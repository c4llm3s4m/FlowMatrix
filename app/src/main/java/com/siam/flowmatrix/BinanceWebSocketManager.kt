package com.siam.flowmatrix

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * Represents the structured connection state of the Binance WebSocket.
 */
sealed interface WebSocketState {
    data object Initializing : WebSocketState
    data class Connecting(val attempt: Int) : WebSocketState
    data object Connected : WebSocketState
    data class Disconnected(val retryDelayMs: Long? = null) : WebSocketState

    fun toDisplayString(): String = when (this) {
        Initializing -> "Initializing..."
        is Connecting -> if (attempt == 0) "🟡 Connecting (Futures WS)..." else "🟡 Reconnecting (Attempt $attempt)..."
        Connected -> "🟢 LIVE (Futures WS)"
        is Disconnected -> if (retryDelayMs != null) {
            val seconds = String.format(Locale.US, "%.1f", retryDelayMs / 1000.0)
            "🔴 Disconnected. Retrying in ${seconds}s..."
        } else {
            "⚪ Disconnected"
        }
    }
}

class BinanceWebSocketManager(
    private val client: OkHttpClient = defaultClient,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var reconnectJob: Job? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var depthWebSocket: WebSocket? = null

    private var activeSymbol: String = "BTCUSDT"
    private var activeInterval: String = "5m"

    @Volatile
    private var reconnectAttempt = 0
    private val isManuallyDisconnected = AtomicBoolean(false)

    private val _liveCandle = MutableStateFlow<CandleOrderFlow?>(null)
    val liveCandle: StateFlow<CandleOrderFlow?> = _liveCandle.asStateFlow()

    private val _initialDeltaSign = MutableStateFlow<InitialDeltaSign>(InitialDeltaSign.WAITING)
    val initialDeltaSign: StateFlow<InitialDeltaSign> = _initialDeltaSign.asStateFlow()

    private val _liveOrderBookImbalance = MutableStateFlow<OrderBookImbalance?>(null)
    val liveOrderBookImbalance: StateFlow<OrderBookImbalance?> = _liveOrderBookImbalance.asStateFlow()

    @Volatile
    private var trackedCandleOpenTime = 0L

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Initializing)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    fun connect(symbol: String, interval: String = "5m") {
        val trimmed = symbol.trim().uppercase()
        if (trimmed.isBlank()) return

        activeSymbol = if (!trimmed.endsWith("USDT") && !trimmed.endsWith("BUSD")) {
            "${trimmed}USDT"
        } else {
            trimmed
        }
        activeInterval = interval
        isManuallyDisconnected.set(false)
        reconnectAttempt = 0
        trackedCandleOpenTime = 0L
        _initialDeltaSign.value = InitialDeltaSign.WAITING
        _liveOrderBookImbalance.value = null

        connectInternal()
    }

    @Synchronized
    private fun connectInternal() {
        if (isManuallyDisconnected.get()) return

        // Cancel pending reconnection timer
        reconnectJob?.cancel()
        reconnectJob = null

        // Cancel existing WebSocket references safely
        val oldWs = webSocket
        webSocket = null
        oldWs?.cancel()

        val oldDepthWs = depthWebSocket
        depthWebSocket = null
        oldDepthWs?.cancel()

        val lowerSymbol = activeSymbol.lowercase()

        // 1. Primary Dedicated Kline WebSocket (@kline) - 100% Isolated for live price, aggression & initial pulse
        val wsUrl = "wss://fstream.binance.com/market/ws/${lowerSymbol}@kline_$activeInterval"
        _connectionState.value = WebSocketState.Connecting(reconnectAttempt)

        val request = Request.Builder().url(wsUrl).build()

        val currentWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                reconnectAttempt = 0
                _connectionState.value = WebSocketState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                try {
                    val json = JSONObject(text)

                    // Respond to server text ping frame if present
                    if (json.has("ping")) {
                        val pingVal = json.get("ping")
                        webSocket.send("""{"pong":$pingVal}""")
                        return
                    }

                    if (!json.has("k")) return
                    val kline = json.getJSONObject("k")

                    val totalVol = kline.optString("v", "0.0").toDoubleOrNull() ?: 0.0
                    val takerBuyVol = kline.optString("V", "0.0").toDoubleOrNull() ?: (totalVol / 2.0)
                    val openTime = kline.optLong("t", System.currentTimeMillis())

                    val liveData = CandleOrderFlow(
                        openTime = openTime,
                        open = kline.optString("o", "0.0").toDoubleOrNull() ?: 0.0,
                        high = kline.optString("h", "0.0").toDoubleOrNull() ?: 0.0,
                        low = kline.optString("l", "0.0").toDoubleOrNull() ?: 0.0,
                        close = kline.optString("c", "0.0").toDoubleOrNull() ?: 0.0,
                        totalVolume = totalVol,
                        takerBuyVolume = takerBuyVol
                    )

                    // Capture Initial Delta Sign on first incoming tick of a newly opened bar
                    if (openTime != trackedCandleOpenTime) {
                        trackedCandleOpenTime = openTime
                        val initialDelta = takerBuyVol - (totalVol - takerBuyVol)
                        _initialDeltaSign.value = when {
                            initialDelta > 0.0 -> InitialDeltaSign.POSITIVE
                            initialDelta < 0.0 -> InitialDeltaSign.NEGATIVE
                            else -> InitialDeltaSign.WAITING
                        }
                    }

                    _liveCandle.value = liveData
                    if (_connectionState.value !is WebSocketState.Connected) {
                        _connectionState.value = WebSocketState.Connected
                    }
                } catch (_: Exception) {
                    // Ignored or logged
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                webSocket.close(1000, null)
                handleDisconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                handleDisconnect()
            }

            private fun handleDisconnect() {
                if (!isManuallyDisconnected.get()) {
                    scheduleReconnect()
                }
            }
        })

        this.webSocket = currentWs

        // 2. Parallel Depth WebSocket (@depth20@100ms) - Dedicated stream for real-time Order Book Imbalance
        val depthWsUrl = "wss://fstream.binance.com/ws/${lowerSymbol}@depth20@100ms"
        val depthRequest = Request.Builder().url(depthWsUrl).build()

        val currentDepthWs = client.newWebSocket(depthRequest, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@BinanceWebSocketManager.depthWebSocket != webSocket) return
                try {
                    val json = JSONObject(text)
                    if (json.has("b") && json.has("a")) {
                        val bidsArray = json.optJSONArray("b")
                        val asksArray = json.optJSONArray("a")

                        var totalBidVol = 0.0
                        if (bidsArray != null) {
                            for (i in 0 until bidsArray.length()) {
                                val level = bidsArray.optJSONArray(i)
                                val qty = level?.optString(1)?.toDoubleOrNull() ?: 0.0
                                totalBidVol += qty
                            }
                        }

                        var totalAskVol = 0.0
                        if (asksArray != null) {
                            for (i in 0 until asksArray.length()) {
                                val level = asksArray.optJSONArray(i)
                                val qty = level?.optString(1)?.toDoubleOrNull() ?: 0.0
                                totalAskVol += qty
                            }
                        }

                        val totalVol = totalBidVol + totalAskVol
                        if (totalVol > 0.0) {
                            val bidPct = (totalBidVol / totalVol) * 100.0
                            val askPct = (totalAskVol / totalVol) * 100.0
                            _liveOrderBookImbalance.value = OrderBookImbalance(
                                bidVolume = totalBidVol,
                                askVolume = totalAskVol,
                                bidPercentage = bidPct,
                                askPercentage = askPct
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Ignored or logged
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (this@BinanceWebSocketManager.depthWebSocket != webSocket) return
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Handled gracefully without affecting main kline socket
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Handled gracefully without affecting main kline socket
            }
        })

        this.depthWebSocket = currentDepthWs
    }

    @Synchronized
    private fun scheduleReconnect() {
        if (isManuallyDisconnected.get()) return
        if (reconnectJob?.isActive == true) return

        val baseDelay = 1000L
        val maxDelay = 15000L
        val exponentialMultiplier = (1L shl min(reconnectAttempt, 4))
        val calculatedDelay = baseDelay * exponentialMultiplier

        val jitter = Random.nextLong(0, 500)
        val finalDelay = min(maxDelay, calculatedDelay + jitter)

        reconnectAttempt++
        _connectionState.value = WebSocketState.Disconnected(finalDelay)

        reconnectJob = scope.launch {
            delay(finalDelay.milliseconds)
            connectInternal()
        }
    }

    fun reconnect() {
        reconnectAttempt = 0
        isManuallyDisconnected.set(false)
        connectInternal()
    }

    fun disconnect() {
        isManuallyDisconnected.set(true)
        reconnectJob?.cancel()
        reconnectJob = null

        val ws = webSocket
        webSocket = null
        ws?.cancel()

        val dWs = depthWebSocket
        depthWebSocket = null
        dWs?.cancel()

        _connectionState.value = WebSocketState.Disconnected(null)
    }

    /**
     * Cleans up coroutine scope resources when manager is no longer needed.
     */
    fun close() {
        disconnect()
        scope.cancel()
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}