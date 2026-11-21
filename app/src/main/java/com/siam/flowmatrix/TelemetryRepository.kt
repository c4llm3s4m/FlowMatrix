package com.siam.flowmatrix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Application-scoped Singleton Telemetry Repository.
 * Decouples WebSocket streaming, REST polling, and historical ArrayDeque snapshot buffers
 * from the Activity/ViewModel lifecycle so minimized states or screen lock events on HyperOS
 * never drop connections or wipe data.
 */
object TelemetryRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private val fetcher = BinanceDataFetcher()
    val wsManager = BinanceWebSocketManager()
    private var prefsManager: CardOrderPreferences? = null
    private var appContext: Context? = null

    private val snapshotBuffer = ArrayDeque<FlowSnapshot>()
    private val tradeSnapshotBuffer = ArrayDeque<TradeSnapshot>()
    private val tradeRateWindow = ArrayDeque<Long>()
    private val smartMoneySnapshotBuffer = ArrayDeque<SmartMoneySnapshot>()

    // Wyckoff Effort vs Result Rolling Window Volume Buffer (max size 20)
    private val volumeHistoryBuffer = ArrayDeque<Double>()

    // Order Flow V3.5: Independent Per-Timeframe Window-Commit Memory Buffers (max size 10)
    private val tfWindowBuffers = mapOf(
        "1m" to ArrayDeque<OrderFlowSnapshot>(),
        "5m" to ArrayDeque<OrderFlowSnapshot>(),
        "15m" to ArrayDeque<OrderFlowSnapshot>(),
        "1h" to ArrayDeque<OrderFlowSnapshot>()
    )

    private val dirEffBuffers = mapOf(
        "1m" to ArrayDeque<Double>(),
        "5m" to ArrayDeque<Double>(),
        "15m" to ArrayDeque<Double>(),
        "1h" to ArrayDeque<Double>()
    )

    private val tfLastCommitMs = mutableMapOf(
        "1m" to 0L,
        "5m" to 0L,
        "15m" to 0L,
        "1h" to 0L
    )

    private val lastNonBalancedRegimes = mutableMapOf<String, String>()
    private val transitionSignalMap = mutableMapOf<String, String>()
    private val transitionTextMap = mutableMapOf<String, String>()
    private val transitionHoldCountMap = mutableMapOf<String, Int>()

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

    // SMPE Transition State
    private var lastNonNeutralSmpeRegime: String? = null
    private var smpeTransitionTimestampMs: Long? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val millisFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _uiState = MutableStateFlow(DashboardState())
    val uiState: StateFlow<DashboardState> = _uiState.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val applicationContext = context.applicationContext
        appContext = applicationContext
        val prefs = CardOrderPreferences(applicationContext)
        prefsManager = prefs

        val savedMode = prefs.getConnectionMode()
        val savedAppMode = prefs.getAppMode()
        val effective = detectEffectiveMode(applicationContext, savedMode)

        _uiState.update {
            it.copy(
                cardOrder = prefs.getCardOrder(),
                hiddenCards = prefs.getHiddenCards(),
                connectionMode = savedMode,
                activeMode = effective,
                appMode = savedAppMode
            )
        }

        startPolling()
        listenToWebSocket()
        wsManager.connect(_uiState.value.symbol, _uiState.value.interval, effective)
    }

    private fun detectEffectiveMode(context: Context, mode: ConnectionMode): ConnectionMode {
        if (mode != ConnectionMode.AUTO) return mode
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
        prefsManager?.saveAppMode(mode)
        _uiState.update { it.copy(appMode = mode) }
    }

    fun setConnectionMode(mode: ConnectionMode) {
        prefsManager?.saveConnectionMode(mode)
        val ctx = appContext ?: return
        val effective = detectEffectiveMode(ctx, mode)
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

    fun toggleSmpeDebugMode() {
        _uiState.update {
            val currentDebug = it.smpeUiState.isDebugEnabled
            it.copy(smpeUiState = it.smpeUiState.copy(isDebugEnabled = !currentDebug))
        }
    }

    fun setCardVisible(card: TelemetryCardType, isVisible: Boolean) {
        val current = _uiState.value.hiddenCards.toMutableSet()
        if (isVisible) {
            current.remove(card)
        } else {
            current.add(card)
        }
        _uiState.update { it.copy(hiddenCards = current) }
        prefsManager?.saveHiddenCards(current)
    }

    fun showAllCards() {
        _uiState.update { it.copy(hiddenCards = emptySet()) }
        prefsManager?.saveHiddenCards(emptySet())
    }

    fun moveCardUp(card: TelemetryCardType) {
        val current = _uiState.value.cardOrder.toMutableList()
        val index = current.indexOf(card)
        if (index > 0) {
            val temp = current[index]
            current[index] = current[index - 1]
            current[index - 1] = temp
            _uiState.update { it.copy(cardOrder = current) }
            prefsManager?.saveCardOrder(current)
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
            prefsManager?.saveCardOrder(current)
        }
    }

    fun resetCardOrder() {
        val defaultOrder = TelemetryCardType.DEFAULT_ORDER
        _uiState.update { it.copy(cardOrder = defaultOrder, hiddenCards = emptySet()) }
        prefsManager?.saveCardOrder(defaultOrder)
        prefsManager?.saveHiddenCards(emptySet())
    }

    private fun listenToWebSocket() {
        scope.launch {
            wsManager.liveCandle.collect { liveTick ->
                if (liveTick != null) {
                    lastPriceTickTimeMs = System.currentTimeMillis()
                    _uiState.update { it.copy(liveCandle = liveTick, isLoading = false) }
                }
            }
        }

        scope.launch {
            wsManager.initialDeltaSign.collect { sign ->
                _uiState.update { it.copy(initialDeltaSign = sign) }
            }
        }

        scope.launch {
            wsManager.liveOrderBookImbalance.collect { imbalance ->
                if (imbalance != null) {
                    _uiState.update { it.copy(orderBookImbalance = imbalance) }
                }
            }
        }

        scope.launch {
            wsManager.liveTrade.collect { trade ->
                if (trade != null) {
                    processLiveTradeSnapshot(trade)
                }
            }
        }

        scope.launch {
            wsManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionStatus = state.toDisplayString()) }
            }
        }
    }

    // --- V3 FLOW CONSENSUS ENGINE: INTENT ENGINE, UNIFIED 0-100% CONFIDENCE & SMPE OVERRIDES ---
    private fun evaluateFlowConsensus(
        oiState: OIFlowRegimeState,
        ofState: OrderFlowRegimeState,
        smpeState: SmpeUiState
    ): FlowConsensusState {
        val oiRegime = oiState.regime.uppercase()
        val ofRegime = ofState.regime.uppercase()
        val oiStrength = oiState.strength.uppercase()
        val cvdConfirm = oiState.confirmation

        // --- INVENTORY VS FLOW CONFLICT DETECTOR (SYNCHRONIZED BOOLEANS) ---
        val inventoryIncreasing = smpeState.regime in listOf("ACCUMULATION", "SPONSORED_RALLY") || smpeState.longCapitalDeltaPct >= 0.50
        val inventoryDecreasing = smpeState.regime in listOf("DISTRIBUTION", "ABANDONMENT") || smpeState.longCapitalDeltaPct <= -0.50

        val flowBuying = ofRegime in listOf("EXPANSIVE BUYING", "THIN-ASK DRIFT", "BID ABSORPTION", "SHORT TRAP", "BUY AGGRESSION")
        val flowSelling = ofRegime in listOf("EXPANSIVE SELLING", "THIN-BID DROP", "ASK ABSORPTION", "LONG TRAP", "SELL AGGRESSION")

        val (invVsFlowLabel, invVsFlowBias) = when {
            flowSelling && inventoryIncreasing -> "PROFIT TAKING INSIDE BULLISH CAMPAIGN" to "BULLISH"
            flowBuying && inventoryDecreasing -> "BUYING INTO DISTRIBUTION" to "BEARISH"
            flowBuying && inventoryIncreasing -> "CONFIRMED EXPANSION" to "STRONG BULLISH"
            flowSelling && inventoryDecreasing -> "CONFIRMED DISTRIBUTION" to "STRONG BEARISH"
            else -> "ALIGNED / BALANCED" to "NEUTRAL"
        }

        // --- V3 INTENT ENGINE RULES ---
        val (intentKey, intentColor, intentDesc) = when {
            (ofRegime == "THIN-ASK DRIFT" || ofRegime == "EXPANSIVE BUYING") && oiRegime == "SHORT COVERING" -> Triple(
                "SHORT SQUEEZE",
                Color(0xFF22C55E), // Green
                "Forced short covering driving upside squeeze."
            )
            (ofRegime == "THIN-BID DROP" || ofRegime == "EXPANSIVE SELLING") && oiRegime == "LONG LIQUIDATIONS" -> Triple(
                "LONG LIQUIDATION",
                Color(0xFFEF4444), // Red
                "Forced long liquidations driving downside cascade."
            )
            (ofRegime == "ASK ABSORPTION" && oiRegime == "NEW LONGS") || ofRegime == "LONG TRAP" -> Triple(
                "BULL TRAP FORMING",
                Color(0xFFFF9100), // Amber
                "Fresh longs absorbed into overhead supply."
            )
            (ofRegime == "BID ABSORPTION" && oiRegime == "NEW SHORTS") || ofRegime == "SHORT TRAP" -> Triple(
                "BEAR TRAP FORMING",
                Color(0xFF00E5FF), // Cyan
                "Fresh shorts absorbed by passive buyers."
            )
            ofRegime == "EXPANSIVE BUYING" && oiRegime == "NEW LONGS" -> Triple(
                "INITIATIVE BUYING",
                Color(0xFF00C853), // Bright Green
                "New longs driving directional expansion."
            )
            ofRegime == "EXPANSIVE SELLING" && oiRegime == "NEW SHORTS" -> Triple(
                "INITIATIVE SHORTING",
                Color(0xFFFF5252), // Bright Red
                "New shorts driving directional expansion."
            )
            else -> Triple(
                "NEUTRAL AUCTION",
                Color(0xFF9CA3AF),
                "No dominant directional intent detected."
            )
        }

        // --- PRIORITY HIERARCHY & CONSENSUS OVERRIDES ---
        val isSmpeOverride = smpeState.regime == "SPONSORED_RALLY" &&
            (oiRegime == "NEW LONGS" || oiRegime == "SHORT COVERING") &&
            ((ofRegime == "EXPANSIVE SELLING" || ofRegime == "SELL AGGRESSION") && abs(ofState.deltaPercent) < 25.0)

        val isBullishAbsorptionTrap = (ofRegime == "BID ABSORPTION" || ofRegime == "SHORT TRAP") && oiRegime == "NEW SHORTS"
        val isBearishDistributionWall = (ofRegime == "ASK ABSORPTION" || ofRegime == "LONG TRAP") && (oiRegime == "NEW LONGS" || smpeState.regime == "DISTRIBUTION")

        val (candidateConsensus, candidateBadgeColor, narrativeText, ruleName) = when {
            // 1. HIGHEST-PRIORITY STRUCTURAL OVERRIDE: SMART MONEY DOMINANCE
            isSmpeOverride -> Quadruple(
                "SMART MONEY DOMINANCE",
                Color(0xFF10B981),
                "Large traders actively expanding inventory despite mild short-term sell pressure.",
                "SMPE_OVERRIDE: SPONSORED_RALLY + BULLISH_OI => SMART_MONEY_DOMINANCE"
            )

            // 2. EXPLICIT V3 INTENT OVERRIDES: SHORT SQUEEZE & LONG LIQUIDATION CASCADE
            intentKey == "SHORT SQUEEZE" -> Quadruple(
                "SHORT SQUEEZE",
                Color(0xFF22C55E),
                "Forced short covering into thin overhead asks.",
                "INTENT_RULE: SHORT_SQUEEZE"
            )

            intentKey == "LONG LIQUIDATION" -> Quadruple(
                "LONG LIQUIDATION CASCADE",
                Color(0xFFEF4444),
                "Forced long liquidations into pulled bid liquidity.",
                "INTENT_RULE: LONG_LIQUIDATION_CASCADE"
            )

            // 3. EXPLICIT WYCKOFF ABSORPTION RULES
            isBullishAbsorptionTrap -> Quadruple(
                "BULLISH ABSORPTION TRAP",
                Color(0xFF00C853),
                "Passive bids absorbing aggressive short entries at support.",
                "BID_ABSORPTION + NEW_SHORTS => BULLISH_ABSORPTION_TRAP"
            )

            isBearishDistributionWall -> Quadruple(
                "BEARISH DISTRIBUTION WALL",
                Color(0xFFFF9100),
                "Passive asks absorbing breakout buyers into overhead supply.",
                "ASK_ABSORPTION + NEW_LONGS/DISTRIBUTION => BEARISH_DISTRIBUTION_WALL"
            )

            // STATE 1: STRONG BULLISH CONTINUATION
            oiRegime == "NEW LONGS" && flowBuying -> Quadruple(
                "STRONG BULLISH CONTINUATION",
                Color(0xFF10B981),
                "Fresh longs entering while buyers control short-term order flow.",
                "NEW_LONGS + BUY_FLOW => STRONG_BULLISH_CONTINUATION"
            )

            // STATE 2: STRONG BEARISH CONTINUATION
            oiRegime == "NEW SHORTS" && flowSelling -> Quadruple(
                "STRONG BEARISH CONTINUATION",
                Color(0xFFEF4444),
                "Fresh shorts entering while sellers control short-term order flow.",
                "NEW_SHORTS + SELL_FLOW => STRONG_BEARISH_CONTINUATION"
            )

            // STATE 3: SHORT TRAP
            oiRegime == "NEW SHORTS" && flowBuying -> Quadruple(
                "SHORT TRAP",
                Color(0xFFF59E0B),
                "New shorts entering while buyers absorb and push price higher. Potential squeeze risk.",
                "NEW_SHORTS + BUY_FLOW => SHORT_TRAP"
            )

            // STATE 4: LONG TRAP
            oiRegime == "NEW LONGS" && flowSelling -> Quadruple(
                "LONG TRAP",
                Color(0xFFF97316),
                "New longs entering while sellers control aggressive flow. Potential downside trap.",
                "NEW_LONGS + SELL_FLOW => LONG_TRAP"
            )

            // STATE 5: CAPITULATION REVERSAL
            oiRegime == "LONG LIQUIDATIONS" && flowBuying -> Quadruple(
                "CAPITULATION REVERSAL",
                Color(0xFFEAB308),
                "Long liquidation event followed by aggressive buying pressure. Potential local bottom.",
                "LONG_LIQUIDATIONS + BUY_FLOW => CAPITULATION_REVERSAL"
            )

            // STATE 6: SHORT SQUEEZE
            oiRegime == "SHORT COVERING" && flowBuying -> Quadruple(
                "SHORT SQUEEZE",
                Color(0xFF22C55E),
                "Shorts exiting while buyers continue lifting offers. Potential squeeze continuation.",
                "SHORT_COVERING + BUY_FLOW => SHORT_SQUEEZE"
            )

            // STATE 7: DISTRIBUTION
            oiRegime == "NEW LONGS" && ofRegime == "BALANCED FLOW" && oiState.priceDeltaPercent < 0 -> Quadruple(
                "DISTRIBUTION",
                Color(0xFFF97316),
                "Longs entering but aggressive buying unable to advance price efficiently. Potential distribution.",
                "NEW_LONGS + STALL => DISTRIBUTION"
            )

            // STATE 8: ACCUMULATION
            oiRegime == "NEW SHORTS" && ofRegime == "BALANCED FLOW" && oiState.priceDeltaPercent > 0 -> Quadruple(
                "ACCUMULATION",
                Color(0xFF3B82F6),
                "Selling pressure absorbed without meaningful price decline. Potential accumulation.",
                "NEW_SHORTS + STALL => ACCUMULATION"
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

        // --- V3 UNIFIED CONFIDENCE ENGINE (0-100%, SMPE-PRIORITY WEIGHTING) ---
        // 1. SMPE Score (up to 30 pts)
        val smpeScore = when {
            smpeState.regime in listOf("SPONSORED_RALLY", "ACCUMULATION", "DISTRIBUTION", "ABANDONMENT") -> {
                val isSmpeBullish = smpeState.regime in listOf("SPONSORED_RALLY", "ACCUMULATION")
                if ((isSmpeBullish && flowBuying) || (!isSmpeBullish && flowSelling)) 30 else 15
            }
            smpeState.dominance in listOf("BULL DOMINANCE", "BEAR DOMINANCE", "STRONG BULL DOMINANCE", "SPONSORED RALLY", "EXTREME SPONSORSHIP") -> 20
            else -> 10
        }

        // 2. OI Regime Score (up to 25 pts)
        val oiScore = when {
            oiRegime in listOf("NEW LONGS", "NEW SHORTS", "SHORT COVERING", "LONG LIQUIDATIONS") -> {
                val isOiBullish = oiRegime in listOf("NEW LONGS", "SHORT COVERING")
                if ((isOiBullish && flowBuying) || (!isOiBullish && flowSelling)) 25 else 15
            }
            oiRegime == "NEUTRAL" -> 10
            else -> 5
        }

        // 3. Order Flow Quality Score (up to 25 pts)
        val ofScore = when {
            ofRegime in listOf("EXPANSIVE BUYING", "EXPANSIVE SELLING", "LONG TRAP", "SHORT TRAP") -> 25
            ofRegime in listOf("BID ABSORPTION", "ASK ABSORPTION") || ofState.defenseState != "NONE" -> 20
            ofRegime in listOf("THIN-ASK DRIFT", "THIN-BID DROP") -> 15
            else -> 10
        }

        // 4. Persistence Strength Score (up to 20 pts)
        val basePersistenceScore = when {
            ofState.persistenceCount >= 6 -> 20
            ofState.persistenceCount in 4..5 -> 15
            ofState.persistenceCount in 2..3 -> 10
            else -> 4
        }
        val persistencePenalty = if (ofState.persistenceState == "EXHAUSTING") 8 else 0
        val persistenceScore = kotlin.math.max(0, basePersistenceScore - persistencePenalty)

        val confidencePct = (smpeScore + oiScore + ofScore + persistenceScore).coerceIn(0, 100)

        val (confText, confColor) = when {
            confidencePct >= 75 -> "HIGH" to Color(0xFF10B981)
            confidencePct >= 45 -> "MEDIUM" to Color(0xFFEAB308)
            else -> "LOW" to Color(0xFF9CA3AF)
        }

        // ALIGNMENT RULE: Never output Consensus = NEUTRAL when intentState != "NEUTRAL AUCTION" and confidencePct >= 60
        val (finalConsensus, finalColor, finalNarrative, finalRule) = if (candidateConsensus == "NEUTRAL" && intentKey != "NEUTRAL AUCTION" && confidencePct >= 60) {
            Quadruple(
                intentKey,
                intentColor,
                intentDesc,
                "INTENT_OVERRIDE: $intentKey (Confidence $confidencePct%)"
            )
        } else {
            Quadruple(candidateConsensus, candidateBadgeColor, narrativeText, ruleName)
        }

        val isDebugActive = _uiState.value.flowConsensus.debugState.isDebugEnabled

        val debugState = FlowConsensusDebugState(
            isDebugEnabled = isDebugActive,
            inputOiRegime = oiRegime,
            inputOrderFlowRegime = ofRegime,
            inputOiStrength = oiStrength,
            inputOrderFlowStrength = if (flowBuying || flowSelling) "HIGH" else "MEDIUM",
            inputCvdConfirmation = cvdConfirm,
            inputSmpeRegime = smpeState.regime,
            inputSmpeDominance = smpeState.dominance,
            inventoryVsFlowLabel = invVsFlowLabel,
            inventoryVsFlowBias = invVsFlowBias,
            smpeScore = smpeScore,
            oiScore = oiScore,
            ofScore = ofScore,
            persistenceScore = persistenceScore,
            totalConfidencePct = confidencePct,
            intentRuleFired = intentKey,
            ruleFired = finalRule
        )

        return FlowConsensusState(
            consensus = finalConsensus,
            badgeColor = finalColor,
            confidencePct = confidencePct,
            confidence = confText,
            confidenceColor = confColor,
            oiRegimeDisplay = oiRegime,
            orderFlowDisplay = ofRegime,
            narrative = finalNarrative,
            inventoryVsFlowLabel = invVsFlowLabel,
            inventoryVsFlowBias = invVsFlowBias,
            intentState = intentKey,
            intentColor = intentColor,
            intentDescription = intentDesc,
            previousConsensus = memoryLastConsensus,
            previousConsensusColor = memoryLastBadgeColor,
            previousNarrative = memoryLastNarrative,
            previousConsensusAgeFormatted = prevAgeStr,
            consensusHistory = memoryHistoryList.toList(),
            debugState = debugState
        )
    }

    // --- PHASE 4, 4.5 & WYCKOFF V3.5 EFFORT VS RESULT ENGINE ---
    private fun processLiveTradeSnapshot(trade: TradeSnapshot) {
        tradeSnapshotBuffer.addLast(trade)

        val now = System.currentTimeMillis()
        lastTradeTimeMs = now

        tradeRateWindow.addLast(now)
        val rateCutoff = now - 1000L
        while (tradeRateWindow.isNotEmpty() && tradeRateWindow.first() < rateCutoff) {
            tradeRateWindow.removeFirst()
        }
        val msgsPerSec = tradeRateWindow.size.toDouble()

        val activeTf = _uiState.value.interval
        val thresholds = TimeframeThresholds.getThresholds(activeTf)
        val windowDurationMs = getTimeframeDurationMs(activeTf)
        val cutoffTime = now - windowDurationMs

        while (tradeSnapshotBuffer.isNotEmpty() && tradeSnapshotBuffer.first().timestampMs < cutoffTime) {
            tradeSnapshotBuffer.removeFirst()
        }

        if (tradeSnapshotBuffer.isEmpty()) return

        var buyVol = 0.0
        var sellVol = 0.0

        for (t in tradeSnapshotBuffer) {
            if (t.isBuyerMaker) {
                sellVol += t.quantity
            } else {
                buyVol += t.quantity
            }
        }

        val totalVol = buyVol + sellVol
        val netDelta = buyVol - sellVol
        val deltaPct = if (totalVol > 0.0) (netDelta / totalVol) * 100.0 else 0.0

        val anchorPrice = tradeSnapshotBuffer.first().price
        val priceDeltaPct = if (anchorPrice > 0.0) ((trade.price - anchorPrice) / anchorPrice) * 100.0 else 0.0

        val isBuilding = tradeSnapshotBuffer.size < 5

        // Wyckoff Effort vs Result Volume History
        if (!isBuilding && totalVol > 0.0) {
            volumeHistoryBuffer.addLast(totalVol)
            while (volumeHistoryBuffer.size > 20) {
                volumeHistoryBuffer.removeFirst()
            }
        }

        val avgWindowVol = if (volumeHistoryBuffer.isNotEmpty()) volumeHistoryBuffer.average() else totalVol
        val effortRatio = if (avgWindowVol > 0.0) totalVol / avgWindowVol else 1.0
        val priceEfficiency = if (abs(deltaPct) > 1.0) (priceDeltaPct / abs(deltaPct)) * 100.0 else 0.0

        // Timeframe-Normalized Flow Efficiency
        val normalizedPriceDelta = abs(priceDeltaPct) / thresholds.tfScaleFactor
        val flowEfficiency = normalizedPriceDelta / kotlin.math.max(abs(deltaPct), 1.0)

        // Directional (Signed) Efficiency
        val deltaSign = if (deltaPct > 0.0) 1.0 else if (deltaPct < 0.0) -1.0 else 0.0
        val directionalEfficiency = if (deltaSign != 0.0) {
            (priceDeltaPct * deltaSign) / (thresholds.tfScaleFactor * kotlin.math.max(abs(deltaPct), 1.0))
        } else 0.0

        val efficiencyBiasStr = when {
            directionalEfficiency > 0.02 -> "ALIGNED"
            directionalEfficiency >= 0.0 -> "ABSORBED"
            else -> "INVERTED"
        }

        val efficiencyState = when {
            flowEfficiency < 0.02 -> "ABSORPTION"
            flowEfficiency <= 0.06 -> "NORMAL_AUCTION"
            flowEfficiency <= 0.12 -> "INITIATIVE_MOVE"
            else -> "LIQUIDITY_VACUUM"
        }

        val effortLevel = when {
            effortRatio >= 1.35 || abs(deltaPct) >= thresholds.deltaTriggerPct -> "HIGH"
            effortRatio <= 0.75 && abs(deltaPct) < thresholds.deltaTriggerPct -> "LOW"
            else -> "MODERATE"
        }

        val resultLevel = when {
            abs(priceDeltaPct) <= thresholds.absorptionMaxPricePct -> "STALLED"
            abs(priceDeltaPct) >= thresholds.vacuumMinPricePct -> "EXPANSIVE"
            else -> "MODERATE"
        }

        // 9 Deterministic Wyckoff Regimes (Evaluating Trap Detection BEFORE Absorption)
        val (regimeStr, colorVal, mechanicsSummaryStr) = when {
            isBuilding || totalVol == 0.0 -> Triple(
                "BALANCED FLOW",
                Color(0xFF9CA3AF),
                "Building window telemetry..."
            )

            // 1. TRAP DETECTION ENGINE (HIGH PRIORITY)
            deltaPct >= thresholds.deltaTriggerPct && priceDeltaPct <= -thresholds.absorptionMaxPricePct -> Triple(
                "LONG TRAP",
                Color(0xFFFF5252), // Bright Red
                "Aggressive buyers unable to achieve upside and losing ground"
            )
            deltaPct <= -thresholds.deltaTriggerPct && priceDeltaPct >= thresholds.absorptionMaxPricePct -> Triple(
                "SHORT TRAP",
                Color(0xFF00C853), // Bright Green
                "Aggressive sellers unable to achieve downside and losing ground"
            )

            // 2. ABSORPTION ENGINE
            deltaPct >= thresholds.deltaTriggerPct && priceDeltaPct <= thresholds.absorptionMaxPricePct -> Triple(
                "ASK ABSORPTION",
                Color(0xFFFF9100), // Amber / Orange
                "Heavy taker buying absorbed by passive limit ask wall"
            )
            deltaPct <= -thresholds.deltaTriggerPct && priceDeltaPct >= -thresholds.absorptionMaxPricePct -> Triple(
                "BID ABSORPTION",
                Color(0xFF00E5FF), // Cyan / Teal
                "Heavy taker selling absorbed by passive limit bid wall"
            )

            // 3. EXPANSION ENGINE
            deltaPct >= thresholds.deltaTriggerPct && priceDeltaPct > thresholds.absorptionMaxPricePct -> Triple(
                "EXPANSIVE BUYING",
                Color(0xFF00C853), // Bright Green
                "High buy effort driving clean upward price expansion"
            )
            deltaPct <= -thresholds.deltaTriggerPct && priceDeltaPct < -thresholds.absorptionMaxPricePct -> Triple(
                "EXPANSIVE SELLING",
                Color(0xFFFF5252), // Bright Red
                "High sell effort driving clean downward price breakdown"
            )

            // 4. VACUUM DRIFT ENGINE
            abs(deltaPct) < thresholds.deltaTriggerPct && priceDeltaPct >= thresholds.vacuumMinPricePct -> Triple(
                "THIN-ASK DRIFT",
                Color(0xFF66BB6A), // Muted Green
                "Price rising on low taker effort (overhead liquidity vacuum)"
            )
            abs(deltaPct) < thresholds.deltaTriggerPct && priceDeltaPct <= -thresholds.vacuumMinPricePct -> Triple(
                "THIN-BID DROP",
                Color(0xFFEF5350), // Muted Red
                "Price dropping on low taker effort (pulled bid liquidity)"
            )

            else -> Triple(
                "BALANCED FLOW",
                Color(0xFF9CA3AF), // Neutral Gray
                "Order flow and price movement balanced within normal thresholds"
            )
        }

        // Window-Commit Cadence: Commit snapshot once per closed timeframe window
        val lastCommit = tfLastCommitMs[activeTf] ?: 0L
        val activeBuffer = tfWindowBuffers[activeTf] ?: ArrayDeque()
        val dirEffHist = dirEffBuffers[activeTf] ?: ArrayDeque()

        if (!isBuilding && now - lastCommit >= windowDurationMs) {
            activeBuffer.addLast(
                OrderFlowSnapshot(
                    timestampMs = now,
                    regime = regimeStr,
                    effortRatio = effortRatio,
                    normalizedEfficiency = flowEfficiency,
                    directionalEfficiency = directionalEfficiency,
                    deltaPct = deltaPct,
                    priceDeltaPct = priceDeltaPct
                )
            )
            while (activeBuffer.size > 10) {
                activeBuffer.removeFirst()
            }

            dirEffHist.addLast(directionalEfficiency)
            while (dirEffHist.size > 10) {
                dirEffHist.removeFirst()
            }

            // Named Regime Transition Engine
            val lastNonBal = lastNonBalancedRegimes[activeTf]
            if (regimeStr != "BALANCED FLOW" && lastNonBal != null && lastNonBal != regimeStr) {
                val (tSig, tTxt) = when {
                    lastNonBal == "ASK ABSORPTION" && regimeStr == "THIN-ASK DRIFT" -> "BREAKOUT_RELEASE" to "Passive overhead supply exhausted"
                    lastNonBal == "BID ABSORPTION" && regimeStr == "THIN-BID DROP" -> "SUPPORT_FAILURE" to "Passive bid support withdrawn"
                    lastNonBal == "SHORT TRAP" && regimeStr == "EXPANSIVE BUYING" -> "SQUEEZE_IGNITION" to "Trapped shorts fueling initiative buying"
                    lastNonBal == "LONG TRAP" && regimeStr == "EXPANSIVE SELLING" -> "CASCADE_IGNITION" to "Trapped longs fueling cascading supply"
                    else -> "NONE" to "NONE"
                }
                if (tSig != "NONE") {
                    transitionSignalMap[activeTf] = tSig
                    transitionTextMap[activeTf] = tTxt
                    transitionHoldCountMap[activeTf] = 2 // Hold for 2 closed windows
                }
            } else if ((transitionHoldCountMap[activeTf] ?: 0) > 0) {
                val remaining = (transitionHoldCountMap[activeTf] ?: 1) - 1
                transitionHoldCountMap[activeTf] = remaining
                if (remaining == 0) {
                    transitionSignalMap[activeTf] = "NONE"
                    transitionTextMap[activeTf] = "NONE"
                }
            }

            if (regimeStr != "BALANCED FLOW") {
                lastNonBalancedRegimes[activeTf] = regimeStr
            }

            tfLastCommitMs[activeTf] = now
        }

        // REGIME PERSISTENCE ENGINE
        val previousRegimeStr = if (activeBuffer.size >= 2) activeBuffer[activeBuffer.size - 2].regime else null

        var persistenceCount = 1
        for (i in activeBuffer.indices.reversed()) {
            if (i < activeBuffer.size - 1) {
                if (activeBuffer[i].regime == regimeStr) {
                    persistenceCount++
                } else break
            }
        }

        // REGIME MOMENTUM ENGINE (NOISE-GATED)
        val regimeMomentumStr = if (persistenceCount >= 3 && dirEffHist.size >= 3) {
            val effSlope = dirEffHist.last() - dirEffHist[dirEffHist.size - 3]
            val volSlope = if (activeBuffer.size >= 3) activeBuffer.last().effortRatio - activeBuffer[activeBuffer.size - 3].effortRatio else 0.0

            when {
                persistenceCount >= 6 && effSlope < -0.005 -> "EXHAUSTING ⚠"
                effSlope > 0.005 || (effSlope >= -0.005 && volSlope > 0.10) -> "STRENGTHENING ↑"
                effSlope < -0.005 && volSlope <= 0.0 -> "WEAKENING ↓"
                else -> "STABLE →"
            }
        } else {
            "STABLE →"
        }

        val isExhausting = regimeMomentumStr == "EXHAUSTING ⚠"

        val persistenceStateStr = when {
            persistenceCount == 1 -> "FRESH"
            persistenceCount in 2..3 -> "DEVELOPING"
            persistenceCount in 4..5 -> "ESTABLISHED"
            persistenceCount >= 6 && isExhausting -> "EXHAUSTING"
            else -> "DOMINANT"
        }

        // ABSORPTION ESCALATION (WITH FLICKER TOLERANCE - Last 4 closed windows)
        val last4 = activeBuffer.takeLast(4)
        val bidDefenseHits = last4.count { it.regime == "BID ABSORPTION" || it.regime == "SHORT TRAP" }
        val askDefenseHits = last4.count { it.regime == "ASK ABSORPTION" || it.regime == "LONG TRAP" }

        val defenseStateStr = when {
            bidDefenseHits >= 3 -> "ACTIVE_BID_DEFENSE"
            askDefenseHits >= 3 -> "ACTIVE_ASK_DEFENSE"
            else -> "NONE"
        }

        // Streaks & Regime Scores
        var absStreak = 0
        var expStreak = 0
        var vacStreak = 0

        for (snap in activeBuffer.asReversed()) {
            val r = snap.regime
            if (r == "ASK ABSORPTION" || r == "BID ABSORPTION" || r == "LONG TRAP" || r == "SHORT TRAP") absStreak++ else break
        }
        for (snap in activeBuffer.asReversed()) {
            val r = snap.regime
            if (r == "EXPANSIVE BUYING" || r == "EXPANSIVE SELLING") expStreak++ else break
        }
        for (snap in activeBuffer.asReversed()) {
            val r = snap.regime
            if (r == "THIN-ASK DRIFT" || r == "THIN-BID DROP") vacStreak++ else break
        }

        val bullScore = activeBuffer.count { it.regime in listOf("EXPANSIVE BUYING", "BID ABSORPTION", "SHORT TRAP", "THIN-ASK DRIFT") }
        val bearScore = activeBuffer.count { it.regime in listOf("EXPANSIVE SELLING", "ASK ABSORPTION", "LONG TRAP", "THIN-BID DROP") }

        val currentTransSignal = transitionSignalMap[activeTf] ?: "NONE"
        val currentTransText = transitionTextMap[activeTf] ?: "NONE"
        val currentLastNonBal = lastNonBalancedRegimes[activeTf]

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
            currentTimeframe = activeTf,
            windowStatus = if (isBuilding) "BUILDING" else "READY",
            lastPrice = trade.price,
            lastQuantity = trade.quantity,
            lastIsBuyerMaker = trade.isBuyerMaker,
            buyVolume = buyVol,
            sellVolume = sellVol,
            netDelta = netDelta,
            deltaPercent = deltaPct,
            effortRatio = effortRatio,
            effortLevel = effortLevel,
            resultLevel = resultLevel,
            priceEfficiency = priceEfficiency,
            flowEfficiency = flowEfficiency,
            efficiencyState = efficiencyState,
            directionalEfficiency = directionalEfficiency,
            efficiencyBias = efficiencyBiasStr,
            regimeMomentum = regimeMomentumStr,
            lastNonBalancedRegime = currentLastNonBal,
            transitionSignal = currentTransSignal,
            transitionText = currentTransText,
            activeThresholdsLabel = thresholds.activeThresholdsLabel,
            currentRegime = regimeStr,
            previousRegime = previousRegimeStr,
            persistenceCount = persistenceCount,
            persistenceState = persistenceStateStr,
            absorptionStreak = absStreak,
            expansionStreak = expStreak,
            vacuumStreak = vacStreak,
            defenseState = defenseStateStr,
            bullishRegimeScore = bullScore,
            bearishRegimeScore = bearScore,
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
            effortLevel = effortLevel,
            resultLevel = resultLevel,
            effortRatio = effortRatio,
            priceEfficiency = priceEfficiency,
            flowEfficiency = flowEfficiency,
            efficiencyState = efficiencyState,
            directionalEfficiency = directionalEfficiency,
            efficiencyBias = efficiencyBiasStr,
            regimeMomentum = regimeMomentumStr,
            transitionSignal = currentTransSignal,
            transitionText = currentTransText,
            mechanicsSummary = mechanicsSummaryStr,
            activeThresholdsLabel = thresholds.activeThresholdsLabel,
            persistenceCount = persistenceCount,
            persistenceState = persistenceStateStr,
            defenseState = defenseStateStr,
            buildingWindow = isBuilding,
            debugState = debugState
        )

        val consensusState = evaluateFlowConsensus(_uiState.value.oiFlowRegime, newOrderFlowState, _uiState.value.smpeUiState)

        _uiState.update {
            it.copy(
                orderFlowRegime = newOrderFlowState,
                flowConsensus = consensusState,
                isLoading = false
            )
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                fetchDashboardData(_uiState.value.symbol, _uiState.value.interval)
                delay(30000L)
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
        scope.launch {
            fetchDashboardData(_uiState.value.symbol, _uiState.value.interval)
        }
    }

    private fun updateCoreState(symbol: String, interval: String) {
        snapshotBuffer.clear()
        tradeSnapshotBuffer.clear()
        tradeRateWindow.clear()
        smartMoneySnapshotBuffer.clear()
        volumeHistoryBuffer.clear()

        // Clear all 4 timeframe window-commit memory buffers on symbol switch
        tfWindowBuffers.values.forEach { it.clear() }
        dirEffBuffers.values.forEach { it.clear() }
        tfLastCommitMs.keys.forEach { tfLastCommitMs[it] = 0L }
        lastNonBalancedRegimes.clear()
        transitionSignalMap.clear()
        transitionTextMap.clear()
        transitionHoldCountMap.clear()

        memoryLastConsensus = null
        memoryLastNarrative = null
        memoryLastTimestampMs = null
        memoryHistoryList.clear()
        lastValidRegime = null
        lastRegimeTransitionTimeMs = 0L
        lastNonNeutralSmpeRegime = null
        smpeTransitionTimestampMs = null
        val ctx = appContext ?: return
        val effective = detectEffectiveMode(ctx, _uiState.value.connectionMode)

        _uiState.update {
            val currentOiDebug = it.oiFlowRegime.debugState.isDebugEnabled
            val currentOfDebug = it.orderFlowRegime.debugState.isDebugEnabled
            val currentFcDebug = it.flowConsensus.debugState.isDebugEnabled
            val currentSmpeDebug = it.smpeUiState.isDebugEnabled

            it.copy(
                symbol = symbol,
                interval = interval,
                isLoading = true,
                liveCandle = null,
                initialDeltaSign = InitialDeltaSign.WAITING,
                orderBookImbalance = null,
                oiFlowRegime = OIFlowRegimeState(debugState = OiFlowRegimeDebugState(isDebugEnabled = currentOiDebug, warnings = listOf("WARNING: Window Building"))),
                orderFlowRegime = OrderFlowRegimeState(debugState = OrderFlowRegimeDebugState(isDebugEnabled = currentOfDebug)),
                flowConsensus = FlowConsensusState(debugState = FlowConsensusDebugState(isDebugEnabled = currentFcDebug)),
                smpeUiState = SmpeUiState(isDebugEnabled = currentSmpeDebug),
                activeMode = effective,
                error = null
            )
        }
        wsManager.connect(symbol, interval, effective)
        scope.launch { fetchDashboardData(symbol, interval) }
    }

    private suspend fun fetchDashboardData(symbol: String, interval: String) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        val macroPeriod = if (interval == "1m") "5m" else interval

        coroutineScope {
            val klinesDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getCandleOrderFlow(symbol, interval) } }.getOrNull() ?: emptyList() }
            val oiDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getOpenInterestHistory(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val takerLSDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getTakerLongShortRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val globalLSDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getGlobalLongShortRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val topAccountDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getTopTraderAccountRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val topPositionDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getTopTraderPositionRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val depthDeferred = async {
                runCatching {
                    withTimeoutOrNull(2000L) { fetcher.getOrderBookDepth(symbol, limit = 20) }
                        ?: withTimeoutOrNull(2000L) { fetcher.getSpotOrderBookDepth(symbol, limit = 20) }
                }.getOrNull()
            }

            val klines = klinesDeferred.await()
            _uiState.update { it.copy(isLoading = false) }

            val oi = oiDeferred.await()
            val takerLS = takerLSDeferred.await()
            val globalLS = globalLSDeferred.await()
            val topAccount = topAccountDeferred.await()
            val topPosition = topPositionDeferred.await()
            val depth = depthDeferred.await()

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

            val currentOi = oi.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0
            val currentPrice = _uiState.value.liveCandle?.close
                ?: klines.lastOrNull()?.close
                ?: 0.0
            val now = System.currentTimeMillis()
            lastOiPollTimeMs = now

            if (currentOi > 0.0 && currentPrice > 0.0) {
                snapshotBuffer.addLast(FlowSnapshot(now, currentPrice, currentOi))
            }

            val maxHistoryCutoff = now - 7_200_000L
            while (snapshotBuffer.isNotEmpty() && snapshotBuffer.first().timestampMs < maxHistoryCutoff) {
                snapshotBuffer.removeFirst()
            }

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

            // --- PHASE 6: SMART MONEY POSITION ENGINE (SMPE) DERIVATIONS & SNAPSHOT BUFFER ---
            val oiValueStr = oi.lastOrNull()?.sumOpenInterestValue?.toDoubleOrNull()
            val oiContracts = oi.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0
            val totalNotionalOI = oiValueStr ?: (oiContracts * currentPrice)

            val topPos = topPosition.lastOrNull()
            val longAccount = topPos?.longAccount?.toDoubleOrNull() ?: 0.5
            val shortAccount = topPos?.shortAccount?.toDoubleOrNull() ?: 0.5

            val longCapital = totalNotionalOI * longAccount
            val shortCapital = totalNotionalOI * shortAccount
            val capitalRatio = if (shortCapital > 0.0) longCapital / shortCapital else 0.0
            val netInventory = longCapital - shortCapital
            val longPositionPct = longAccount * 100.0
            val shortPositionPct = shortAccount * 100.0

            if (totalNotionalOI > 0.0 && currentPrice > 0.0) {
                smartMoneySnapshotBuffer.addLast(
                    SmartMoneySnapshot(
                        timestampMs = now,
                        price = currentPrice,
                        longCapital = longCapital,
                        shortCapital = shortCapital,
                        longPositionPct = longPositionPct,
                        shortPositionPct = shortPositionPct,
                        capitalRatio = capitalRatio
                    )
                )
                while (smartMoneySnapshotBuffer.size > 20) {
                    smartMoneySnapshotBuffer.removeFirst()
                }
            }

            val latestSmpeSnapshot = smartMoneySnapshotBuffer.lastOrNull()
            val smpeBaseline = if (latestSmpeSnapshot != null) {
                smartMoneySnapshotBuffer.lastOrNull { snapshot ->
                    snapshot.longPositionPct != latestSmpeSnapshot.longPositionPct ||
                    snapshot.shortPositionPct != latestSmpeSnapshot.shortPositionPct
                } ?: smartMoneySnapshotBuffer.firstOrNull() ?: latestSmpeSnapshot
            } else {
                SmartMoneySnapshot(now, currentPrice, longCapital, shortCapital, longPositionPct, shortPositionPct, capitalRatio)
            }

            val longCapitalDelta = longCapital - smpeBaseline.longCapital
            val shortCapitalDelta = shortCapital - smpeBaseline.shortCapital
            val longCapitalDeltaPct = if (smpeBaseline.longCapital > 0.0) ((longCapital - smpeBaseline.longCapital) / smpeBaseline.longCapital) * 100.0 else 0.0
            val shortCapitalDeltaPct = if (smpeBaseline.shortCapital > 0.0) ((shortCapital - smpeBaseline.shortCapital) / smpeBaseline.shortCapital) * 100.0 else 0.0
            val inventoryMomentum = longCapitalDeltaPct - shortCapitalDeltaPct
            val positioningSpread = longPositionPct - shortPositionPct
            val smpePriceDeltaPct = if (smpeBaseline.price > 0.0) ((currentPrice - smpeBaseline.price) / smpeBaseline.price) * 100.0 else 0.0

            val (dominanceText, dominanceColor) = when {
                capitalRatio < 0.70 -> "BEAR DOMINANCE" to Color(0xFFEF4444)
                capitalRatio <= 1.30 -> "BALANCED" to Color(0xFF9CA3AF)
                capitalRatio <= 3.00 -> "BULL DOMINANCE" to Color(0xFF10B981)
                capitalRatio <= 5.00 -> "STRONG BULL DOMINANCE" to Color(0xFF00C853)
                capitalRatio <= 10.00 -> "SPONSORED RALLY" to Color(0xFF22C55E)
                else -> "EXTREME SPONSORSHIP" to Color(0xFF00E676)
            }

            val satisfiesSmpeThreshold = smartMoneySnapshotBuffer.size >= 2 &&
                (abs(longCapitalDeltaPct) >= 0.20 || abs(shortCapitalDeltaPct) >= 0.20)

            val (smpeRegimeKey, smpeRegimeColor, smpeRuleEvaluated) = when {
                !satisfiesSmpeThreshold -> Triple(
                    "NEUTRAL",
                    Color(0xFF9CA3AF),
                    "THRESHOLD_NOT_MET (Requires >=0.20% capital delta)"
                )
                longCapitalDeltaPct >= 0.20 && shortCapitalDeltaPct <= -0.10 && smpePriceDeltaPct > 0.10 -> Triple(
                    "SPONSORED_RALLY",
                    Color(0xFF10B981),
                    "longCapitalDeltaPct >= 0.20 && shortCapitalDeltaPct <= -0.10 && priceDeltaPct > 0.10 => SPONSORED_RALLY"
                )
                longCapitalDeltaPct >= 0.20 && smpePriceDeltaPct in -0.25..0.75 -> Triple(
                    "ACCUMULATION",
                    Color(0xFF3B82F6),
                    "longCapitalDeltaPct >= 0.20 && priceDeltaPct in -0.25..0.75 => ACCUMULATION"
                )
                longCapitalDeltaPct <= -0.60 && (shortCapitalDeltaPct >= 0.20 || smpePriceDeltaPct < -0.20) -> Triple(
                    "ABANDONMENT",
                    Color(0xFFEF4444),
                    "longCapitalDeltaPct <= -0.60 && (shortCapitalDeltaPct >= 0.20 || smpePriceDeltaPct < -0.20) => ABANDONMENT"
                )
                longCapitalDeltaPct <= -0.20 && smpePriceDeltaPct in -0.25..0.50 -> Triple(
                    "DISTRIBUTION",
                    Color(0xFFF97316),
                    "longCapitalDeltaPct <= -0.20 && priceDeltaPct in -0.25..0.50 => DISTRIBUTION"
                )
                else -> Triple(
                    "NEUTRAL",
                    Color(0xFF9CA3AF),
                    "NO_SMPE_RULE_MATCH => NEUTRAL"
                )
            }

            if (smpeRegimeKey != "NEUTRAL" && smpeRegimeKey != lastNonNeutralSmpeRegime) {
                lastNonNeutralSmpeRegime = smpeRegimeKey
                smpeTransitionTimestampMs = now
            }

            val smpeTransitionLabel = when (lastNonNeutralSmpeRegime) {
                "ACCUMULATION" -> "WHALE ACCUMULATION"
                "SPONSORED_RALLY" -> "WHALE EXPANSION"
                "DISTRIBUTION" -> "WHALE DISTRIBUTION"
                "ABANDONMENT" -> "WHALE EXITING"
                else -> null
            }

            val smpeTransitionAgeStr = if (smpeTransitionTimestampMs != null) {
                formatAge(now - smpeTransitionTimestampMs!!)
            } else null

            val isSmpeDebugActive = _uiState.value.smpeUiState.isDebugEnabled

            val smpeUiState = SmpeUiState(
                isDebugEnabled = isSmpeDebugActive,
                currentPrice = currentPrice,
                longCapital = longCapital,
                shortCapital = shortCapital,
                netInventory = netInventory,
                longPositionPct = longPositionPct,
                shortPositionPct = shortPositionPct,
                capitalRatio = capitalRatio,
                longCapitalDelta = longCapitalDelta,
                shortCapitalDelta = shortCapitalDelta,
                longCapitalDeltaPct = longCapitalDeltaPct,
                shortCapitalDeltaPct = shortCapitalDeltaPct,
                inventoryMomentum = inventoryMomentum,
                positioningSpread = positioningSpread,
                dominance = dominanceText,
                dominanceColor = dominanceColor,
                regime = smpeRegimeKey,
                regimeColor = smpeRegimeColor,
                transitionLabel = smpeTransitionLabel,
                transitionAgeFormatted = smpeTransitionAgeStr,
                snapshotCount = smartMoneySnapshotBuffer.size,
                lastPollTimestampMs = now,
                lastHttpStatus = 200,
                lastErrorMessage = null,
                ruleEvaluated = smpeRuleEvaluated
            )

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

            val finalKlines = klines.ifEmpty {
                runCatching { fetcher.getSpotCandleOrderFlow(symbol, interval) }.getOrDefault(emptyList())
            }

            val errorMessage = if (finalKlines.isEmpty() && oi.isEmpty() && takerLS.isEmpty() && globalLS.isEmpty()) {
                "Unable to reach Binance REST API. Reconnecting WebSocket..."
            } else null

            val consensusState = evaluateFlowConsensus(oiFlowRegimeState, _uiState.value.orderFlowRegime, smpeUiState)

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
                    smpeUiState = smpeUiState,
                    candles = finalKlines,
                    error = errorMessage
                )
            }
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)