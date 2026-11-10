package com.siam.flowmatrix

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class CardOrderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("flow_matrix_card_prefs", Context.MODE_PRIVATE)

    fun getCardOrder(): List<TelemetryCardType> {
        val savedString = prefs.getString("card_order_v1", null) ?: return TelemetryCardType.DEFAULT_ORDER
        val ids = savedString.split(",")
        val savedOrder = ids.mapNotNull { TelemetryCardType.fromId(it) }.distinct()
        val missingCards = TelemetryCardType.entries.filter { it !in savedOrder }
        val fullOrder = savedOrder + missingCards
        return if (fullOrder.size == TelemetryCardType.entries.size) fullOrder else TelemetryCardType.DEFAULT_ORDER
    }

    fun saveCardOrder(order: List<TelemetryCardType>) {
        val stringToSave = order.joinToString(",") { it.id }
        prefs.edit().putString("card_order_v1", stringToSave).apply()
    }

    fun getConnectionMode(): ConnectionMode {
        val savedId = prefs.getString("connection_mode_v1", ConnectionMode.AUTO.id) ?: ConnectionMode.AUTO.id
        return ConnectionMode.fromId(savedId)
    }

    fun saveConnectionMode(mode: ConnectionMode) {
        prefs.edit().putString("connection_mode_v1", mode.id).apply()
    }
}



data class DashboardState(
    val symbol: String = "BTCUSDT",
    val interval: String = "5m",
    val isLoading: Boolean = true,
    val connectionStatus: String = "Initializing...",
    val liveCandle: CandleOrderFlow? = null,
    val initialDeltaSign: InitialDeltaSign = InitialDeltaSign.WAITING,
    val openInterest: List<OpenInterestHistResponse> = emptyList(),
    val takerLongShort: List<TakerLongShortRatioResponse> = emptyList(),
    val globalLongShort: List<GlobalLongShortRatioResponse> = emptyList(),
    val topTraderAccount: List<TopTraderLongShortRatioResponse> = emptyList(),
    val topTraderPosition: List<TopTraderLongShortRatioResponse> = emptyList(),
    val positioningMatrix: PositioningMatrix? = null,
    val orderBookImbalance: OrderBookImbalance? = null,
    val oiFlowRegime: OIFlowRegimeState = OIFlowRegimeState(),
    val candles: List<CandleOrderFlow> = emptyList(),
    val cardOrder: List<TelemetryCardType> = TelemetryCardType.DEFAULT_ORDER,
    val connectionMode: ConnectionMode = ConnectionMode.AUTO,
    val activeMode: ConnectionMode = ConnectionMode.BROADBAND,
    val error: String? = null
)

class FlowMatrixViewModel(application: Application) : AndroidViewModel(application) {
    private val fetcher = BinanceDataFetcher()
    private val wsManager = BinanceWebSocketManager()
    private val prefsManager = CardOrderPreferences(application)

    private val snapshotBuffer = ArrayDeque<FlowSnapshot>()
    private var lastValidRegime: String? = null

    private val _uiState = MutableStateFlow(
        DashboardState(
            cardOrder = prefsManager.getCardOrder(),
            connectionMode = prefsManager.getConnectionMode()
        )
    )
    val uiState: StateFlow<DashboardState> = _uiState.asStateFlow()

    init {
        val savedMode = prefsManager.getConnectionMode()
        val effective = detectEffectiveMode(savedMode)
        _uiState.update { it.copy(connectionMode = savedMode, activeMode = effective) }

        startPolling()
        listenToWebSocket()
        wsManager.connect(_uiState.value.symbol, _uiState.value.interval, effective)
    }

