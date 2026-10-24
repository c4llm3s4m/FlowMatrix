package com.siam.flowmatrix

import androidx.lifecycle.ViewModel
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
    val candles: List<CandleOrderFlow> = emptyList(),
    val error: String? = null
)

class FlowMatrixViewModel : ViewModel() {
    private val fetcher = BinanceDataFetcher()
    private val wsManager = BinanceWebSocketManager()

    private val _uiState = MutableStateFlow(DashboardState())
    val uiState: StateFlow<DashboardState> = _uiState.asStateFlow()

    init {
        startPolling()
        listenToWebSocket()
        wsManager.connect(_uiState.value.symbol, _uiState.value.interval)
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
        _uiState.update {
            it.copy(
                symbol = symbol,
                interval = interval,
                isLoading = true,
                liveCandle = null,
                initialDeltaSign = InitialDeltaSign.WAITING,
                error = null
            )
        }
        wsManager.connect(symbol, interval)
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
                runCatching { fetcher.getOrderBookDepth(symbol) }.getOrElse {
                    runCatching { fetcher.getSpotOrderBookDepth(symbol) }.getOrNull()
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

            // Calculate Order Book Imbalance Percentage
            val orderBookImbalance = if (depth != null && (depth.bids.isNotEmpty() || depth.asks.isNotEmpty())) {
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
                } else OrderBookImbalance()
            } else null

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
                    orderBookImbalance = orderBookImbalance,
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