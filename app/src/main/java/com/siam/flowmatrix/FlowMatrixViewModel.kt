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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    fun getAppMode(): AppMode {
        val savedId = prefs.getString("app_mode_v1", AppMode.GEMINI.id) ?: AppMode.GEMINI.id
        return AppMode.fromId(savedId)
    }

    fun saveAppMode(mode: AppMode) {
        prefs.edit().putString("app_mode_v1", mode.id).apply()
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
    val orderFlowRegime: OrderFlowRegimeState = OrderFlowRegimeState(),
    val flowConsensus: FlowConsensusState = FlowConsensusState(),
    val candles: List<CandleOrderFlow> = emptyList(),
    val cardOrder: List<TelemetryCardType> = TelemetryCardType.DEFAULT_ORDER,
    val connectionMode: ConnectionMode = ConnectionMode.AUTO,
    val activeMode: ConnectionMode = ConnectionMode.BROADBAND,
    val appMode: AppMode = AppMode.GEMINI,
    val error: String? = null
)

class FlowMatrixViewModel(application: Application) : AndroidViewModel(application) {
    private val fetcher = BinanceDataFetcher()
    private val wsManager = BinanceWebSocketManager()
    private val prefsManager = CardOrderPreferences(application)

    private val snapshotBuffer = ArrayDeque<FlowSnapshot>()
    private val tradeSnapshotBuffer = ArrayDeque<TradeSnapshot>()
    private val tradeRateWindow = ArrayDeque<Long>()

    // Consensus Memory Engine State
    private var memoryLastConsensus: String? = null
    private var memoryLastNarrative: String? = null
    private var memoryLastTimestampMs: Long? = null
    private var memoryLastBadgeColor: Color = Color(0xFF9CA3AF)
    private val memoryHistoryList = ArrayDeque<ConsensusHistoryEvent>()

    private var lastValidRegime: String? = null
    private var lastOiPollTimeMs: Long = 0L
    private var lastPriceTickTimeMs: Long = 0L
    private var lastTradeTimeMs: Long = 0L
    private var lastRegimeTransitionTimeMs: Long = 0L

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val millisFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _uiState = MutableStateFlow(
        DashboardState(
            cardOrder = prefsManager.getCardOrder(),
            connectionMode = prefsManager.getConnectionMode(),
            appMode = prefsManager.getAppMode()
        )
    )
    val uiState: StateFlow<DashboardState> = _uiState.asStateFlow()

    val appModeState: StateFlow<AppMode> = _uiState
        .map { it.appMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value.appMode)

