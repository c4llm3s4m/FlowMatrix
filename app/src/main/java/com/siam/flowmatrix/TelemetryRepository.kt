package com.siam.flowmatrix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * SymbolSession holds isolated in-memory telemetry state, buffers, and polling jobs
 * for a single watchlisted contract. This enables zero-reset symbol and timeframe switching
 * with continuous background ingestion across up to 5 concurrent watchlisted symbols.
 */
class SymbolSession(
    val symbol: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = createdAtMs + 24 * 3600 * 1000L
) {
    val snapshotBuffer = ArrayDeque<FlowSnapshot>()
    val tradeSnapshotBuffer = ArrayDeque<TradeSnapshot>()
    val tradeRateWindow = ArrayDeque<Long>()
    val smartMoneySnapshotBuffer = ArrayDeque<SmartMoneySnapshot>()
    val volumeHistoryBuffer = ArrayDeque<Double>()

    val tfWindowBuffers = mapOf(
        "1m" to ArrayDeque<OrderFlowSnapshot>(),
        "5m" to ArrayDeque<OrderFlowSnapshot>(),
        "15m" to ArrayDeque<OrderFlowSnapshot>(),
        "1h" to ArrayDeque<OrderFlowSnapshot>()
    )

    val dirEffBuffers = mapOf(
        "1m" to ArrayDeque<Double>(),
        "5m" to ArrayDeque<Double>(),
        "15m" to ArrayDeque<Double>(),
        "1h" to ArrayDeque<Double>()
    )

    val tfLastCommitMs = mutableMapOf(
        "1m" to 0L,
        "5m" to 0L,
        "15m" to 0L,
        "1h" to 0L
    )

    val lastNonBalancedRegimes = mutableMapOf<String, String>()
    val transitionSignalMap = mutableMapOf<String, String>()
    val transitionTextMap = mutableMapOf<String, String>()
    val transitionHoldCountMap = mutableMapOf<String, Int>()

    var memoryLastConsensus: String? = null
    var memoryLastNarrative: String? = null
    var memoryLastTimestampMs: Long? = null
    var memoryLastBadgeColor: Color = Color(0xFF9CA3AF)
    val memoryHistoryList = ArrayDeque<ConsensusHistoryEvent>()

    var lastValidRegime: String? = null
    var lastOiPollTimeMs: Long = 0L
    var lastPriceTickTimeMs: Long = 0L
    var lastTradeTimeMs: Long = 0L
    var lastRegimeTransitionTimeMs: Long = 0L

    var lastNonNeutralSmpeRegime: String? = null
    var smpeTransitionTimestampMs: Long? = null

    @Volatile var livePrice: Double = 0.0
    @Volatile var premiumIndex: PremiumIndexResponse? = null
    @Volatile var klines: List<CandleOrderFlow> = emptyList()
    @Volatile var openInterest: List<OpenInterestHistResponse> = emptyList()
    @Volatile var takerLongShort: List<TakerLongShortRatioResponse> = emptyList()
    @Volatile var globalLongShort: List<GlobalLongShortRatioResponse> = emptyList()
    @Volatile var topTraderAccount: List<TopTraderLongShortRatioResponse> = emptyList()
    @Volatile var topTraderPosition: List<TopTraderLongShortRatioResponse> = emptyList()
    @Volatile var depth: OrderBookDepthResponse? = null
    @Volatile var liveCandle: CandleOrderFlow? = null
    @Volatile var liveOrderBookImbalance: OrderBookImbalance? = null
    @Volatile var liveTrade: TradeSnapshot? = null
    @Volatile var initialDeltaSign: InitialDeltaSign = InitialDeltaSign.WAITING

    var restPollingJob: Job? = null
}

/**
 * Application-scoped Singleton Telemetry Repository.
 * Manages the 5-symbol 24-hour self-destructing Watchlist, zero-reset symbol & timeframe switching,
 * combined WebSocket stream routing, and staggered REST polling.
 */
object TelemetryRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reaperJob: Job? = null

    private val fetcher = BinanceDataFetcher()
    val wsManager = BinanceWebSocketManager()
    private var prefsManager: CardOrderPreferences? = null
    private var appContext: Context? = null

    private val activeSessions = ConcurrentHashMap<String, SymbolSession>()
    private val activeWatchlist = CopyOnWriteArrayList<WatchlistItem>()

    private val _activeSymbol = MutableStateFlow("BTCUSDT")
    private val _activeTimeframe = MutableStateFlow("5m")

    @Volatile
    private var lastEmissionMs = 0L
    private var pendingEmissionJob: Job? = null

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
        val effective = detectEffectiveMode(applicationContext, savedMode)

        // Load Watchlist from Local Preferences or Initialize Default BTCUSDT (24h TTL)
        val loadedWatchlist = prefs.getWatchlist().filter { !it.isExpired }
        val finalWatchlist = if (loadedWatchlist.isNotEmpty()) {
            loadedWatchlist.take(5)
        } else {
            val now = System.currentTimeMillis()
            listOf(WatchlistItem("BTCUSDT", createdAtMs = now, expiresAtMs = now + 24 * 3600 * 1000L))
        }

        activeWatchlist.clear()
        activeWatchlist.addAll(finalWatchlist)
        prefs.saveWatchlist(activeWatchlist)

        val initialSymbol = activeWatchlist.firstOrNull()?.symbol ?: "BTCUSDT"
        _activeSymbol.value = initialSymbol

        // Initialize Symbol Sessions
        activeWatchlist.forEach { item ->
            val session = SymbolSession(item.symbol, item.createdAtMs, item.expiresAtMs)
            activeSessions[item.symbol] = session
        }

        _uiState.update {
            it.copy(
                symbol = initialSymbol,
                interval = _activeTimeframe.value,
                cardOrder = prefs.getCardOrder(),
                hiddenCards = prefs.getHiddenCards(),
                connectionMode = savedMode,
                activeMode = effective,
                watchlist = activeWatchlist.toList()
            )
        }

        listenToWebSocketStreams()
        wsManager.syncSubscribedSymbols(activeWatchlist.map { it.symbol }.toSet(), _activeTimeframe.value, effective)

        startStaggeredRestPolling()
        startExpiryReaper()
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

    fun setConnectionMode(mode: ConnectionMode) {
        prefsManager?.saveConnectionMode(mode)
        val ctx = appContext ?: return
        val effective = detectEffectiveMode(ctx, mode)
        _uiState.update { it.copy(connectionMode = mode, activeMode = effective) }
        wsManager.syncSubscribedSymbols(activeWatchlist.map { it.symbol }.toSet(), _activeTimeframe.value, effective)
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

    // --- TELEMETRY EXPORT HELPERS ---
    fun buildSessionSnapshotState(session: SymbolSession, interval: String): DashboardState {
        val activeCandle = session.liveCandle ?: session.klines.lastOrNull()
        val currentPrice = if (session.livePrice > 0.0) session.livePrice else activeCandle?.close ?: 0.0

        val oiState = computeOiFlowRegimeState(session, interval)
        val ofState = computeOrderFlowRegimeState(session, interval)
        val smpeState = computeSmpeState(session)
        val consensusState = evaluateFlowConsensus(oiState, ofState, smpeState)

        return DashboardState(
            symbol = session.symbol,
            interval = interval,
            isLoading = session.klines.isEmpty() && session.openInterest.isEmpty(),
            livePrice = currentPrice,
            liveCandle = activeCandle,
            initialDeltaSign = session.initialDeltaSign,
            premiumIndex = session.premiumIndex,
            openInterest = session.openInterest,
            takerLongShort = session.takerLongShort,
            globalLongShort = session.globalLongShort,
            topTraderAccount = session.topTraderAccount,
            topTraderPosition = session.topTraderPosition,
            positioningMatrix = computePositioningMatrix(session),
            orderBookImbalance = session.liveOrderBookImbalance ?: computeFallbackImbalance(session),
            oiFlowRegime = oiState,
            orderFlowRegime = ofState,
            flowConsensus = consensusState,
            smpeUiState = smpeState,
            candles = session.klines,
            watchlist = activeWatchlist.toList(),
            activeMode = _uiState.value.activeMode
        )
    }

    fun buildActiveSymbolExport(format: ExportFormat): Pair<String, String> {
        val currSymbol = _activeSymbol.value
        val currInterval = _activeTimeframe.value
        val session = activeSessions[currSymbol] ?: SymbolSession(currSymbol)
        val state = buildSessionSnapshotState(session, currInterval)
        val watchItem = activeWatchlist.firstOrNull { it.symbol == currSymbol }
        val ttl = watchItem?.formattedTtl ?: "24h 00m"

        val fileName = TelemetryExporter.generateFileName(currSymbol, currInterval, format)
        val content = if (format == ExportFormat.TXT_REPORT) {
            TelemetryExporter.generateSingleSymbolReport(session, state, currInterval, ttl)
        } else {
            TelemetryExporter.generateTabularCsvExport(session, state, currInterval)
        }
        return fileName to content
    }

    fun buildFullWatchlistExport(): Pair<String, String> {
        val currInterval = _activeTimeframe.value
        val fileName = TelemetryExporter.generateFileName("ALL", currInterval, ExportFormat.TXT_REPORT, isFullWatchlist = true)

        val sessionStates = activeSessions.mapValues { (_, session) ->
            buildSessionSnapshotState(session, currInterval)
        }

        val content = TelemetryExporter.generateFullWatchlistReport(activeSessions, activeWatchlist, sessionStates, currInterval)
        return fileName to content
    }

    // --- ZERO-RESET SYMBOL & TIMEFRAME SWITCHING ---
    fun setSymbol(newSymbol: String) {
        val cleanSymbol = newSymbol.trim().uppercase()
        if (cleanSymbol.isBlank()) return

        val formattedSymbol = if (!cleanSymbol.endsWith("USDT") && !cleanSymbol.endsWith("BUSD")) {
            "${cleanSymbol}USDT"
        } else cleanSymbol

        if (formattedSymbol == _activeSymbol.value) return

        if (activeWatchlist.none { it.symbol == formattedSymbol }) {
            addSymbolToWatchlist(formattedSymbol)
        } else {
            _activeSymbol.value = formattedSymbol
            wsManager.setViewSymbol(formattedSymbol)
            emitActiveState()
        }
    }

    fun setTimeframe(newInterval: String) {
        if (newInterval == _activeTimeframe.value) return
        _activeTimeframe.value = newInterval

        val ctx = appContext ?: return
        val effective = detectEffectiveMode(ctx, _uiState.value.connectionMode)
        wsManager.syncSubscribedSymbols(activeWatchlist.map { it.symbol }.toSet(), newInterval, effective)

        val currentSession = activeSessions[_activeSymbol.value]
        if (currentSession != null) {
            scope.launch {
                fetchSymbolDashboardData(currentSession)
            }
        }

        emitActiveState()
    }

    fun clearWatchlistError() {
        _uiState.update { it.copy(watchlistError = null) }
    }

    fun addSymbolToWatchlist(symbolInput: String) {
        val clean = symbolInput.trim().uppercase()
        if (clean.isBlank()) return

        val formattedSymbol = if (!clean.endsWith("USDT") && !clean.endsWith("BUSD")) {
            "${clean}USDT"
        } else clean

        if (activeWatchlist.any { it.symbol == formattedSymbol }) {
            _activeSymbol.value = formattedSymbol
            wsManager.setViewSymbol(formattedSymbol)
            emitActiveState()
            return
        }

        // HARD CAP GUARD (MAX 5 SYMBOLS)
        if (activeWatchlist.size >= 5) {
            _uiState.update {
                it.copy(watchlistError = "Watchlist hard cap reached (5/5 symbols). Remove an existing symbol to add another.")
            }
            return
        }

        val now = System.currentTimeMillis()
        val item = WatchlistItem(formattedSymbol, createdAtMs = now, expiresAtMs = now + 24 * 3600 * 1000L)
        activeWatchlist.add(item)
        prefsManager?.saveWatchlist(activeWatchlist)

        val session = SymbolSession(formattedSymbol, item.createdAtMs, item.expiresAtMs)
        activeSessions[formattedSymbol] = session

        _activeSymbol.value = formattedSymbol
        wsManager.setViewSymbol(formattedSymbol)

        val ctx = appContext ?: return
        val effective = detectEffectiveMode(ctx, _uiState.value.connectionMode)
        wsManager.syncSubscribedSymbols(activeWatchlist.map { it.symbol }.toSet(), _activeTimeframe.value, effective)

        val index = activeWatchlist.indexOf(item)
        startSymbolRestPolling(session, index * 800L)

        emitActiveState()
    }

    fun removeSymbolFromWatchlist(symbolInput: String) {
        val clean = symbolInput.trim().uppercase()
        val formattedSymbol = if (!clean.endsWith("USDT") && !clean.endsWith("BUSD")) {
            "${clean}USDT"
        } else clean

        val item = activeWatchlist.firstOrNull { it.symbol == formattedSymbol } ?: return
        activeWatchlist.remove(item)
        prefsManager?.saveWatchlist(activeWatchlist)

        val session = activeSessions.remove(formattedSymbol)
        session?.restPollingJob?.cancel()

        if (activeWatchlist.isEmpty()) {
            val now = System.currentTimeMillis()
            val defaultItem = WatchlistItem("BTCUSDT", createdAtMs = now, expiresAtMs = now + 24 * 3600 * 1000L)
            activeWatchlist.add(defaultItem)
            prefsManager?.saveWatchlist(activeWatchlist)
            val defaultSession = SymbolSession("BTCUSDT", defaultItem.createdAtMs, defaultItem.expiresAtMs)
            activeSessions["BTCUSDT"] = defaultSession
            _activeSymbol.value = "BTCUSDT"
            startSymbolRestPolling(defaultSession, 0L)
        } else if (_activeSymbol.value == formattedSymbol) {
            _activeSymbol.value = activeWatchlist.first().symbol
        }

        wsManager.setViewSymbol(_activeSymbol.value)
        val ctx = appContext ?: return
        val effective = detectEffectiveMode(ctx, _uiState.value.connectionMode)
        wsManager.syncSubscribedSymbols(activeWatchlist.map { it.symbol }.toSet(), _activeTimeframe.value, effective)

        emitActiveState()
    }

    // --- 24-HOUR SELF-DESTRUCT EXPIRY REAPER ---
    private fun startExpiryReaper() {
        reaperJob?.cancel()
        reaperJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                val expiredSymbols = activeWatchlist.filter { it.isExpired }.map { it.symbol }
                if (expiredSymbols.isNotEmpty()) {
                    expiredSymbols.forEach { sym ->
                        removeSymbolFromWatchlist(sym)
                    }
                } else {
                    _uiState.update { it.copy(watchlist = activeWatchlist.toList()) }
                }
            }
        }
    }

    // Coalesced / Throttled UI State Emission (Max once per 150ms)
    private fun scheduleThrottledEmission() {
        val now = System.currentTimeMillis()
        if (now - lastEmissionMs >= 150L) {
            lastEmissionMs = now
            emitActiveState()
        } else if (pendingEmissionJob == null || pendingEmissionJob?.isCompleted == true) {
            pendingEmissionJob = scope.launch {
                delay(150L)
                lastEmissionMs = System.currentTimeMillis()
                emitActiveState()
            }
        }
    }

    private fun listenToWebSocketStreams() {
        scope.launch {
            wsManager.symbolKlineTicks.collect { tick ->
                val session = activeSessions[tick.symbol] ?: return@collect
                session.liveCandle = tick.candle
                session.lastPriceTickTimeMs = System.currentTimeMillis()
                if (tick.initialDelta != InitialDeltaSign.WAITING) {
                    session.initialDeltaSign = tick.initialDelta
                }
                if (tick.symbol == _activeSymbol.value) {
                    scheduleThrottledEmission()
                }
            }
        }

        scope.launch {
            wsManager.symbolDepthTicks.collect { tick ->
                val session = activeSessions[tick.symbol] ?: return@collect
                session.liveOrderBookImbalance = tick.imbalance
                if (tick.symbol == _activeSymbol.value) {
                    scheduleThrottledEmission()
                }
            }
        }

        scope.launch {
            wsManager.symbolTradeTicks.collect { tick ->
                val session = activeSessions[tick.symbol] ?: return@collect
                session.livePrice = tick.trade.price
                session.lastPriceTickTimeMs = System.currentTimeMillis()

                val currentCandle = session.liveCandle
                if (currentCandle != null) {
                    session.liveCandle = currentCandle.copy(
                        close = tick.trade.price,
                        high = maxOf(currentCandle.high, tick.trade.price),
                        low = minOf(currentCandle.low, tick.trade.price)
                    )
                }

                processSessionLiveTradeSnapshot(session, tick.trade)

                if (tick.symbol == _activeSymbol.value) {
                    _uiState.update { it.copy(livePrice = tick.trade.price, liveCandle = session.liveCandle) }
                    scheduleThrottledEmission()
                }
            }
        }

        scope.launch {
            wsManager.connectionState.collect { state ->
                _uiState.update { it.copy(connectionStatus = state.toDisplayString()) }
            }
        }
    }

    private fun startStaggeredRestPolling() {
        activeWatchlist.forEachIndexed { index, item ->
            val session = activeSessions[item.symbol] ?: return@forEachIndexed
            startSymbolRestPolling(session, offsetMs = index * 800L)
        }
    }

    private fun startSymbolRestPolling(session: SymbolSession, offsetMs: Long) {
        session.restPollingJob?.cancel()
        session.restPollingJob = scope.launch {
            delay(offsetMs)
            while (isActive) {
                fetchSymbolDashboardData(session)
                delay(30_000L)
            }
        }
    }

    fun refresh() {
        Log.d("FM_TRACE", "[UI_ACTION] RETRY_TAPPED")
        wsManager.reconnect()
        activeWatchlist.forEachIndexed { index, item ->
            val session = activeSessions[item.symbol] ?: return@forEachIndexed
            scope.launch {
                delay(index * 400L)
                fetchSymbolDashboardData(session)
            }
        }
    }

    @Synchronized
    private fun emitActiveState() {
        val currSymbol = _activeSymbol.value
        val currInterval = _activeTimeframe.value
        val session = activeSessions[currSymbol] ?: return

        val activeCandle = session.liveCandle ?: session.klines.lastOrNull()
        val currentPrice = if (session.livePrice > 0.0) session.livePrice else activeCandle?.close ?: 0.0

        val oiState = computeOiFlowRegimeState(session, currInterval)
        val ofState = computeOrderFlowRegimeState(session, currInterval)
        val smpeState = computeSmpeState(session)
        val consensusState = evaluateFlowConsensus(oiState, ofState, smpeState)

        Log.d("FM_TRACE", "[UI_STATE] sym=$currSymbol isLoading=${session.klines.isEmpty() && session.openInterest.isEmpty()} livePrice=${session.livePrice} hasLiveCandle=${session.liveCandle != null} error=${if (session.klines.isEmpty() && session.openInterest.isEmpty()) "Connecting to $currSymbol market telemetry..." else null}")

        _uiState.update {
            it.copy(
                symbol = currSymbol,
                interval = currInterval,
                isLoading = session.klines.isEmpty() && session.openInterest.isEmpty(),
                livePrice = currentPrice,
                liveCandle = activeCandle,
                initialDeltaSign = session.initialDeltaSign,
                premiumIndex = session.premiumIndex,
                openInterest = session.openInterest,
                takerLongShort = session.takerLongShort,
                globalLongShort = session.globalLongShort,
                topTraderAccount = session.topTraderAccount,
                topTraderPosition = session.topTraderPosition,
                positioningMatrix = computePositioningMatrix(session),
                orderBookImbalance = session.liveOrderBookImbalance ?: computeFallbackImbalance(session),
                oiFlowRegime = oiState,
                orderFlowRegime = ofState,
                flowConsensus = consensusState,
                smpeUiState = smpeState,
                candles = session.klines,
                watchlist = activeWatchlist.toList(),
                error = if (session.klines.isEmpty() && session.openInterest.isEmpty()) "Connecting to $currSymbol market telemetry..." else null
            )
        }
    }

    private fun computePositioningMatrix(session: SymbolSession): PositioningMatrix? {
        val globalLS = session.globalLongShort
        val topAccount = session.topTraderAccount
        val topPosition = session.topTraderPosition

        if (globalLS.isEmpty() && topAccount.isEmpty() && topPosition.isEmpty()) return null

        val retailRatio = globalLS.lastOrNull()?.longShortRatio?.toDoubleOrNull() ?: 1.0
        val whaleAccRatio = topAccount.lastOrNull()?.longShortRatio?.toDoubleOrNull() ?: 1.0
        val whalePosRatio = topPosition.lastOrNull()?.longShortRatio?.toDoubleOrNull() ?: 1.0

        val prevRetail = if (globalLS.size > 1) globalLS[globalLS.size - 2].longShortRatio.toDoubleOrNull() ?: retailRatio else retailRatio
        val prevWhaleAcc = if (topAccount.size > 1) topAccount[topAccount.size - 2].longShortRatio.toDoubleOrNull() ?: whaleAccRatio else whaleAccRatio
        val prevWhalePos = if (topPosition.size > 1) topPosition[topPosition.size - 2].longShortRatio.toDoubleOrNull() ?: whalePosRatio else whalePosRatio

        return PositioningMatrix(
            retailRatio = retailRatio,
            retailChangeDown = retailRatio < prevRetail,
            whaleAccountRatio = whaleAccRatio,
            whaleAccountChangeDown = whaleAccRatio < prevWhaleAcc,
            whalePositionRatio = whalePosRatio,
            whalePositionChangeDown = whalePosRatio < prevWhalePos
        )
    }

    private fun computeFallbackImbalance(session: SymbolSession): OrderBookImbalance? {
        val depth = session.depth ?: return null
        if (depth.bids.isEmpty() && depth.asks.isEmpty()) return null
        val totalBids = depth.bids.sumOf { it.getOrNull(1)?.toDoubleOrNull() ?: 0.0 }
        val totalAsks = depth.asks.sumOf { it.getOrNull(1)?.toDoubleOrNull() ?: 0.0 }
        val totalVol = totalBids + totalAsks
        if (totalVol <= 0.0) return null
        val bidPct = (totalBids / totalVol) * 100.0
        val askPct = (totalAsks / totalVol) * 100.0
        return OrderBookImbalance(totalBids, totalAsks, bidPct, askPct)
    }

    // --- V3.5 FLOW CONSENSUS ENGINE ---
    private fun evaluateFlowConsensus(
        oiState: OIFlowRegimeState,
        ofState: OrderFlowRegimeState,
        smpeState: SmpeUiState
    ): FlowConsensusState {
        val oiRegime = oiState.regime.uppercase()
        val ofRegime = ofState.regime.uppercase()
        val oiStrength = oiState.strength.uppercase()
        val cvdConfirm = oiState.confirmation

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

        val (intentKey, intentColor, intentDesc) = when {
            (ofRegime == "THIN-ASK DRIFT" || ofRegime == "EXPANSIVE BUYING") && oiRegime == "SHORT COVERING" -> Triple(
                "SHORT SQUEEZE",
                Color(0xFF22C55E),
                "Forced short covering driving upside squeeze."
            )
            (ofRegime == "THIN-BID DROP" || ofRegime == "EXPANSIVE SELLING") && oiRegime == "LONG LIQUIDATIONS" -> Triple(
                "LONG LIQUIDATION",
                Color(0xFFEF4444),
                "Forced long liquidations driving downside cascade."
            )
            (ofRegime == "ASK ABSORPTION" && oiRegime == "NEW LONGS") || ofRegime == "LONG TRAP" -> Triple(
                "BULL TRAP FORMING",
                Color(0xFFFF9100),
                "Fresh longs absorbed into overhead supply."
            )
            (ofRegime == "BID ABSORPTION" && oiRegime == "NEW SHORTS") || ofRegime == "SHORT TRAP" -> Triple(
                "BEAR TRAP FORMING",
                Color(0xFF00E5FF),
                "Fresh shorts absorbed by passive buyers."
            )
            ofRegime == "EXPANSIVE BUYING" && oiRegime == "NEW LONGS" -> Triple(
                "INITIATIVE BUYING",
                Color(0xFF00C853),
                "New longs driving directional expansion."
            )
            ofRegime == "EXPANSIVE SELLING" && oiRegime == "NEW SHORTS" -> Triple(
                "INITIATIVE SHORTING",
                Color(0xFFFF5252),
                "New shorts driving directional expansion."
            )
            else -> Triple(
                "NEUTRAL AUCTION",
                Color(0xFF9CA3AF),
                "No dominant directional intent detected."
            )
        }

        val isSmpeOverride = smpeState.regime == "SPONSORED_RALLY" &&
            (oiRegime == "NEW LONGS" || oiRegime == "SHORT COVERING") &&
            ((ofRegime == "EXPANSIVE SELLING" || ofRegime == "SELL AGGRESSION") && abs(ofState.deltaPercent) < 25.0)

        val isBullishAbsorptionTrap = (ofRegime == "BID ABSORPTION" || ofRegime == "SHORT TRAP") && oiRegime == "NEW SHORTS"
        val isBearishDistributionWall = (ofRegime == "ASK ABSORPTION" || ofRegime == "LONG TRAP") && (oiRegime == "NEW LONGS" || smpeState.regime == "DISTRIBUTION")

        val (candidateConsensus, candidateBadgeColor, narrativeText, ruleName) = when {
            isSmpeOverride -> Quadruple(
                "SMART MONEY DOMINANCE",
                Color(0xFF10B981),
                "Large traders actively expanding inventory despite mild short-term sell pressure.",
                "SMPE_OVERRIDE: SPONSORED_RALLY + BULLISH_OI => SMART_MONEY_DOMINANCE"
            )
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
            oiRegime == "NEW LONGS" && flowBuying -> Quadruple(
                "STRONG BULLISH CONTINUATION",
                Color(0xFF10B981),
                "Fresh longs entering while buyers control short-term order flow.",
                "NEW_LONGS + BUY_FLOW => STRONG_BULLISH_CONTINUATION"
            )
            oiRegime == "NEW SHORTS" && flowSelling -> Quadruple(
                "STRONG BEARISH CONTINUATION",
                Color(0xFFEF4444),
                "Fresh shorts entering while sellers control short-term order flow.",
                "NEW_SHORTS + SELL_FLOW => STRONG_BEARISH_CONTINUATION"
            )
            oiRegime == "NEW SHORTS" && flowBuying -> Quadruple(
                "SHORT TRAP",
                Color(0xFFF59E0B),
                "New shorts entering while buyers absorb and push price higher. Potential squeeze risk.",
                "NEW_SHORTS + BUY_FLOW => SHORT_TRAP"
            )
            oiRegime == "NEW LONGS" && flowSelling -> Quadruple(
                "LONG TRAP",
                Color(0xFFF97316),
                "New longs entering while sellers control aggressive flow. Potential downside trap.",
                "NEW_LONGS + SELL_FLOW => LONG_TRAP"
            )
            oiRegime == "LONG LIQUIDATIONS" && flowBuying -> Quadruple(
                "CAPITULATION REVERSAL",
                Color(0xFFEAB308),
                "Long liquidation event followed by aggressive buying pressure. Potential local bottom.",
                "LONG_LIQUIDATIONS + BUY_FLOW => CAPITULATION_REVERSAL"
            )
            oiRegime == "SHORT COVERING" && flowBuying -> Quadruple(
                "SHORT SQUEEZE",
                Color(0xFF22C55E),
                "Shorts exiting while buyers continue lifting offers. Potential squeeze continuation.",
                "SHORT_COVERING + BUY_FLOW => SHORT_SQUEEZE"
            )
            oiRegime == "NEW LONGS" && ofRegime == "BALANCED FLOW" && oiState.priceDeltaPercent < 0 -> Quadruple(
                "DISTRIBUTION",
                Color(0xFFF97316),
                "Longs entering but aggressive buying unable to advance price efficiently. Potential distribution.",
                "NEW_LONGS + STALL => DISTRIBUTION"
            )
            oiRegime == "NEW SHORTS" && ofRegime == "BALANCED FLOW" && oiState.priceDeltaPercent > 0 -> Quadruple(
                "ACCUMULATION",
                Color(0xFF3B82F6),
                "Selling pressure absorbed without meaningful price decline. Potential accumulation.",
                "NEW_SHORTS + STALL => ACCUMULATION"
            )
            else -> Quadruple(
                "NEUTRAL",
                Color(0xFF9CA3AF),
                "No dominant market narrative detected.",
                "NO_RULE_MATCH => NEUTRAL"
            )
        }

        val session = activeSessions[_activeSymbol.value]
        val now = System.currentTimeMillis()
        val isNonNeutral = candidateConsensus != "NEUTRAL" && candidateConsensus != "BUILDING WINDOW"
        val activeConsensus = _uiState.value.flowConsensus.consensus

        if (session != null && isNonNeutral && candidateConsensus != activeConsensus) {
            if (activeConsensus != "NEUTRAL" && activeConsensus != "BUILDING WINDOW") {
                synchronized(session) {
                    session.memoryLastConsensus = activeConsensus
                    session.memoryLastNarrative = _uiState.value.flowConsensus.narrative
                    session.memoryLastTimestampMs = now
                    session.memoryLastBadgeColor = _uiState.value.flowConsensus.badgeColor

                    val event = ConsensusHistoryEvent(
                        timestampFormatted = timeFormatter.format(Date(now)),
                        consensus = activeConsensus,
                        badgeColor = _uiState.value.flowConsensus.badgeColor
                    )
                    session.memoryHistoryList.addFirst(event)
                    while (session.memoryHistoryList.size > 5) {
                        session.memoryHistoryList.removeLast()
                    }
                }
            }
        }

        val prevAgeStr = if (session?.memoryLastTimestampMs != null) formatAge(now - session.memoryLastTimestampMs!!) else null

        val mechanicsBiasStr = when {
            intentKey in listOf("SHORT SQUEEZE", "BEAR TRAP FORMING", "INITIATIVE BUYING") ||
            candidateConsensus in listOf("STRONG BULLISH CONTINUATION", "SHORT SQUEEZE", "CAPITULATION REVERSAL", "SHORT TRAP", "BULLISH ABSORPTION TRAP", "ACCUMULATION", "SMART MONEY DOMINANCE") ||
            flowBuying -> "BULLISH"

            intentKey in listOf("LONG LIQUIDATION", "BULL TRAP FORMING", "INITIATIVE SHORTING") ||
            candidateConsensus in listOf("STRONG BEARISH CONTINUATION", "LONG LIQUIDATION CASCADE", "LONG TRAP", "BEARISH DISTRIBUTION WALL", "DISTRIBUTION") ||
            flowSelling -> "BEARISH"

            else -> "NEUTRAL"
        }

        val smpeScore = when {
            invVsFlowLabel == "BUYING INTO DISTRIBUTION" || invVsFlowLabel == "SELLING INTO ACCUMULATION" -> 5
            smpeState.regime in listOf("SPONSORED_RALLY", "ACCUMULATION", "DISTRIBUTION", "ABANDONMENT") -> {
                val isSmpeBullish = smpeState.regime in listOf("SPONSORED_RALLY", "ACCUMULATION")
                if ((isSmpeBullish && mechanicsBiasStr == "BULLISH") || (!isSmpeBullish && mechanicsBiasStr == "BEARISH")) 30 else 15
            }
            smpeState.dominance in listOf("BULL DOMINANCE", "BEAR DOMINANCE", "STRONG BULL DOMINANCE", "SPONSORED RALLY", "EXTREME SPONSORSHIP") -> 20
            else -> 10
        }

        val oiScore = when {
            oiRegime in listOf("NEW LONGS", "NEW SHORTS", "SHORT COVERING", "LONG LIQUIDATIONS") -> {
                val isOiBullish = oiRegime in listOf("NEW LONGS", "SHORT COVERING")
                if ((isOiBullish && mechanicsBiasStr == "BULLISH") || (!isOiBullish && mechanicsBiasStr == "BEARISH")) 25 else 15
            }
            oiRegime in listOf("SHORT CLOSING", "LONG CLOSING") -> 12
            oiRegime == "NEUTRAL" -> 10
            else -> 5
        }

        val ofScore = when {
            ofRegime in listOf("EXPANSIVE BUYING", "EXPANSIVE SELLING", "LONG TRAP", "SHORT TRAP") || ofState.efficiencyBias == "INVERTED" -> 20
            ofRegime in listOf("BID ABSORPTION", "ASK ABSORPTION") || ofState.defenseState != "NONE" -> 18
            ofRegime in listOf("THIN-ASK DRIFT", "THIN-BID DROP") -> 14
            else -> 10
        }

        val momentumScore = when {
            ofState.regimeMomentum.contains("STRENGTHENING") -> 15
            ofState.regimeMomentum.contains("STABLE") -> 8
            ofState.regimeMomentum.contains("WEAKENING") -> 3
            ofState.regimeMomentum.contains("EXHAUSTING") -> 0
            else -> 8
        }

        val persistenceScore = when {
            ofState.persistenceCount >= 6 -> 10
            ofState.persistenceCount in 4..5 -> 8
            ofState.persistenceCount in 2..3 -> 5
            else -> 2
        }

        val confidencePct = (smpeScore + oiScore + ofScore + momentumScore + persistenceScore).coerceIn(0, 100)

        val (confText, confColor) = when {
            confidencePct >= 75 -> "HIGH" to Color(0xFF10B981)
            confidencePct >= 45 -> "MEDIUM" to Color(0xFFEAB308)
            else -> "LOW" to Color(0xFF9CA3AF)
        }

        val tradeBiasStr = when {
            invVsFlowLabel == "BUYING INTO DISTRIBUTION" || invVsFlowLabel == "SELLING INTO ACCUMULATION" || confidencePct < 50 -> "LOW"
            confidencePct >= 75 && ((mechanicsBiasStr == "BULLISH" && smpeState.regime in listOf("SPONSORED_RALLY", "ACCUMULATION", "NEUTRAL")) || (mechanicsBiasStr == "BEARISH" && smpeState.regime in listOf("DISTRIBUTION", "ABANDONMENT", "NEUTRAL"))) && !ofState.regimeMomentum.contains("EXHAUSTING") -> "HIGH"
            else -> "MEDIUM"
        }

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
            momentumScore = momentumScore,
            persistenceScore = persistenceScore,
            totalConfidencePct = confidencePct,
            mechanicsBias = mechanicsBiasStr,
            tradeBias = tradeBiasStr,
            intentRuleFired = intentKey,
            ruleFired = finalRule
        )

        val memoryHistory = if (session != null) {
            synchronized(session) {
                session.memoryHistoryList.toList()
            }
        } else emptyList()

        return FlowConsensusState(
            consensus = finalConsensus,
            badgeColor = finalColor,
            confidencePct = confidencePct,
            confidence = confText,
            confidenceColor = confColor,
            mechanicsBias = mechanicsBiasStr,
            tradeBias = tradeBiasStr,
            oiRegimeDisplay = oiRegime,
            orderFlowDisplay = ofRegime,
            narrative = finalNarrative,
            inventoryVsFlowLabel = invVsFlowLabel,
            inventoryVsFlowBias = invVsFlowBias,
            intentState = intentKey,
            intentColor = intentColor,
            intentDescription = intentDesc,
            transitionSignal = ofState.transitionSignal,
            transitionText = ofState.transitionText,
            previousConsensus = session?.memoryLastConsensus,
            previousConsensusColor = session?.memoryLastBadgeColor ?: Color(0xFF9CA3AF),
            previousNarrative = session?.memoryLastNarrative,
            previousConsensusAgeFormatted = prevAgeStr,
            consensusHistory = memoryHistory,
            debugState = debugState
        )
    }

    private fun processSessionLiveTradeSnapshot(session: SymbolSession, trade: TradeSnapshot) {
        synchronized(session) {
            session.tradeSnapshotBuffer.addLast(trade)

            val now = System.currentTimeMillis()
            session.lastTradeTimeMs = now

            session.tradeRateWindow.addLast(now)
            val rateCutoff = now - 1000L
            while (session.tradeRateWindow.isNotEmpty() && session.tradeRateWindow.first() < rateCutoff) {
                session.tradeRateWindow.removeFirst()
            }

            // Retain full 1 hour (3,600,000 ms) of trade snapshots in buffer
            val cutoffTime = now - 3_600_000L
            while (session.tradeSnapshotBuffer.isNotEmpty() && session.tradeSnapshotBuffer.first().timestampMs < cutoffTime) {
                session.tradeSnapshotBuffer.removeFirst()
            }
        }
    }

    private fun computeOrderFlowRegimeState(session: SymbolSession, activeTf: String): OrderFlowRegimeState {
        val now = System.currentTimeMillis()
        val thresholds = TimeframeThresholds.getThresholds(activeTf)
        val windowDurationMs = getTimeframeDurationMs(activeTf)
        val cutoffTime = now - windowDurationMs

        // THREAD SAFETY FIX 1: Defensive snapshot of tradeSnapshotBuffer inside synchronized(session)
        val trades = synchronized(session) {
            session.tradeSnapshotBuffer.filter { it.timestampMs >= cutoffTime }
        }

        if (trades.isEmpty()) {
            return OrderFlowRegimeState(
                buildingWindow = true,
                debugState = OrderFlowRegimeDebugState(
                    isDebugEnabled = _uiState.value.orderFlowRegime.debugState.isDebugEnabled,
                    connectionStage = wsManager.tradeStreamStage.value,
                    socketUrl = wsManager.tradeSocketUrl.value,
                    currentStream = wsManager.tradeStreamName.value,
                    currentSymbol = session.symbol,
                    currentTimeframe = activeTf,
                    windowStatus = "BUILDING"
                )
            )
        }

        var buyVol = 0.0
        var sellVol = 0.0

        for (t in trades) {
            if (t.isBuyerMaker) {
                sellVol += t.quantity
            } else {
                buyVol += t.quantity
            }
        }

        val totalVol = buyVol + sellVol
        val netDelta = buyVol - sellVol
        val deltaPct = if (totalVol > 0.0) (netDelta / totalVol) * 100.0 else 0.0

        val anchorPrice = trades.first().price
        val lastPrice = trades.last().price
        val priceDeltaPct = if (anchorPrice > 0.0) ((lastPrice - anchorPrice) / anchorPrice) * 100.0 else 0.0

        val isBuilding = trades.size < 5

        val avgWindowVol = synchronized(session) {
            if (!isBuilding && totalVol > 0.0) {
                session.volumeHistoryBuffer.addLast(totalVol)
                while (session.volumeHistoryBuffer.size > 20) {
                    session.volumeHistoryBuffer.removeFirst()
                }
            }
            if (session.volumeHistoryBuffer.isNotEmpty()) session.volumeHistoryBuffer.average() else totalVol
        }

        val effortRatio = if (avgWindowVol > 0.0) totalVol / avgWindowVol else 1.0
        val priceEfficiency = if (abs(deltaPct) > 1.0) (priceDeltaPct / abs(deltaPct)) * 100.0 else 0.0

        val normalizedPriceDelta = abs(priceDeltaPct) / thresholds.tfScaleFactor
        val flowEfficiency = normalizedPriceDelta / kotlin.math.max(abs(deltaPct), 1.0)

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

        val (regimeStr, colorVal, mechanicsSummaryStr) = when {
            isBuilding || totalVol == 0.0 -> Triple(
                "BALANCED FLOW",
                Color(0xFF9CA3AF),
                "Building window telemetry..."
            )
            deltaPct >= thresholds.deltaTriggerPct && priceDeltaPct <= -thresholds.absorptionMaxPricePct -> Triple(
                "LONG TRAP",
                Color(0xFFFF5252),
                "Aggressive buyers unable to achieve upside and losing ground"
            )
            deltaPct <= -thresholds.deltaTriggerPct && priceDeltaPct >= thresholds.absorptionMaxPricePct -> Triple(
                "SHORT TRAP",
                Color(0xFF00C853),
                "Aggressive sellers unable to achieve downside and losing ground"
            )
            deltaPct >= thresholds.deltaTriggerPct && priceDeltaPct <= thresholds.absorptionMaxPricePct -> Triple(
                "ASK ABSORPTION",
                Color(0xFFFF9100),
                "Heavy taker buying absorbed by passive limit ask wall"
            )
            deltaPct <= -thresholds.deltaTriggerPct && priceDeltaPct >= -thresholds.absorptionMaxPricePct -> Triple(
                "BID ABSORPTION",
                Color(0xFF00E5FF),
                "Heavy taker selling absorbed by passive limit bid wall"
            )
            deltaPct >= thresholds.deltaTriggerPct && priceDeltaPct > thresholds.absorptionMaxPricePct -> Triple(
                "EXPANSIVE BUYING",
                Color(0xFF00C853),
                "High buy effort driving clean upward price expansion"
            )
            deltaPct <= -thresholds.deltaTriggerPct && priceDeltaPct < -thresholds.absorptionMaxPricePct -> Triple(
                "EXPANSIVE SELLING",
                Color(0xFFFF5252),
                "High sell effort driving clean downward price breakdown"
            )
            abs(deltaPct) < thresholds.deltaTriggerPct && priceDeltaPct >= thresholds.vacuumMinPricePct -> Triple(
                "THIN-ASK DRIFT",
                Color(0xFF66BB6A),
                "Price rising on low taker effort (overhead liquidity vacuum)"
            )
            abs(deltaPct) < thresholds.deltaTriggerPct && priceDeltaPct <= -thresholds.vacuumMinPricePct -> Triple(
                "THIN-BID DROP",
                Color(0xFFEF5350),
                "Price dropping on low taker effort (pulled bid liquidity)"
            )
            else -> Triple(
                "BALANCED FLOW",
                Color(0xFF9CA3AF),
                "Order flow and price movement balanced within normal thresholds"
            )
        }

        val (previousRegimeStr, persistenceCount, regimeMomentumStr, defenseStateStr, absStreak, expStreak, vacStreak, bullScore, bearScore, currentTransSignal, currentTransText, currentLastNonBal) = synchronized(session) {
            val lastCommit = session.tfLastCommitMs[activeTf] ?: 0L
            val activeBuffer = session.tfWindowBuffers[activeTf] ?: ArrayDeque()
            val dirEffHist = session.dirEffBuffers[activeTf] ?: ArrayDeque()

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

                val lastNonBal = session.lastNonBalancedRegimes[activeTf]
                if (regimeStr != "BALANCED FLOW" && lastNonBal != null && lastNonBal != regimeStr) {
                    val (tSig, tTxt) = when {
                        lastNonBal == "ASK ABSORPTION" && regimeStr == "THIN-ASK DRIFT" -> "BREAKOUT_RELEASE" to "Passive overhead supply exhausted"
                        lastNonBal == "BID ABSORPTION" && regimeStr == "THIN-BID DROP" -> "SUPPORT_FAILURE" to "Passive bid support withdrawn"
                        lastNonBal == "SHORT TRAP" && regimeStr == "EXPANSIVE BUYING" -> "SQUEEZE_IGNITION" to "Trapped shorts fueling initiative buying"
                        lastNonBal == "LONG TRAP" && regimeStr == "EXPANSIVE SELLING" -> "CASCADE_IGNITION" to "Trapped longs fueling cascading supply"
                        else -> "NONE" to "NONE"
                    }
                    if (tSig != "NONE") {
                        session.transitionSignalMap[activeTf] = tSig
                        session.transitionTextMap[activeTf] = tTxt
                        session.transitionHoldCountMap[activeTf] = 2
                    }
                } else if ((session.transitionHoldCountMap[activeTf] ?: 0) > 0) {
                    val remaining = (session.transitionHoldCountMap[activeTf] ?: 1) - 1
                    session.transitionHoldCountMap[activeTf] = remaining
                    if (remaining == 0) {
                        session.transitionSignalMap[activeTf] = "NONE"
                        session.transitionTextMap[activeTf] = "NONE"
                    }
                }

                if (regimeStr != "BALANCED FLOW") {
                    session.lastNonBalancedRegimes[activeTf] = regimeStr
                }

                session.tfLastCommitMs[activeTf] = now
            }

            val prevReg = if (activeBuffer.size >= 2) activeBuffer[activeBuffer.size - 2].regime else null

            var pCount = 1
            for (i in activeBuffer.indices.reversed()) {
                if (i < activeBuffer.size - 1) {
                    if (activeBuffer[i].regime == regimeStr) {
                        pCount++
                    } else break
                }
            }

            val rMom = if (pCount >= 3 && dirEffHist.size >= 3) {
                val effSlope = dirEffHist.last() - dirEffHist[dirEffHist.size - 3]
                val volSlope = if (activeBuffer.size >= 3) activeBuffer.last().effortRatio - activeBuffer[activeBuffer.size - 3].effortRatio else 0.0

                when {
                    pCount >= 6 && effSlope < -0.005 -> "EXHAUSTING ⚠"
                    effSlope > 0.005 || (effSlope >= -0.005 && volSlope > 0.10) -> "STRENGTHENING ↑"
                    effSlope < -0.005 && volSlope <= 0.0 -> "WEAKENING ↓"
                    else -> "STABLE →"
                }
            } else {
                "STABLE →"
            }

            val last4 = activeBuffer.takeLast(4)
            val bidHits = last4.count { it.regime == "BID ABSORPTION" || it.regime == "SHORT TRAP" }
            val askHits = last4.count { it.regime == "ASK ABSORPTION" || it.regime == "LONG TRAP" }

            val defState = when {
                bidHits >= 3 -> "ACTIVE_BID_DEFENSE"
                askHits >= 3 -> "ACTIVE_ASK_DEFENSE"
                else -> "NONE"
            }

            var aStreak = 0
            var eStreak = 0
            var vStreak = 0

            for (snap in activeBuffer.asReversed()) {
                val r = snap.regime
                if (r == "ASK ABSORPTION" || r == "BID ABSORPTION" || r == "LONG TRAP" || r == "SHORT TRAP") aStreak++ else break
            }
            for (snap in activeBuffer.asReversed()) {
                val r = snap.regime
                if (r == "EXPANSIVE BUYING" || r == "EXPANSIVE SELLING") eStreak++ else break
            }
            for (snap in activeBuffer.asReversed()) {
                val r = snap.regime
                if (r == "THIN-ASK DRIFT" || r == "THIN-BID DROP") vStreak++ else break
            }

            val bScore = activeBuffer.count { it.regime in listOf("EXPANSIVE BUYING", "BID ABSORPTION", "SHORT TRAP", "THIN-ASK DRIFT") }
            val brScore = activeBuffer.count { it.regime in listOf("EXPANSIVE SELLING", "ASK ABSORPTION", "LONG TRAP", "THIN-BID DROP") }

            val cSig = session.transitionSignalMap[activeTf] ?: "NONE"
            val cTxt = session.transitionTextMap[activeTf] ?: "NONE"
            val cLastNonBal = session.lastNonBalancedRegimes[activeTf]

            Tuple12(prevReg, pCount, rMom, defState, aStreak, eStreak, vStreak, bScore, brScore, cSig, cTxt, cLastNonBal)
        }

        val isExhausting = regimeMomentumStr == "EXHAUSTING ⚠"

        val persistenceStateStr = when {
            persistenceCount == 1 -> "FRESH"
            persistenceCount in 2..3 -> "DEVELOPING"
            persistenceCount in 4..5 -> "ESTABLISHED"
            persistenceCount >= 6 && isExhausting -> "EXHAUSTING"
            else -> "DOMINANT"
        }

        val warnings = mutableListOf<String>()
        if (wsManager.tradeMsgCount == 0L) warnings.add("WARNING: No WS Messages")
        if (trades.isEmpty()) warnings.add("WARNING: No Buffered Trades")
        if (session.lastTradeTimeMs > 0 && (now - session.lastTradeTimeMs > 10_000L)) warnings.add("WARNING: Trade Stream Stalled")

        val debugState = OrderFlowRegimeDebugState(
            isDebugEnabled = _uiState.value.orderFlowRegime.debugState.isDebugEnabled,
            connectionStage = wsManager.tradeStreamStage.value,
            socketUrl = wsManager.tradeSocketUrl.value,
            currentStream = wsManager.tradeStreamName.value,
            currentSymbol = session.symbol,
            previousSymbol = wsManager.prevSymbol,
            lastSubscriptionTimeFormatted = if (wsManager.lastSubTimeMs > 0) timeFormatter.format(Date(wsManager.lastSubTimeMs)) else "--:--:--",
            lastError = wsManager.tradeLastError.value,
            messagesReceived = wsManager.tradeMsgCount,
            messagesPerSecond = synchronized(session) { session.tradeRateWindow.size.toDouble() },
            lastMessageTimestampFormatted = if (session.lastTradeTimeMs > 0L) millisFormatter.format(Date(session.lastTradeTimeMs)) else "--:--:--.---",
            msSinceLastMessage = if (session.lastTradeTimeMs > 0L) now - session.lastTradeTimeMs else 0L,
            rawMessagePreview = wsManager.rawLastMessage.value,
            reconnectCount = wsManager.reconnectCount,
            bufferedTradesCount = trades.size,
            latestSnapshotTimestampFormatted = if (trades.isNotEmpty()) millisFormatter.format(Date(trades.last().timestampMs)) else "--:--:--.---",
            latestSnapshotPrice = trades.lastOrNull()?.price ?: 0.0,
            currentTimeframe = activeTf,
            windowStatus = if (isBuilding) "BUILDING" else "READY",
            lastPrice = lastPrice,
            lastQuantity = trades.lastOrNull()?.quantity ?: 0.0,
            lastIsBuyerMaker = trades.lastOrNull()?.isBuyerMaker ?: false,
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
            parseFailuresCount = wsManager.parseFailuresCount,
            warnings = warnings
        )

        return OrderFlowRegimeState(
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
    }

    private fun computeOiFlowRegimeState(session: SymbolSession, activeTf: String): OIFlowRegimeState {
        val now = System.currentTimeMillis()
        val targetDurationMs = getTimeframeDurationMs(activeTf)

        // THREAD SAFETY FIX 2: Synchronized snapshot of snapshotBuffer
        val (snapshotCount, oldestAgeSec) = synchronized(session) {
            val count = session.snapshotBuffer.size
            val oldest = if (session.snapshotBuffer.isNotEmpty()) (now - session.snapshotBuffer.first().timestampMs) / 1000L else 0L
            count to oldest
        }
        val anchorSnapshot = synchronized(session) {
            session.snapshotBuffer.lastOrNull { snapshot -> (now - snapshot.timestampMs) >= targetDurationMs }
        }
        val anchorAgeSec = if (anchorSnapshot != null) (now - anchorSnapshot.timestampMs) / 1000L else 0L
        val latestAgeSec = synchronized(session) {
            if (session.snapshotBuffer.isNotEmpty()) (now - session.snapshotBuffer.last().timestampMs) / 1000L else 0L
        }

        val currentPrice = if (session.livePrice > 0.0) session.livePrice else session.liveCandle?.close ?: session.klines.lastOrNull()?.close ?: 0.0
        val currentOi = session.openInterest.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0

        val warnings = mutableListOf<String>()
        if (snapshotCount == 0) warnings.add("WARNING: Snapshot Buffer Empty")
        if (anchorSnapshot == null) warnings.add("WARNING: Anchor Snapshot Missing / Window Building")

        if (anchorSnapshot == null || currentOi <= 0.0 || currentPrice <= 0.0) {
            val debugState = OiFlowRegimeDebugState(
                isDebugEnabled = _uiState.value.oiFlowRegime.debugState.isDebugEnabled,
                snapshotCount = snapshotCount,
                requiredWindowSec = targetDurationMs / 1000L,
                oldestSnapshotAgeSec = oldestAgeSec,
                anchorSnapshotAgeSec = 0L,
                latestSnapshotAgeSec = latestAgeSec,
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
                currentCvd = session.klines.sumOf { it.volumeDelta },
                cvdDirection = "NONE",
                transitionTimeSecAgo = null,
                lastOiPollTimeFormatted = if (session.lastOiPollTimeMs > 0) timeFormatter.format(Date(session.lastOiPollTimeMs)) else "--:--:--",
                msSinceOiPoll = if (session.lastOiPollTimeMs > 0) now - session.lastOiPollTimeMs else 0L,
                lastPriceTickTimeFormatted = if (session.lastPriceTickTimeMs > 0) millisFormatter.format(Date(session.lastPriceTickTimeMs)) else "--:--:--.---",
                msSincePriceTick = if (session.lastPriceTickTimeMs > 0) now - session.lastPriceTickTimeMs else 0L,
                warnings = warnings
            )
            return OIFlowRegimeState(
                regime = "BUILDING WINDOW",
                mechanics = "Waiting for sufficient historical data",
                badgeColor = Color(0xFF9CA3AF),
                buildingWindow = true,
                debugState = debugState
            )
        }

        val deltaPrice = currentPrice - anchorSnapshot.price
        val deltaPricePct = if (anchorSnapshot.price > 0.0) ((currentPrice - anchorSnapshot.price) / anchorSnapshot.price) * 100.0 else 0.0
        val deltaOI = currentOi - anchorSnapshot.openInterest
        val deltaOIPct = if (anchorSnapshot.openInterest > 0.0) ((currentOi - anchorSnapshot.openInterest) / anchorSnapshot.openInterest) * 100.0 else 0.0

        val pricePass = abs(deltaPricePct) >= 0.15
        val oiPass = abs(deltaOIPct) >= 0.25
        val passesSignificanceFilter = pricePass && oiPass

        val currentCvd = session.klines.sumOf { it.volumeDelta }
        val cvdDir = if (currentCvd > 0.0) "POSITIVE" else if (currentCvd < 0.0) "NEGATIVE" else "ZERO"

        val absOI = abs(deltaOIPct)
        val oiScoreVal = when {
            absOI >= 3.0 -> 3
            absOI >= 1.0 -> 2
            else -> 1
        }
        val absPrice = abs(deltaPricePct)
        val priceScoreVal = when {
            absPrice >= 3.0 -> 3
            absPrice >= 1.0 -> 2
            else -> 1
        }
        val totalStrengthScore = oiScoreVal + priceScoreVal

        val (regimeStr, mechanicsStr, colorVal) = when {
            !passesSignificanceFilter -> Triple("NEUTRAL", "Flow expansion below threshold", Color(0xFF9CA3AF))
            deltaOI > 0.0 && deltaPrice > 0.0 -> Triple("NEW LONGS", "Fresh capital entering Longs (Buy-to-Open)", Color(0xFF10B981))
            deltaOI > 0.0 && deltaPrice < 0.0 -> Triple("NEW SHORTS", "Fresh capital entering Shorts (Sell-to-Open)", Color(0xFFEF4444))
            deltaOI < 0.0 && deltaPrice > 0.0 -> Triple("SHORT COVERING", "Shorts buying back positions (Buy-to-Close)", Color(0xFFEAB308))
            deltaOI < 0.0 && deltaPrice < 0.0 -> Triple("LONG LIQUIDATIONS", "Longs exiting positions (Sell-to-Close)", Color(0xFFF97316))
            else -> Triple("NEUTRAL", "Waiting for next OI delta", Color(0xFF9CA3AF))
        }

        val cvdConfirm = when {
            regimeStr == "NEW LONGS" && currentCvd > 0.0 -> "CVD CONFIRMED"
            regimeStr == "NEW SHORTS" && currentCvd < 0.0 -> "CVD CONFIRMED"
            (regimeStr == "NEW LONGS" && currentCvd < 0.0) || (regimeStr == "NEW SHORTS" && currentCvd > 0.0) -> "DIVERGENCE"
            else -> null
        }

        val debugState = OiFlowRegimeDebugState(
            isDebugEnabled = _uiState.value.oiFlowRegime.debugState.isDebugEnabled,
            snapshotCount = snapshotCount,
            requiredWindowSec = targetDurationMs / 1000L,
            oldestSnapshotAgeSec = oldestAgeSec,
            anchorSnapshotAgeSec = anchorAgeSec,
            latestSnapshotAgeSec = latestAgeSec,
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
            overallThresholdPass = passesSignificanceFilter,
            regimeLogicCondition = if (passesSignificanceFilter) "abs(deltaPricePct) >= 0.15 && abs(deltaOIPct) >= 0.25" else "EXPANSION_BELOW_THRESHOLD",
            oiScore = oiScoreVal,
            priceScore = priceScoreVal,
            totalScore = totalStrengthScore,
            currentCvd = currentCvd,
            cvdDirection = cvdDir,
            transitionTimeSecAgo = if (session.lastRegimeTransitionTimeMs > 0) (now - session.lastRegimeTransitionTimeMs) / 1000L else null,
            lastOiPollTimeFormatted = if (session.lastOiPollTimeMs > 0) timeFormatter.format(Date(session.lastOiPollTimeMs)) else "--:--:--",
            msSinceOiPoll = if (session.lastOiPollTimeMs > 0) now - session.lastOiPollTimeMs else 0L,
            lastPriceTickTimeFormatted = if (session.lastPriceTickTimeMs > 0) millisFormatter.format(Date(session.lastPriceTickTimeMs)) else "--:--:--.---",
            msSincePriceTick = if (session.lastPriceTickTimeMs > 0) now - session.lastPriceTickTimeMs else 0L,
            warnings = warnings
        )

        return OIFlowRegimeState(
            regime = regimeStr,
            mechanics = mechanicsStr,
            badgeColor = colorVal,
            strength = if (!passesSignificanceFilter) "LOW" else if (abs(deltaOIPct) >= 1.0) "HIGH" else "MEDIUM",
            strengthColor = if (!passesSignificanceFilter) Color(0xFF9CA3AF) else if (abs(deltaOIPct) >= 1.0) Color(0xFF10B981) else Color(0xFFEAB308),
            confirmation = cvdConfirm,
            priceDeltaPercent = deltaPricePct,
            oiDeltaContracts = deltaOI,
            oiDeltaPercent = deltaOIPct,
            buildingWindow = false,
            debugState = debugState
        )
    }

    // Pure read/calculation SMPE function with synchronized smartMoneySnapshotBuffer read
    private fun computeSmpeState(session: SymbolSession): SmpeUiState {
        val currentPrice = if (session.livePrice > 0.0) session.livePrice else session.liveCandle?.close ?: session.klines.lastOrNull()?.close ?: 0.0
        val oiValueStr = session.openInterest.lastOrNull()?.sumOpenInterestValue?.toDoubleOrNull()
        val oiContracts = session.openInterest.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0
        val totalNotionalOI = oiValueStr ?: (oiContracts * currentPrice)

        val topPos = session.topTraderPosition.lastOrNull()
        val longAccount = topPos?.longAccount?.toDoubleOrNull() ?: 0.5
        val shortAccount = topPos?.shortAccount?.toDoubleOrNull() ?: 0.5

        val longCapital = totalNotionalOI * longAccount
        val shortCapital = totalNotionalOI * shortAccount
        val capitalRatio = if (shortCapital > 0.0) longCapital / shortCapital else 0.0
        val netInventory = longCapital - shortCapital
        val longPositionPct = longAccount * 100.0
        val shortPositionPct = shortAccount * 100.0

        val now = System.currentTimeMillis()

        // THREAD SAFETY FIX 3: Defensive snapshot of smartMoneySnapshotBuffer
        val (smpeCount, smpeBaseline) = synchronized(session) {
            val count = session.smartMoneySnapshotBuffer.size
            val latest = session.smartMoneySnapshotBuffer.lastOrNull()
            val baseline = if (latest != null) {
                session.smartMoneySnapshotBuffer.lastOrNull { snapshot ->
                    snapshot.longPositionPct != latest.longPositionPct ||
                    snapshot.shortPositionPct != latest.shortPositionPct
                } ?: session.smartMoneySnapshotBuffer.firstOrNull() ?: latest
            } else {
                SmartMoneySnapshot(now, currentPrice, longCapital, shortCapital, longPositionPct, shortPositionPct, capitalRatio)
            }
            count to baseline
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

        val (smpeRegimeKey, smpeRegimeColor) = when {
            longCapitalDeltaPct >= 0.20 && shortCapitalDeltaPct <= -0.10 && smpePriceDeltaPct > 0.10 -> "SPONSORED_RALLY" to Color(0xFF10B981)
            longCapitalDeltaPct >= 0.20 && smpePriceDeltaPct in -0.25..0.75 -> "ACCUMULATION" to Color(0xFF3B82F6)
            longCapitalDeltaPct <= -0.60 && (shortCapitalDeltaPct >= 0.20 || smpePriceDeltaPct < -0.20) -> "ABANDONMENT" to Color(0xFFEF4444)
            longCapitalDeltaPct <= -0.20 && smpePriceDeltaPct in -0.25..0.50 -> "DISTRIBUTION" to Color(0xFFF97316)
            else -> "NEUTRAL" to Color(0xFF9CA3AF)
        }

        return SmpeUiState(
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
            snapshotCount = smpeCount,
            lastPollTimestampMs = now
        )
    }

    private suspend fun fetchSymbolDashboardData(session: SymbolSession) {
        val symbol = session.symbol
        val interval = _activeTimeframe.value
        val macroPeriod = if (interval == "1m") "5m" else interval

        coroutineScope {
            val klinesDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getCandleOrderFlow(symbol, interval) } }.getOrNull() ?: emptyList() }
            val oiDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getOpenInterestHistory(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val takerLSDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getTakerLongShortRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val globalLSDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getGlobalLongShortRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val topAccountDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getTopTraderAccountRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val topPositionDeferred = async { runCatching { withTimeoutOrNull(2500L) { fetcher.getTopTraderPositionRatio(symbol, macroPeriod) } }.getOrNull() ?: emptyList() }
            val premiumIndexDeferred = async { runCatching { withTimeoutOrNull(2000L) { fetcher.getPremiumIndex(symbol) } }.getOrNull() }
            val depthDeferred = async {
                runCatching {
                    withTimeoutOrNull(2000L) { fetcher.getOrderBookDepth(symbol, limit = 20) }
                        ?: withTimeoutOrNull(2000L) { fetcher.getSpotOrderBookDepth(symbol, limit = 20) }
                }.getOrNull()
            }

            val klines = klinesDeferred.await()
            val oi = oiDeferred.await()
            val takerLS = takerLSDeferred.await()
            val globalLS = globalLSDeferred.await()
            val topAccount = topAccountDeferred.await()
            val topPosition = topPositionDeferred.await()
            val premiumIndex = premiumIndexDeferred.await()
            val depth = depthDeferred.await()

            session.klines = klines
            session.openInterest = oi
            session.takerLongShort = takerLS
            session.globalLongShort = globalLS
            session.topTraderAccount = topAccount
            session.topTraderPosition = topPosition
            session.premiumIndex = premiumIndex
            session.depth = depth

            Log.d("FM_TRACE", "[REPO] FETCH_DONE sym=$symbol klines=${klines.size} oi=${oi.size} takerLS=${takerLS.size} globalLS=${globalLS.size} topAcc=${topAccount.size} topPos=${topPosition.size} prem=${premiumIndex != null} depth=${depth != null}")

            val currentOi = oi.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0
            val currentPrice = if (session.livePrice > 0.0) session.livePrice else session.liveCandle?.close ?: klines.lastOrNull()?.close ?: 0.0
            val now = System.currentTimeMillis()
            session.lastOiPollTimeMs = now

            if (currentOi > 0.0 && currentPrice > 0.0) {
                synchronized(session) {
                    session.snapshotBuffer.addLast(FlowSnapshot(now, currentPrice, currentOi))
                    val maxHistoryCutoff = now - 7_200_000L
                    while (session.snapshotBuffer.isNotEmpty() && session.snapshotBuffer.first().timestampMs < maxHistoryCutoff) {
                        session.snapshotBuffer.removeFirst()
                    }
                }
            }

            val oiValueStr = oi.lastOrNull()?.sumOpenInterestValue?.toDoubleOrNull()
            val oiContracts = oi.lastOrNull()?.sumOpenInterest?.toDoubleOrNull() ?: 0.0
            val totalNotionalOI = oiValueStr ?: (oiContracts * currentPrice)

            val topPos = topPosition.lastOrNull()
            val longAccount = topPos?.longAccount?.toDoubleOrNull() ?: 0.5
            val shortAccount = topPos?.shortAccount?.toDoubleOrNull() ?: 0.5

            val longCapital = totalNotionalOI * longAccount
            val shortCapital = totalNotionalOI * shortAccount
            val capitalRatio = if (shortCapital > 0.0) longCapital / shortCapital else 0.0

            if (totalNotionalOI > 0.0 && currentPrice > 0.0) {
                synchronized(session) {
                    session.smartMoneySnapshotBuffer.addLast(
                        SmartMoneySnapshot(
                            timestampMs = now,
                            price = currentPrice,
                            longCapital = longCapital,
                            shortCapital = shortCapital,
                            longPositionPct = longAccount * 100.0,
                            shortPositionPct = shortAccount * 100.0,
                            capitalRatio = capitalRatio
                        )
                    )
                    while (session.smartMoneySnapshotBuffer.size > 20) {
                        session.smartMoneySnapshotBuffer.removeFirst()
                    }
                }
            }

            if (symbol == _activeSymbol.value) {
                emitActiveState()
            }
        }
    }
}

data class Tuple12<A, B, C, D, E, F, G, H, I, J, K, L>(
    val first: A, val second: B, val third: C, val fourth: D,
    val fifth: E, val sixth: F, val seventh: G, val eighth: H,
    val ninth: I, val tenth: J, val eleventh: K, val twelfth: L
)
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)