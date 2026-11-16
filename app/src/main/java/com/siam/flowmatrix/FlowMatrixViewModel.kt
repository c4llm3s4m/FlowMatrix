package com.siam.flowmatrix

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

    fun getHiddenCards(): Set<TelemetryCardType> {
        val savedString = prefs.getString("hidden_cards_v1", "") ?: ""
        if (savedString.isBlank()) return emptySet()
        val ids = savedString.split(",")
        return ids.mapNotNull { TelemetryCardType.fromId(it) }.toSet()
    }

    fun saveHiddenCards(hiddenCards: Set<TelemetryCardType>) {
        val stringToSave = hiddenCards.joinToString(",") { it.id }
        prefs.edit().putString("hidden_cards_v1", stringToSave).apply()
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
    val smpeUiState: SmpeUiState = SmpeUiState(),
    val candles: List<CandleOrderFlow> = emptyList(),
    val cardOrder: List<TelemetryCardType> = TelemetryCardType.DEFAULT_ORDER,
    val hiddenCards: Set<TelemetryCardType> = emptySet(),
    val connectionMode: ConnectionMode = ConnectionMode.AUTO,
    val activeMode: ConnectionMode = ConnectionMode.BROADBAND,
    val appMode: AppMode = AppMode.GEMINI,
    val error: String? = null
)

class FlowMatrixViewModel(application: Application) : AndroidViewModel(application) {

    init {
        TelemetryRepository.init(application)
        TelemetryForegroundService.startService(application)
    }

    val uiState: StateFlow<DashboardState> = TelemetryRepository.uiState

    val appModeState: StateFlow<AppMode> = TelemetryRepository.uiState
        .map { it.appMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TelemetryRepository.uiState.value.appMode)

    val smpeState: StateFlow<SmpeUiState> = TelemetryRepository.uiState
        .map { it.smpeUiState }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TelemetryRepository.uiState.value.smpeUiState)

    fun setAppMode(mode: AppMode) = TelemetryRepository.setAppMode(mode)
    fun setConnectionMode(mode: ConnectionMode) = TelemetryRepository.setConnectionMode(mode)
    fun toggleOiDebugMode() = TelemetryRepository.toggleOiDebugMode()
    fun toggleOrderFlowDebugMode() = TelemetryRepository.toggleOrderFlowDebugMode()
    fun toggleFlowConsensusDebugMode() = TelemetryRepository.toggleFlowConsensusDebugMode()
    fun toggleSmpeDebugMode() = TelemetryRepository.toggleSmpeDebugMode()
    fun setCardVisible(card: TelemetryCardType, isVisible: Boolean) = TelemetryRepository.setCardVisible(card, isVisible)
    fun showAllCards() = TelemetryRepository.showAllCards()
    fun moveCardUp(card: TelemetryCardType) = TelemetryRepository.moveCardUp(card)
    fun moveCardDown(card: TelemetryCardType) = TelemetryRepository.moveCardDown(card)
    fun resetCardOrder() = TelemetryRepository.resetCardOrder()
    fun setSymbol(newSymbol: String) = TelemetryRepository.setSymbol(newSymbol)
    fun setTimeframe(newInterval: String) = TelemetryRepository.setTimeframe(newInterval)
    fun refresh() = TelemetryRepository.refresh()
}