    init {
        val savedMode = prefsManager.getConnectionMode()
        val savedAppMode = prefsManager.getAppMode()
        val effective = detectEffectiveMode(savedMode)
        _uiState.update { it.copy(connectionMode = savedMode, activeMode = effective, appMode = savedAppMode) }

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

    private fun formatAge(ageMs: Long): String {
        val sec = ageMs / 1000L
        return when {
            sec < 60 -> "${sec} sec ago"
            sec < 3600 -> "${sec / 60} min ago"
            else -> "${sec / 3600} hr ago"
        }
    }

    fun setAppMode(mode: AppMode) {
        prefsManager.saveAppMode(mode)
        _uiState.update { it.copy(appMode = mode) }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        prefsManager.saveConnectionMode(mode)
        val effective = detectEffectiveMode(mode)
        _uiState.update { it.copy(connectionMode = mode, activeMode = effective) }
        wsManager.connect(_uiState.value.symbol, _uiState.value.interval, effective)
    }

    fun toggleOiDebugMode() {
        _uiState.update {
            val currentDebug = it.oiFlowRegime.debugState
            val toggled = currentDebug.copy(isDebugEnabled = !currentDebug.isDebugEnabled)
            it.copy(oiFlowRegime = it.oiFlowRegime.copy(debugState = toggled))
        }
    }

    fun toggleOrderFlowDebugMode() {
        _uiState.update {
            val currentDebug = it.orderFlowRegime.debugState
            val toggled = currentDebug.copy(isDebugEnabled = !currentDebug.isDebugEnabled)
            it.copy(orderFlowRegime = it.orderFlowRegime.copy(debugState = toggled))
        }
    }

    fun toggleFlowConsensusDebugMode() {
        _uiState.update {
            val currentDebug = it.flowConsensus.debugState
            val toggled = currentDebug.copy(isDebugEnabled = !currentDebug.isDebugEnabled)
            it.copy(flowConsensus = it.flowConsensus.copy(debugState = toggled))
        }
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
                    lastPriceTickTimeMs = System.currentTimeMillis()
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
            wsManager.liveTrade.collect { trade ->
                if (trade != null) {
                    processLiveTradeSnapshot(trade)
                }
            }
        }

        viewModelScope.launch {
            wsManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionStatus = state.toDisplayString()) }
            }
        }
    }

    // --- PHASE 5 & MEMORY ENGINE: DUAL ENGINE CONSENSUS & TRAP DETECTION ENGINE ---
    private fun evaluateFlowConsensus(
        oiState: OIFlowRegimeState,
        ofState: OrderFlowRegimeState
    ): FlowConsensusState {
        val oiRegime = oiState.regime.uppercase()
        val ofRegime = ofState.regime.uppercase()
        val oiStrength = oiState.strength.uppercase()
        val cvdConfirm = oiState.confirmation

        val (candidateConsensus, candidateBadgeColor, narrativeText, ruleName) = when {
            // STATE 1: STRONG BULLISH CONTINUATION
            oiRegime == "NEW LONGS" && ofRegime == "BUY AGGRESSION" -> Quadruple(
                "STRONG BULLISH CONTINUATION",
                Color(0xFF10B981),
                "Fresh longs entering while buyers control short-term order flow.",
                "NEW_LONGS + BUY_AGGRESSION => STRONG_BULLISH_CONTINUATION"
            )

            // STATE 2: STRONG BEARISH CONTINUATION
            oiRegime == "NEW SHORTS" && ofRegime == "SELL AGGRESSION" -> Quadruple(
                "STRONG BEARISH CONTINUATION",
                Color(0xFFEF4444),
                "Fresh shorts entering while sellers control short-term order flow.",
                "NEW_SHORTS + SELL_AGGRESSION => STRONG_BEARISH_CONTINUATION"
            )

            // STATE 3: SHORT TRAP
            oiRegime == "NEW SHORTS" && ofRegime == "BUY AGGRESSION" -> Quadruple(
                "SHORT TRAP",
                Color(0xFFF59E0B),
                "New shorts entering while buyers absorb and push price higher. Potential squeeze risk.",
                "NEW_SHORTS + BUY_AGGRESSION => SHORT_TRAP"
            )

            // STATE 4: LONG TRAP
            oiRegime == "NEW LONGS" && ofRegime == "SELL AGGRESSION" -> Quadruple(
                "LONG TRAP",
                Color(0xFFF97316),
                "New longs entering while sellers control aggressive flow. Potential downside trap.",
                "NEW_LONGS + SELL_AGGRESSION => LONG_TRAP"
            )

            // STATE 5: CAPITULATION REVERSAL
            oiRegime == "LONG LIQUIDATIONS" && ofRegime == "BUY AGGRESSION" -> Quadruple(
                "CAPITULATION REVERSAL",
                Color(0xFFEAB308),
                "Long liquidation event followed by aggressive buying pressure. Potential local bottom.",
                "LONG_LIQUIDATIONS + BUY_AGGRESSION => CAPITULATION_REVERSAL"
            )

            // STATE 6: SHORT SQUEEZE
            oiRegime == "SHORT COVERING" && ofRegime == "BUY AGGRESSION" -> Quadruple(
                "SHORT SQUEEZE",
                Color(0xFF22C55E),
                "Shorts exiting while buyers continue lifting offers. Potential squeeze continuation.",
                "SHORT_COVERING + BUY_AGGRESSION => SHORT_SQUEEZE"
            )

            // STATE 7: DISTRIBUTION
            (oiRegime == "NEW LONGS" && ofRegime == "SELL_ABSORPTION") ||
            (oiRegime == "NEW LONGS" && ofRegime == "BALANCED FLOW" && oiState.priceDeltaPercent < 0) -> Quadruple(
                "DISTRIBUTION",
                Color(0xFFF97316),
                "Longs entering but aggressive buying unable to advance price efficiently. Potential distribution.",
                "NEW_LONGS + SELL_ABSORPTION/STALL => DISTRIBUTION"
            )

            // STATE 8: ACCUMULATION
            (oiRegime == "NEW SHORTS" && ofRegime == "BUY_ABSORPTION") ||
            (oiRegime == "NEW SHORTS" && ofRegime == "BALANCED FLOW" && oiState.priceDeltaPercent > 0) -> Quadruple(
                "ACCUMULATION",
                Color(0xFF3B82F6),
                "Selling pressure absorbed without meaningful price decline. Potential accumulation.",
                "NEW_SHORTS + BUY_ABSORPTION/STALL => ACCUMULATION"
            )

            // STATE 9: NEUTRAL
            else -> Quadruple(
                "NEUTRAL",
                Color(0xFF9CA3AF),
                "No dominant market narrative detected.",
                "NO_RULE_MATCH => NEUTRAL"
            )
        }

        // --- CONSENSUS MEMORY LAYER UPDATE RULES ---
        val now = System.currentTimeMillis()
        val isNonNeutral = candidateConsensus != "NEUTRAL" && candidateConsensus != "BUILDING WINDOW"
        val activeConsensus = _uiState.value.flowConsensus.consensus

        // When consensus changes to a new non-neutral state, promote prior non-neutral state to Memory!
        if (isNonNeutral && candidateConsensus != activeConsensus) {
            if (activeConsensus != "NEUTRAL" && activeConsensus != "BUILDING WINDOW") {
                memoryLastConsensus = activeConsensus
                memoryLastNarrative = _uiState.value.flowConsensus.narrative
                memoryLastTimestampMs = now
                memoryLastBadgeColor = _uiState.value.flowConsensus.badgeColor

                val event = ConsensusHistoryEvent(
                    timestampFormatted = timeFormatter.format(Date(now)),
                    consensus = activeConsensus,
                    badgeColor = _uiState.value.flowConsensus.badgeColor
                )
                memoryHistoryList.addFirst(event)
                while (memoryHistoryList.size > 5) {
                    memoryHistoryList.removeLast()
                }
            }
        }

        val prevAgeStr = if (memoryLastTimestampMs != null) formatAge(now - memoryLastTimestampMs!!) else null

        // Confidence Calculation Engine: LOW, MEDIUM, HIGH
        var score = 0
        if (oiStrength == "HIGH") score += 2 else if (oiStrength == "MEDIUM") score += 1
        if (ofRegime == "BUY AGGRESSION" || ofRegime == "SELL AGGRESSION") score += 2
        if (cvdConfirm == "CVD CONFIRMED") score += 2 else if (cvdConfirm == "DIVERGENCE") score -= 1

        val (confText, confColor) = when {
            score >= 4 -> "HIGH" to Color(0xFF10B981)
            score >= 2 -> "MEDIUM" to Color(0xFFEAB308)
            else -> "LOW" to Color(0xFF9CA3AF)
        }

        val isDebugActive = _uiState.value.flowConsensus.debugState.isDebugEnabled

        val debugState = FlowConsensusDebugState(
            isDebugEnabled = isDebugActive,
            inputOiRegime = oiRegime,
            inputOrderFlowRegime = ofRegime,
            inputOiStrength = oiStrength,
            inputOrderFlowStrength = if (ofRegime.contains("AGGRESSION")) "HIGH" else "MEDIUM",
            inputCvdConfirmation = cvdConfirm,
            confidenceScore = score,
            ruleFired = ruleName
        )

        return FlowConsensusState(
            consensus = candidateConsensus,
            badgeColor = candidateBadgeColor,
            confidence = confText,
            confidenceColor = confColor,
            oiRegimeDisplay = oiRegime,
            orderFlowDisplay = ofRegime,
            narrative = narrativeText,
            previousConsensus = memoryLastConsensus,
            previousConsensusColor = memoryLastBadgeColor,
            previousNarrative = memoryLastNarrative,
            previousConsensusAgeFormatted = prevAgeStr,
            consensusHistory = memoryHistoryList.toList(),
            debugState = debugState
        )
    }

    // --- PHASE 4 & 4.5: ORDER FLOW ENGINE (LIVE WS TRADES & DIAGNOSTICS) ---
    private fun processLiveTradeSnapshot(trade: TradeSnapshot) {
        tradeSnapshotBuffer.addLast(trade)

        val now = System.currentTimeMillis()
        lastTradeTimeMs = now

        // Calculate sliding window trade rate (msgs/sec)
        tradeRateWindow.addLast(now)
        val rateCutoff = now - 1000L
        while (tradeRateWindow.isNotEmpty() && tradeRateWindow.first() < rateCutoff) {
            tradeRateWindow.removeFirst()
        }
        val msgsPerSec = tradeRateWindow.size.toDouble()

        val windowDurationMs = getTimeframeDurationMs(_uiState.value.interval)
        val cutoffTime = now - windowDurationMs

        while (tradeSnapshotBuffer.isNotEmpty() && tradeSnapshotBuffer.first().timestampMs < cutoffTime) {
            tradeSnapshotBuffer.removeFirst()
        }

        if (tradeSnapshotBuffer.isEmpty()) return

        var buyVol = 0.0
        var sellVol = 0.0

        for (t in tradeSnapshotBuffer) {
            if (t.isBuyerMaker) {
                // Buyer was maker => Seller was taker => Market Sell
                sellVol += t.quantity
            } else {
                // Seller was maker => Buyer was taker => Market Buy
                buyVol += t.quantity
            }
        }

        val totalVol = buyVol + sellVol
        val netDelta = buyVol - sellVol
        val deltaPct = if (totalVol > 0.0) (netDelta / totalVol) * 100.0 else 0.0

        val anchorPrice = tradeSnapshotBuffer.first().price
        val priceDeltaPct = if (anchorPrice > 0.0) ((trade.price - anchorPrice) / anchorPrice) * 100.0 else 0.0

        val isBuilding = tradeSnapshotBuffer.size < 5

        val (regimeStr, colorVal) = when {
            isBuilding || totalVol == 0.0 -> "BALANCED FLOW" to Color(0xFF9CA3AF)
            deltaPct >= 12.0 -> "BUY AGGRESSION" to Color(0xFF10B981)
            deltaPct <= -12.0 -> "SELL AGGRESSION" to Color(0xFFEF4444)
            else -> "BALANCED FLOW" to Color(0xFF9CA3AF)
        }

        // --- PHASE 4.5 DETAILED WS DIAGNOSTICS ENGINE ---
        val wsStage = wsManager.tradeStreamStage.value
        val socketUrl = wsManager.tradeSocketUrl.value
        val currentStream = wsManager.tradeStreamName.value
        val currentSymbol = wsManager.currSymbol
        val prevSymbol = wsManager.prevSymbol
        val lastSubTime = wsManager.lastSubTimeMs
        val lastError = wsManager.tradeLastError.value
        val msgCount = wsManager.tradeMsgCount
        val rawPreview = wsManager.rawLastMessage.value
        val parseFailures = wsManager.parseFailuresCount

        val latestSnapshotTimeFormatted = if (tradeSnapshotBuffer.isNotEmpty()) millisFormatter.format(Date(tradeSnapshotBuffer.last().timestampMs)) else "--:--:--.---"
        val latestSnapshotPrice = tradeSnapshotBuffer.lastOrNull()?.price ?: 0.0

        val warnings = mutableListOf<String>()
        if (msgCount == 0L) warnings.add("WARNING: No WS Messages")
        if (wsStage != WsConnectionStage.SUBSCRIBED && wsStage != WsConnectionStage.RECEIVING_MESSAGES) warnings.add("WARNING: Subscription Not Active")
        if (msgCount > 0L && tradeSnapshotBuffer.isEmpty()) warnings.add("WARNING: No Buffered Trades")
        if (parseFailures > 0L) warnings.add("WARNING: Parse Failure ($parseFailures failed)")
        if (currentSymbol.isBlank()) warnings.add("WARNING: Symbol Missing")
        if (lastTradeTimeMs > 0 && (now - lastTradeTimeMs > 10_000L)) warnings.add("WARNING: Trade Stream Stalled")

        val isDebugActive = _uiState.value.orderFlowRegime.debugState.isDebugEnabled

        val debugState = OrderFlowRegimeDebugState(
            isDebugEnabled = isDebugActive,
            connectionStage = wsStage,
            socketUrl = socketUrl,
            currentStream = currentStream,
            currentSymbol = currentSymbol,
            previousSymbol = prevSymbol,
            lastSubscriptionTimeFormatted = if (lastSubTime > 0) timeFormatter.format(Date(lastSubTime)) else "--:--:--",
            lastError = lastError,
            messagesReceived = msgCount,
            messagesPerSecond = msgsPerSec,
            lastMessageTimestampFormatted = millisFormatter.format(Date(trade.timestampMs)),
            msSinceLastMessage = if (lastTradeTimeMs > 0) now - lastTradeTimeMs else 0L,
            rawMessagePreview = rawPreview,
            reconnectCount = wsManager.reconnectCount,
            bufferedTradesCount = tradeSnapshotBuffer.size,
            latestSnapshotTimestampFormatted = latestSnapshotTimeFormatted,
            latestSnapshotPrice = latestSnapshotPrice,
            currentTimeframe = _uiState.value.interval,
            windowStatus = if (isBuilding) "BUILDING" else "READY",
            lastPrice = trade.price,
            lastQuantity = trade.quantity,
            lastIsBuyerMaker = trade.isBuyerMaker,
            buyVolume = buyVol,
            sellVolume = sellVol,
            netDelta = netDelta,
            deltaPercent = deltaPct,
            parseFailuresCount = parseFailures,
            warnings = warnings
        )

        val newOrderFlowState = OrderFlowRegimeState(
            regime = regimeStr,
            badgeColor = colorVal,
            buyVolume = buyVol,
            sellVolume = sellVol,
            netDelta = netDelta,
            deltaPercent = deltaPct,
            priceDeltaPercent = priceDeltaPct,
            totalVolume = totalVol,
            buildingWindow = isBuilding,
            debugState = debugState
        )

        val consensusState = evaluateFlowConsensus(_uiState.value.oiFlowRegime, newOrderFlowState)

        _uiState.update {
            it.copy(
                orderFlowRegime = newOrderFlowState,
                flowConsensus = consensusState
            )
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
        tradeSnapshotBuffer.clear()
        tradeRateWindow.clear()
        memoryLastConsensus = null
        memoryLastNarrative = null
        memoryLastTimestampMs = null
        memoryHistoryList.clear()
        lastValidRegime = null
        lastRegimeTransitionTimeMs = 0L
        val effective = detectEffectiveMode(_uiState.value.connectionMode)
        _uiState.update {
            it.copy(
                symbol = symbol,
                interval = interval,
                isLoading = true,
                liveCandle = null,
                initialDeltaSign = InitialDeltaSign.WAITING,
                orderBookImbalance = null,
                oiFlowRegime = OIFlowRegimeState(debugState = it.oiFlowRegime.debugState.copy(warnings = listOf("WARNING: Window Building"))),
                orderFlowRegime = OrderFlowRegimeState(),
                flowConsensus = FlowConsensusState(),
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

            // --- FLOW SNAPSHOT BUFFER & DIAGNOSTIC ENGINE ---
            val currentOi = oi.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0
            val currentPrice = _uiState.value.liveCandle?.close
                ?: klines.lastOrNull()?.close
                ?: 0.0
            val now = System.currentTimeMillis()
            lastOiPollTimeMs = now

            if (currentOi > 0.0 && currentPrice > 0.0) {
                snapshotBuffer.addLast(FlowSnapshot(now, currentPrice, currentOi))
            }

            // Prune snapshots older than 2 hours
            val maxHistoryCutoff = now - 7_200_000L
            while (snapshotBuffer.isNotEmpty() && snapshotBuffer.first().timestampMs < maxHistoryCutoff) {
                snapshotBuffer.removeFirst()
            }

            // Locate newest snapshot whose age is at least targetDurationMs
            val targetDurationMs = getTimeframeDurationMs(interval)
            val anchorSnapshot = snapshotBuffer.lastOrNull { snapshot ->
                (now - snapshot.timestampMs) >= targetDurationMs
            }

            val warningsList = mutableListOf<String>()
            if (snapshotBuffer.isEmpty()) warningsList.add("WARNING: Snapshot Buffer Empty")
            if (anchorSnapshot == null) warningsList.add("WARNING: Anchor Snapshot Missing")
            if (anchorSnapshot != null && snapshotBuffer.isNotEmpty() && anchorSnapshot.timestampMs == snapshotBuffer.last().timestampMs) {
                warningsList.add("WARNING: Anchor Equals Current Snapshot")
            }
            if (lastOiPollTimeMs > 0 && (now - lastOiPollTimeMs > 90_000L)) warningsList.add("WARNING: OI Poll Stalled")
            if (lastPriceTickTimeMs > 0 && (now - lastPriceTickTimeMs > 10_000L)) warningsList.add("WARNING: Price Feed Stalled")
            if (anchorSnapshot == null) warningsList.add("WARNING: Window Building")

            val isDebugActive = _uiState.value.oiFlowRegime.debugState.isDebugEnabled

            val oiFlowRegimeState = if (anchorSnapshot == null) {
                val debugState = OiFlowRegimeDebugState(
                    isDebugEnabled = isDebugActive,
                    snapshotCount = snapshotBuffer.size,
                    requiredWindowSec = targetDurationMs / 1000L,
                    oldestSnapshotAgeSec = if (snapshotBuffer.isNotEmpty()) (now - snapshotBuffer.first().timestampMs) / 1000L else 0L,
                    anchorSnapshotAgeSec = 0L,
                    latestSnapshotAgeSec = if (snapshotBuffer.isNotEmpty()) (now - snapshotBuffer.last().timestampMs) / 1000L else 0L,
                    bufferStatus = "BUILDING",
                    currentPrice = currentPrice,
                    anchorPrice = 0.0,
                    currentOi = currentOi,
                    anchorOi = 0.0,
                    priceDelta = 0.0,
                    priceDeltaPercent = 0.0,
                    oiDelta = 0.0,
                    oiDeltaPercent = 0.0,
                    priceThresholdPass = false,
                    oiThresholdPass = false,
                    overallThresholdPass = false,
                    regimeLogicCondition = "BUILDING",
                    oiScore = 0,
                    priceScore = 0,
                    totalScore = 0,
                    currentCvd = klines.sumOf { it.volumeDelta },
                    cvdDirection = "NONE",
                    transitionTimeSecAgo = null,
                    lastOiPollTimeFormatted = timeFormatter.format(Date(now)),
                    msSinceOiPoll = 0L,
                    lastPriceTickTimeFormatted = if (lastPriceTickTimeMs > 0) millisFormatter.format(Date(lastPriceTickTimeMs)) else "--:--:--.---",
                    msSincePriceTick = if (lastPriceTickTimeMs > 0) now - lastPriceTickTimeMs else 0L,
                    warnings = warningsList
                )
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
                    buildingWindow = true,
                    debugState = debugState
                )
            } else {
                val deltaPrice = currentPrice - anchorSnapshot.price
                val deltaPricePct = if (anchorSnapshot.price > 0.0) ((currentPrice - anchorSnapshot.price) / anchorSnapshot.price) * 100.0 else 0.0
                val deltaOI = currentOi - anchorSnapshot.openInterest
                val deltaOIPct = if (anchorSnapshot.openInterest > 0.0) ((currentOi - anchorSnapshot.openInterest) / anchorSnapshot.openInterest) * 100.0 else 0.0

                val pricePass = abs(deltaPricePct) >= 0.15
                val oiPass = abs(deltaOIPct) >= 0.25
                val passesSignificanceFilter = pricePass && oiPass

                val currentCvd = klines.sumOf { it.volumeDelta }
                val cvdDir = if (currentCvd > 0.0) "POSITIVE" else if (currentCvd < 0.0) "NEGATIVE" else "ZERO"

                if (!passesSignificanceFilter) {
                    val debugState = OiFlowRegimeDebugState(
                        isDebugEnabled = isDebugActive,
                        snapshotCount = snapshotBuffer.size,
                        requiredWindowSec = targetDurationMs / 1000L,
                        oldestSnapshotAgeSec = (now - snapshotBuffer.first().timestampMs) / 1000L,
                        anchorSnapshotAgeSec = (now - anchorSnapshot.timestampMs) / 1000L,
                        latestSnapshotAgeSec = (now - snapshotBuffer.last().timestampMs) / 1000L,
                        bufferStatus = "READY",
                        currentPrice = currentPrice,
                        anchorPrice = anchorSnapshot.price,
                        currentOi = currentOi,
                        anchorOi = anchorSnapshot.openInterest,
                        priceDelta = deltaPrice,
                        priceDeltaPercent = deltaPricePct,
                        oiDelta = deltaOI,
                        oiDeltaPercent = deltaOIPct,
                        priceThresholdPass = pricePass,
                        oiThresholdPass = oiPass,
                        overallThresholdPass = false,
                        regimeLogicCondition = "abs(deltaPricePct) < 0.15 || abs(deltaOIPct) < 0.25",
                        oiScore = 0,
                        priceScore = 0,
                        totalScore = 0,
                        currentCvd = currentCvd,
                        cvdDirection = cvdDir,
                        transitionTimeSecAgo = if (lastRegimeTransitionTimeMs > 0) (now - lastRegimeTransitionTimeMs) / 1000L else null,
                        lastOiPollTimeFormatted = timeFormatter.format(Date(now)),
                        msSinceOiPoll = 0L,
                        lastPriceTickTimeFormatted = if (lastPriceTickTimeMs > 0) millisFormatter.format(Date(lastPriceTickTimeMs)) else "--:--:--.---",
                        msSincePriceTick = if (lastPriceTickTimeMs > 0) now - lastPriceTickTimeMs else 0L,
                        warnings = warningsList
                    )
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
                        buildingWindow = false,
                        debugState = debugState
                    )
                } else {
                    val (regimeStr, mechanicsStr, colorVal, logicCond) = when {
                        deltaOI > 0.0 && deltaPrice > 0.0 -> Quadruple("NEW LONGS", "Fresh capital entering Longs (Buy-to-Open)", Color(0xFF10B981), "deltaOI > 0 && deltaPrice > 0")
                        deltaOI > 0.0 && deltaPrice < 0.0 -> Quadruple("NEW SHORTS", "Fresh capital entering Shorts (Sell-to-Open)", Color(0xFFEF4444), "deltaOI > 0 && deltaPrice < 0")
                        deltaOI < 0.0 && deltaPrice > 0.0 -> Quadruple("SHORT COVERING", "Shorts buying back positions (Buy-to-Close)", Color(0xFFEAB308), "deltaOI < 0 && deltaPrice > 0")
                        deltaOI < 0.0 && deltaPrice < 0.0 -> Quadruple("LONG LIQUIDATIONS", "Longs exiting positions (Sell-to-Close)", Color(0xFFF97316), "deltaOI < 0 && deltaPrice < 0")
                        else -> Quadruple("NEUTRAL", "Waiting for next OI delta", Color(0xFF9CA3AF), "deltaOI == 0")
                    }

                    // Strength Engine
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
                        lastRegimeTransitionTimeMs = now
                    }

                    val cvdConfirm = when {
                        regimeStr == "NEW LONGS" && currentCvd > 0.0 -> "CVD CONFIRMED"
                        regimeStr == "NEW SHORTS" && currentCvd < 0.0 -> "CVD CONFIRMED"
                        (regimeStr == "NEW LONGS" && currentCvd < 0.0) || (regimeStr == "NEW SHORTS" && currentCvd > 0.0) -> "DIVERGENCE"
                        else -> null
                    }

                    val debugState = OiFlowRegimeDebugState(
                        isDebugEnabled = isDebugActive,
                        snapshotCount = snapshotBuffer.size,
                        requiredWindowSec = targetDurationMs / 1000L,
                        oldestSnapshotAgeSec = (now - snapshotBuffer.first().timestampMs) / 1000L,
                        anchorSnapshotAgeSec = (now - anchorSnapshot.timestampMs) / 1000L,
                        latestSnapshotAgeSec = (now - snapshotBuffer.last().timestampMs) / 1000L,
                        bufferStatus = "READY",
                        currentPrice = currentPrice,
                        anchorPrice = anchorSnapshot.price,
                        currentOi = currentOi,
                        anchorOi = anchorSnapshot.openInterest,
                        priceDelta = deltaPrice,
                        priceDeltaPercent = deltaPricePct,
                        oiDelta = deltaOI,
                        oiDeltaPercent = deltaOIPct,
                        priceThresholdPass = pricePass,
                        oiThresholdPass = oiPass,
                        overallThresholdPass = true,
                        regimeLogicCondition = logicCond,
                        oiScore = oiScore,
                        priceScore = priceScore,
                        totalScore = totalStrengthScore,
                        currentCvd = currentCvd,
                        cvdDirection = cvdDir,
                        transitionTimeSecAgo = if (lastRegimeTransitionTimeMs > 0) (now - lastRegimeTransitionTimeMs) / 1000L else null,
                        lastOiPollTimeFormatted = timeFormatter.format(Date(now)),
                        msSinceOiPoll = 0L,
                        lastPriceTickTimeFormatted = if (lastPriceTickTimeMs > 0) millisFormatter.format(Date(lastPriceTickTimeMs)) else "--:--:--.---",
                        msSincePriceTick = if (lastPriceTickTimeMs > 0) now - lastPriceTickTimeMs else 0L,
                        warnings = warningsList
                    )

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
                        buildingWindow = false,
                        debugState = debugState
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

            val consensusState = evaluateFlowConsensus(oiFlowRegimeState, _uiState.value.orderFlowRegime)

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
                    flowConsensus = consensusState,
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

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)