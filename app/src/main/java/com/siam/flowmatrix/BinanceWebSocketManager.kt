package com.siam.flowmatrix

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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

// Data events routed per symbol from multi-stream Combined WebSocket
data class SymbolKlineTick(val symbol: String, val candle: CandleOrderFlow, val initialDelta: InitialDeltaSign)
data class SymbolDepthTick(val symbol: String, val imbalance: OrderBookImbalance)
data class SymbolTradeTick(val symbol: String, val trade: TradeSnapshot)

class BinanceWebSocketManager(
    private val client: OkHttpClient = defaultClient,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var reconnectJob: Job? = null

    @Volatile
    private var webSocket: WebSocket? = null

    private val subscribedSymbols = Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile
    private var activeInterval: String = "5m"
    @Volatile
    private var subscribedInterval: String? = null
    @Volatile
    private var activeConnectionMode: ConnectionMode = ConnectionMode.AUTO
    @Volatile
    private var viewSymbol: String = "BTCUSDT"

    private var previousSymbolName: String? = null
    private var lastSubscriptionTimeMsVal: Long = 0L

    @Volatile
    private var reconnectAttempt = 0
    private val isManuallyDisconnected = AtomicBoolean(false)

    // Shared Flow Ticks for Multi-Symbol Session Dispatching
    private val _symbolKlineTicks = MutableSharedFlow<SymbolKlineTick>(extraBufferCapacity = 64)
    val symbolKlineTicks: SharedFlow<SymbolKlineTick> = _symbolKlineTicks.asSharedFlow()

    private val _symbolDepthTicks = MutableSharedFlow<SymbolDepthTick>(extraBufferCapacity = 64)
    val symbolDepthTicks: SharedFlow<SymbolDepthTick> = _symbolDepthTicks.asSharedFlow()

    private val _symbolTradeTicks = MutableSharedFlow<SymbolTradeTick>(extraBufferCapacity = 64)
    val symbolTradeTicks: SharedFlow<SymbolTradeTick> = _symbolTradeTicks.asSharedFlow()

    // View-specific backward-compatible flows for current active view symbol
    private val _liveCandle = MutableStateFlow<CandleOrderFlow?>(null)
    val liveCandle: StateFlow<CandleOrderFlow?> = _liveCandle.asStateFlow()

    private val _initialDeltaSign = MutableStateFlow<InitialDeltaSign>(InitialDeltaSign.WAITING)
    val initialDeltaSign: StateFlow<InitialDeltaSign> = _initialDeltaSign.asStateFlow()

    private val _liveOrderBookImbalance = MutableStateFlow<OrderBookImbalance?>(null)
    val liveOrderBookImbalance: StateFlow<OrderBookImbalance?> = _liveOrderBookImbalance.asStateFlow()

    private val _liveTrade = MutableStateFlow<TradeSnapshot?>(null)
    val liveTrade: StateFlow<TradeSnapshot?> = _liveTrade.asStateFlow()

    private val _tradeStreamStage = MutableStateFlow<WsConnectionStage>(WsConnectionStage.DISCONNECTED)
    val tradeStreamStage: StateFlow<WsConnectionStage> = _tradeStreamStage.asStateFlow()

    private val _tradeSocketUrl = MutableStateFlow<String>("")
    val tradeSocketUrl: StateFlow<String> = _tradeSocketUrl.asStateFlow()

    private val _tradeStreamName = MutableStateFlow<String>("")
    val tradeStreamName: StateFlow<String> = _tradeStreamName.asStateFlow()

    private val _tradeLastError = MutableStateFlow<String?>(null)
    val tradeLastError: StateFlow<String?> = _tradeLastError.asStateFlow()

    private val _rawLastMessage = MutableStateFlow<String>("No messages received")
    val rawLastMessage: StateFlow<String> = _rawLastMessage.asStateFlow()

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Initializing)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    private val aggTradeMsgCount = AtomicLong(0L)
    val tradeMsgCount: Long get() = aggTradeMsgCount.get()

    private val tradeParseFailures = AtomicLong(0L)
    val parseFailuresCount: Long get() = tradeParseFailures.get()

    val reconnectCount: Int get() = reconnectAttempt
    val prevSymbol: String? get() = previousSymbolName
    val currSymbol: String get() = viewSymbol
    val lastSubTimeMs: Long get() = lastSubscriptionTimeMsVal

    private val trackedCandleOpenTimes = ConcurrentHashMap<String, Long>()
    private val lastDepthFrameTimesMs = ConcurrentHashMap<String, Long>()

    fun setViewSymbol(symbol: String) {
        val clean = symbol.trim().uppercase()
        if (clean.isBlank()) return
        if (viewSymbol != clean) {
            previousSymbolName = viewSymbol
            viewSymbol = clean
        }
    }

    fun syncSubscribedSymbols(symbols: Set<String>, interval: String = "5m", mode: ConnectionMode = ConnectionMode.AUTO) {
        val cleanSet = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.toSet()
        if (cleanSet.isEmpty()) return

        val intervalChanged = subscribedInterval != null && subscribedInterval != interval
        val oldInterval = subscribedInterval ?: activeInterval

        activeInterval = interval
        activeConnectionMode = mode
        lastSubscriptionTimeMsVal = System.currentTimeMillis()
        isManuallyDisconnected.set(false)

        val toAdd = cleanSet - subscribedSymbols
        val toRemove = subscribedSymbols - cleanSet

        subscribedSymbols.clear()
        subscribedSymbols.addAll(cleanSet)

        val ws = webSocket
        if (ws != null && _connectionState.value is WebSocketState.Connected) {
            if (toRemove.isNotEmpty()) sendUnsubscribePayload(ws, toRemove)
            if (toAdd.isNotEmpty()) sendSubscribePayload(ws, toAdd)

            // Fix 2: If timeframe interval changed while connected, update kline subscriptions
            if (intervalChanged && cleanSet.isNotEmpty()) {
                sendUnsubscribeKlines(ws, cleanSet, oldInterval)
                sendSubscribeKlines(ws, cleanSet, interval)
            }
            subscribedInterval = interval
        } else {
            subscribedInterval = interval
            connectInternal()
        }
    }

    fun connect(symbol: String, interval: String = "5m", mode: ConnectionMode = ConnectionMode.AUTO) {
        setViewSymbol(symbol)
        val currentSet = subscribedSymbols.toSet() + viewSymbol
        syncSubscribedSymbols(currentSet, interval, mode)
    }

    // Fix 1: Wrap params in org.json.JSONArray(params) so JSONObject serializes a valid JSON array
    private fun sendSubscribePayload(ws: WebSocket, symbols: Collection<String>) {
        if (symbols.isEmpty()) return
        val depthSuffix = if (activeConnectionMode == ConnectionMode.CELLULAR) "@depth20@1000ms" else "@depth20@100ms"
        val params = mutableListOf<String>()
        symbols.forEach { sym ->
            val lower = sym.lowercase()
            params.add("${lower}@kline_$activeInterval")
            params.add("${lower}$depthSuffix")
            params.add("${lower}@trade")
        }
        val json = JSONObject().apply {
            put("method", "SUBSCRIBE")
            put("params", JSONArray(params))
            put("id", System.currentTimeMillis() % 100000)
        }
        Log.d("BinanceWS", "Sending SUBSCRIBE payload: $json")
        ws.send(json.toString())
    }

    private fun sendUnsubscribePayload(ws: WebSocket, symbols: Collection<String>) {
        if (symbols.isEmpty()) return
        val params = mutableListOf<String>()
        symbols.forEach { sym ->
            val lower = sym.lowercase()
            params.add("${lower}@kline_1m")
            params.add("${lower}@kline_5m")
            params.add("${lower}@kline_15m")
            params.add("${lower}@kline_1h")
            params.add("${lower}@depth20@100ms")
            params.add("${lower}@depth20@1000ms")
            params.add("${lower}@trade")
        }
        val json = JSONObject().apply {
            put("method", "UNSUBSCRIBE")
            put("params", JSONArray(params))
            put("id", System.currentTimeMillis() % 100000)
        }
        Log.d("BinanceWS", "Sending UNSUBSCRIBE payload: $json")
        ws.send(json.toString())
    }

    private fun sendSubscribeKlines(ws: WebSocket, symbols: Collection<String>, interval: String) {
        if (symbols.isEmpty()) return
        val params = mutableListOf<String>()
        symbols.forEach { sym ->
            params.add("${sym.lowercase()}@kline_$interval")
        }
        val json = JSONObject().apply {
            put("method", "SUBSCRIBE")
            put("params", JSONArray(params))
            put("id", System.currentTimeMillis() % 100000)
        }
        Log.d("BinanceWS", "Sending SUBSCRIBE Klines payload: $json")
        ws.send(json.toString())
    }

    private fun sendUnsubscribeKlines(ws: WebSocket, symbols: Collection<String>, interval: String) {
        if (symbols.isEmpty()) return
        val params = mutableListOf<String>()
        symbols.forEach { sym ->
            params.add("${sym.lowercase()}@kline_$interval")
        }
        val json = JSONObject().apply {
            put("method", "UNSUBSCRIBE")
            put("params", JSONArray(params))
            put("id", System.currentTimeMillis() % 100000)
        }
        Log.d("BinanceWS", "Sending UNSUBSCRIBE Klines payload: $json")
        ws.send(json.toString())
    }

    @Synchronized
    private fun connectInternal() {
        if (isManuallyDisconnected.get()) return

        reconnectJob?.cancel()
        reconnectJob = null

        val oldWs = webSocket
        webSocket = null
        oldWs?.cancel()

        val wsUrl = "wss://fstream.binance.com/stream"
        _tradeSocketUrl.value = wsUrl
        _tradeStreamName.value = "combined_stream"
        _tradeStreamStage.value = WsConnectionStage.SOCKET_CREATING
        _connectionState.value = WebSocketState.Connecting(reconnectAttempt)

        val request = Request.Builder().url(wsUrl).build()

        val currentWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                Log.d("BinanceWS", "WebSocket connected onOpen")
                reconnectAttempt = 0
                _connectionState.value = WebSocketState.Connected
                _tradeStreamStage.value = WsConnectionStage.SOCKET_OPEN
                _tradeStreamStage.value = WsConnectionStage.SUBSCRIBED

                // AUTO-RESUBVERTISE ALL ACTIVE SYMBOLS ON WEBSOCKET OPEN / RECONNECT
                subscribedInterval = activeInterval
                val symbolsToSub = subscribedSymbols.toSet().ifEmpty { setOf(viewSymbol) }
                sendSubscribePayload(webSocket, symbolsToSub)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                try {
                    _tradeStreamStage.value = WsConnectionStage.RECEIVING_MESSAGES
                    _rawLastMessage.value = if (text.length > 180) text.substring(0, 180) + "..." else text
                    aggTradeMsgCount.incrementAndGet()

                    val json = JSONObject(text)

                    if (json.has("ping")) {
                        val pingVal = json.get("ping")
                        webSocket.send("""{"pong":$pingVal}""")
                        return
                    }

                    val streamName = json.optString("stream", "")
                    val data = if (json.has("data")) json.getJSONObject("data") else json

                    val eventType = data.optString("e", "")
                    val sym = data.optString("s", "").uppercase().ifBlank {
                        if (streamName.contains("@")) streamName.split("@")[0].uppercase() else viewSymbol
                    }

                    when {
                        eventType == "kline" || data.has("k") -> {
                            val kline = data.getJSONObject("k")
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

                            val lastTrackedTime = trackedCandleOpenTimes[sym] ?: 0L
                            val initialDelta = if (openTime != lastTrackedTime) {
                                trackedCandleOpenTimes[sym] = openTime
                                val deltaVal = takerBuyVol - (totalVol - takerBuyVol)
                                when {
                                    deltaVal > 0.0 -> InitialDeltaSign.POSITIVE
                                    deltaVal < 0.0 -> InitialDeltaSign.NEGATIVE
                                    else -> InitialDeltaSign.WAITING
                                }
                            } else InitialDeltaSign.WAITING

                            _symbolKlineTicks.tryEmit(SymbolKlineTick(sym, liveData, initialDelta))

                            if (sym == viewSymbol) {
                                _liveCandle.value = liveData
                                if (initialDelta != InitialDeltaSign.WAITING) {
                                    _initialDeltaSign.value = initialDelta
                                }
                            }
                        }

                        streamName.contains("@depth") || data.has("b") && data.has("a") -> {
                            val now = System.currentTimeMillis()
                            val lastFrameTime = lastDepthFrameTimesMs[sym] ?: 0L
                            if (activeConnectionMode == ConnectionMode.CELLULAR && (now - lastFrameTime < 300L)) {
                                return
                            }
                            lastDepthFrameTimesMs[sym] = now

                            val bidsArray = data.optJSONArray("b")
                            val asksArray = data.optJSONArray("a")

                            var totalBidVol = 0.0
                            if (bidsArray != null) {
                                for (i in 0 until bidsArray.length()) {
                                    val level = bidsArray.optJSONArray(i)
                                    totalBidVol += level?.optString(1)?.toDoubleOrNull() ?: 0.0
                                }
                            }

                            var totalAskVol = 0.0
                            if (asksArray != null) {
                                for (i in 0 until asksArray.length()) {
                                    val level = asksArray.optJSONArray(i)
                                    totalAskVol += level?.optString(1)?.toDoubleOrNull() ?: 0.0
                                }
                            }

                            val totalVol = totalBidVol + totalAskVol
                            if (totalVol > 0.0) {
                                val bidPct = (totalBidVol / totalVol) * 100.0
                                val askPct = (totalAskVol / totalVol) * 100.0
                                val imbalance = OrderBookImbalance(
                                    bidVolume = totalBidVol,
                                    askVolume = totalAskVol,
                                    bidPercentage = bidPct,
                                    askPercentage = askPct
                                )
                                _symbolDepthTicks.tryEmit(SymbolDepthTick(sym, imbalance))
                                if (sym == viewSymbol) {
                                    _liveOrderBookImbalance.value = imbalance
                                }
                            }
                        }

                        eventType == "trade" || eventType == "aggTrade" || data.has("p") && data.has("q") -> {
                            val price = data.optString("p", "0.0").toDoubleOrNull() ?: 0.0
                            val qty = data.optString("q", "0.0").toDoubleOrNull() ?: 0.0
                            val isBuyerMaker = data.optBoolean("m", false)
                            val tradeTime = data.optLong("T", System.currentTimeMillis())

                            if (price > 0.0 && qty > 0.0) {
                                val tradeSnap = TradeSnapshot(
                                    timestampMs = tradeTime,
                                    price = price,
                                    quantity = qty,
                                    isBuyerMaker = isBuyerMaker
                                )
                                _symbolTradeTicks.tryEmit(SymbolTradeTick(sym, tradeSnap))
                                if (sym == viewSymbol) {
                                    _liveTrade.value = tradeSnap
                                }
                            } else {
                                tradeParseFailures.incrementAndGet()
                            }
                        }
                    }
                } catch (e: Exception) {
                    tradeParseFailures.incrementAndGet()
                    _tradeLastError.value = e.message ?: "JSON parse error"
                }
            }

            // Fix 1: Log code and reason in onClosing, onClosed, and onFailure
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                Log.w("BinanceWS", "WebSocket onClosing: code=$code, reason=$reason")
                _tradeStreamStage.value = WsConnectionStage.DISCONNECTED
                webSocket.close(1000, null)
                handleDisconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                Log.w("BinanceWS", "WebSocket onClosed: code=$code, reason=$reason")
                _tradeStreamStage.value = WsConnectionStage.DISCONNECTED
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@BinanceWebSocketManager.webSocket != webSocket) return
                Log.e("BinanceWS", "WebSocket onFailure: message=${t.message}, response code=${response?.code}", t)
                _tradeStreamStage.value = WsConnectionStage.ERROR
                _tradeLastError.value = t.message ?: "Connection failure"
                handleDisconnect()
            }

            private fun handleDisconnect() {
                if (!isManuallyDisconnected.get()) {
                    scheduleReconnect()
                }
            }
        })

        this.webSocket = currentWs
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

        _connectionState.value = WebSocketState.Disconnected(null)
        _tradeStreamStage.value = WsConnectionStage.DISCONNECTED
    }

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