    private fun detectEffectiveMode(mode: ConnectionMode): ConnectionMode {
        if (mode != ConnectionMode.AUTO) return mode
        return try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNetwork)
            if (caps != null) {
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                if (isCellular || isMetered) ConnectionMode.CELLULAR else ConnectionMode.BROADBAND
            } else ConnectionMode.BROADBAND
        } catch (_: Exception) {
            ConnectionMode.BROADBAND
        }
    }

    private fun getTimeframeDurationMs(interval: String): Long = when (interval) {
        "1m" -> 60_000L
        "5m" -> 300_000L
        "15m" -> 900_000L
        "1h" -> 3_600_000L
        else -> 300_000L
    }

    fun setConnectionMode(mode: ConnectionMode) {
        prefsManager.saveConnectionMode(mode)
        val effective = detectEffectiveMode(mode)
        _uiState.update { it.copy(connectionMode = mode, activeMode = effective) }
        wsManager.connect(_uiState.value.symbol, _uiState.value.interval, effective)
    }

    fun moveCardUp(card: TelemetryCardType) {
        val current = _uiState.value.cardOrder.toMutableList()
        val index = current.indexOf(card)
        if (index > 0) {
            val temp = current[index]
            current[index] = current[index - 1]
            current[index - 1] = temp
            _uiState.update { it.copy(cardOrder = current) }
            prefsManager.saveCardOrder(current)
        }
    }

    fun moveCardDown(card: TelemetryCardType) {
        val current = _uiState.value.cardOrder.toMutableList()
        val index = current.indexOf(card)
        if (index in 0 until current.size - 1) {
            val temp = current[index]
            current[index] = current[index + 1]
            current[index + 1] = temp
            _uiState.update { it.copy(cardOrder = current) }
            prefsManager.saveCardOrder(current)
        }
    }

    fun resetCardOrder() {
        val defaultOrder = TelemetryCardType.DEFAULT_ORDER
        _uiState.update { it.copy(cardOrder = defaultOrder) }
        prefsManager.saveCardOrder(defaultOrder)
    }

    private fun listenToWebSocket() {
        viewModelScope.launch {
            wsManager.liveCandle.collect { liveTick ->
                if (liveTick != null) {
                    _uiState.update { it.copy(liveCandle = liveTick) }
                }
            }
        }

        viewModelScope.launch {
            wsManager.initialDeltaSign.collect { sign ->
                _uiState.update { it.copy(initialDeltaSign = sign) }
            }
        }

        viewModelScope.launch {
            wsManager.liveOrderBookImbalance.collect { imbalance ->
                if (imbalance != null) {
                    _uiState.update { it.copy(orderBookImbalance = imbalance) }
                }
            }
        }

        viewModelScope.launch {
            wsManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionStatus = state.toDisplayString()) }
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                fetchDashboardData(_uiState.value.symbol, _uiState.value.interval)
                delay(30000L) // Refresh historical data every 30 seconds
            }
        }
    }

    fun setSymbol(newSymbol: String) {
        val cleanSymbol = newSymbol.trim().uppercase()
        if (cleanSymbol == _uiState.value.symbol) return
        updateCoreState(cleanSymbol, _uiState.value.interval)
    }

    fun setTimeframe(newInterval: String) {
        if (newInterval == _uiState.value.interval) return
        updateCoreState(_uiState.value.symbol, newInterval)
    }

    fun refresh() {
        wsManager.reconnect()
        viewModelScope.launch {
            fetchDashboardData(_uiState.value.symbol, _uiState.value.interval)
        }
    }

    private fun updateCoreState(symbol: String, interval: String) {
        snapshotBuffer.clear()
        lastValidRegime = null
        val effective = detectEffectiveMode(_uiState.value.connectionMode)
        _uiState.update {
            it.copy(
                symbol = symbol,
                interval = interval,
                isLoading = true,
                liveCandle = null,
                initialDeltaSign = InitialDeltaSign.WAITING,
                orderBookImbalance = null,
                oiFlowRegime = OIFlowRegimeState(),
                activeMode = effective,
                error = null
            )
        }
        wsManager.connect(symbol, interval, effective)
        viewModelScope.launch { fetchDashboardData(symbol, interval) }
    }

    private suspend fun fetchDashboardData(symbol: String, interval: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        val macroPeriod = if (interval == "1m") "5m" else interval

        coroutineScope {
            val klinesDeferred = async { runCatching { fetcher.getCandleOrderFlow(symbol, interval) }.getOrDefault(emptyList()) }
            val oiDeferred = async { runCatching { fetcher.getOpenInterestHistory(symbol, macroPeriod) }.getOrDefault(emptyList()) }
            val takerLSDeferred = async { runCatching { fetcher.getTakerLongShortRatio(symbol, macroPeriod) }.getOrDefault(emptyList()) }
            val globalLSDeferred = async { runCatching { fetcher.getGlobalLongShortRatio(symbol, macroPeriod) }.getOrDefault(emptyList()) }
            val topAccountDeferred = async { runCatching { fetcher.getTopTraderAccountRatio(symbol, macroPeriod) }.getOrDefault(emptyList()) }
            val topPositionDeferred = async { runCatching { fetcher.getTopTraderPositionRatio(symbol, macroPeriod) }.getOrDefault(emptyList()) }
            val depthDeferred = async {
                runCatching { fetcher.getOrderBookDepth(symbol, limit = 20) }.getOrElse {
                    runCatching { fetcher.getSpotOrderBookDepth(symbol, limit = 20) }.getOrNull()
                }
            }

            val klines = klinesDeferred.await()
            val oi = oiDeferred.await()
            val takerLS = takerLSDeferred.await()
            val globalLS = globalLSDeferred.await()
            val topAccount = topAccountDeferred.await()
            val topPosition = topPositionDeferred.await()
            val depth = depthDeferred.await()

            // Calculate Positioning Matrix
            val retailRatio = globalLS.lastOrNull()?.longShortRatio?.toDoubleOrNull() ?: 1.0
            val whaleAccRatio = topAccount.lastOrNull()?.longShortRatio?.toDoubleOrNull() ?: 1.0
            val whalePosRatio = topPosition.lastOrNull()?.longShortRatio?.toDoubleOrNull() ?: 1.0

            val prevRetail = if (globalLS.size > 1) globalLS[globalLS.size - 2].longShortRatio.toDoubleOrNull() ?: retailRatio else retailRatio
            val prevWhaleAcc = if (topAccount.size > 1) topAccount[topAccount.size - 2].longShortRatio.toDoubleOrNull() ?: whaleAccRatio else whaleAccRatio
            val prevWhalePos = if (topPosition.size > 1) topPosition[topPosition.size - 2].longShortRatio.toDoubleOrNull() ?: whalePosRatio else whalePosRatio

            val positioningMatrix = PositioningMatrix(
                retailRatio = retailRatio,
                retailChangeDown = retailRatio < prevRetail,
                whaleAccountRatio = whaleAccRatio,
                whaleAccountChangeDown = whaleAccRatio < prevWhaleAcc,
                whalePositionRatio = whalePosRatio,
                whalePositionChangeDown = whalePosRatio < prevWhalePos
            )

            // --- FLOW SNAPSHOT BUFFER & ENHANCED REGIME ENGINE ---
            val currentOi = oi.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0
            val currentPrice = _uiState.value.liveCandle?.close
                ?: klines.lastOrNull()?.close
                ?: 0.0
            val now = System.currentTimeMillis()

            if (currentOi > 0.0 && currentPrice > 0.0) {
                snapshotBuffer.addLast(FlowSnapshot(now, currentPrice, currentOi))
            }

            // Prune snapshots older than 2 hours to prevent unbounded memory growth
            val maxHistoryCutoff = now - 7_200_000L
            while (snapshotBuffer.isNotEmpty() && snapshotBuffer.first().timestampMs < maxHistoryCutoff) {
                snapshotBuffer.removeFirst()
            }

            // Locate newest snapshot whose age is at least targetDurationMs
            val targetDurationMs = getTimeframeDurationMs(interval)
            val anchorSnapshot = snapshotBuffer.lastOrNull { snapshot ->
                (now - snapshot.timestampMs) >= targetDurationMs
            }

            val oiFlowRegimeState = if (anchorSnapshot == null) {
                OIFlowRegimeState(
                    regime = "BUILDING WINDOW",
                    mechanics = "Waiting for sufficient historical data",
                    badgeColor = Color(0xFF9CA3AF),
                    strength = "NONE",
                    strengthColor = Color(0xFF9CA3AF),
                    confirmation = null,
                    previousRegime = lastValidRegime,
                    priceDeltaPercent = 0.0,
                    oiDeltaContracts = 0.0,
                    oiDeltaPercent = 0.0,
                    buildingWindow = true
                )
            } else {
                val deltaPrice = currentPrice - anchorSnapshot.price
                val deltaPricePct = if (anchorSnapshot.price > 0.0) ((currentPrice - anchorSnapshot.price) / anchorSnapshot.price) * 100.0 else 0.0
                val deltaOI = currentOi - anchorSnapshot.openInterest
                val deltaOIPct = if (anchorSnapshot.openInterest > 0.0) ((currentOi - anchorSnapshot.openInterest) / anchorSnapshot.openInterest) * 100.0 else 0.0

                // Mandatory Significance Filter Check
                val passesSignificanceFilter = abs(deltaPricePct) >= 0.15 && abs(deltaOIPct) >= 0.25

                if (!passesSignificanceFilter) {
                    OIFlowRegimeState(
                        regime = "NEUTRAL",
                        mechanics = "Flow expansion below threshold",
                        badgeColor = Color(0xFF9CA3AF),
                        strength = "LOW",
                        strengthColor = Color(0xFF9CA3AF),
                        confirmation = null,
                        previousRegime = lastValidRegime,
                        priceDeltaPercent = deltaPricePct,
                        oiDeltaContracts = deltaOI,
                        oiDeltaPercent = deltaOIPct,
                        buildingWindow = false
                    )
                } else {
                    val (regimeStr, mechanicsStr, colorVal) = when {
                        deltaOI > 0.0 && deltaPrice > 0.0 -> Triple("NEW LONGS", "Fresh capital entering Longs (Buy-to-Open)", Color(0xFF10B981))
                        deltaOI > 0.0 && deltaPrice < 0.0 -> Triple("NEW SHORTS", "Fresh capital entering Shorts (Sell-to-Open)", Color(0xFFEF4444))
                        deltaOI < 0.0 && deltaPrice > 0.0 -> Triple("SHORT COVERING", "Shorts buying back positions (Buy-to-Close)", Color(0xFFEAB308))
                        deltaOI < 0.0 && deltaPrice < 0.0 -> Triple("LONG LIQUIDATIONS", "Longs exiting positions (Sell-to-Close)", Color(0xFFF97316))
                        else -> Triple("NEUTRAL", "Waiting for next OI delta", Color(0xFF9CA3AF))
                    }

                    // Regime Strength Engine
                    val absOI = abs(deltaOIPct)
                    val oiScore = when {
                        absOI >= 3.0 -> 3
                        absOI >= 1.0 -> 2
                        else -> 1
                    }

                    val absPrice = abs(deltaPricePct)
                    val priceScore = when {
                        absPrice >= 3.0 -> 3
                        absPrice >= 1.0 -> 2
                        else -> 1
                    }

                    val totalStrengthScore = oiScore + priceScore
                    val (strengthText, strColor) = when {
                        totalStrengthScore >= 5 -> "HIGH" to Color(0xFF10B981)
                        totalStrengthScore >= 3 -> "MEDIUM" to Color(0xFFEAB308)
                        else -> "LOW" to Color(0xFF9CA3AF)
                    }

                    // Transition Tracking Engine
                    val prevRegime = lastValidRegime
                    val transitionStr = if (prevRegime != null && prevRegime != regimeStr) {
                        "$prevRegime → $regimeStr"
                    } else null

                    if (regimeStr != "NEUTRAL" && regimeStr != lastValidRegime) {
                        lastValidRegime = regimeStr
                    }

                    // CVD Confirmation Engine
                    val currentCvd = klines.sumOf { it.volumeDelta }
                    val cvdConfirm = when {
                        regimeStr == "NEW LONGS" && currentCvd > 0.0 -> "CVD CONFIRMED"
                        regimeStr == "NEW SHORTS" && currentCvd < 0.0 -> "CVD CONFIRMED"
                        (regimeStr == "NEW LONGS" && currentCvd < 0.0) || (regimeStr == "NEW SHORTS" && currentCvd > 0.0) -> "DIVERGENCE"
                        else -> null
                    }

                    OIFlowRegimeState(
                        regime = regimeStr,
                        mechanics = mechanicsStr,
                        badgeColor = colorVal,
                        strength = strengthText,
                        strengthColor = strColor,
                        confirmation = cvdConfirm,
                        previousRegime = transitionStr,
                        priceDeltaPercent = deltaPricePct,
                        oiDeltaContracts = deltaOI,
                        oiDeltaPercent = deltaOIPct,
                        buildingWindow = false
                    )
                }
            }

            // Calculate initial REST depth fallback ONLY if real-time WS imbalance is not yet set
            val currentWsImbalance = _uiState.value.orderBookImbalance
            val fallbackImbalance = if (currentWsImbalance == null && depth != null && (depth.bids.isNotEmpty() || depth.asks.isNotEmpty())) {
                val totalBids = depth.bids.sumOf { it.getOrNull(1)?.toDoubleOrNull() ?: 0.0 }
                val totalAsks = depth.asks.sumOf { it.getOrNull(1)?.toDoubleOrNull() ?: 0.0 }
                val totalVol = totalBids + totalAsks
                if (totalVol > 0.0) {
                    val bidPct = (totalBids / totalVol) * 100.0
                    val askPct = (totalAsks / totalVol) * 100.0
                    OrderBookImbalance(
                        bidVolume = totalBids,
                        askVolume = totalAsks,
                        bidPercentage = bidPct,
                        askPercentage = askPct
                    )
                } else null
            } else null

            val finalImbalance = currentWsImbalance ?: fallbackImbalance

            // If Futures klines failed or were blocked, try Spot klines API as fallback
            val finalKlines = klines.ifEmpty {
                runCatching { fetcher.getSpotCandleOrderFlow(symbol, interval) }.getOrDefault(emptyList())
            }

            val errorMessage = if (finalKlines.isEmpty() && oi.isEmpty() && takerLS.isEmpty() && globalLS.isEmpty()) {
                "Unable to reach Binance REST API. Reconnecting WebSocket..."
            } else null

            _uiState.update {
                it.copy(
                    isLoading = false,
                    openInterest = oi,
                    takerLongShort = takerLS,
                    globalLongShort = globalLS,
                    topTraderAccount = topAccount,
                    topTraderPosition = topPosition,
                    positioningMatrix = positioningMatrix,
                    orderBookImbalance = finalImbalance,
                    oiFlowRegime = oiFlowRegimeState,
                    candles = finalKlines,
                    error = errorMessage
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        wsManager.disconnect()
    }
}