package com.siam.flowmatrix

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

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

    fun getWatchlist(): List<WatchlistItem> {
        val savedString = prefs.getString("watchlist_v1", "") ?: ""
        if (savedString.isBlank()) return emptyList()
        return savedString.split(";").mapNotNull { token ->
            val parts = token.split("|")
            if (parts.size == 3) {
                val sym = parts[0]
                val created = parts[1].toLongOrNull() ?: 0L
                val expires = parts[2].toLongOrNull() ?: 0L
                if (sym.isNotBlank() && expires > 0L) WatchlistItem(sym, created, expires) else null
            } else null
        }
    }

    fun saveWatchlist(watchlist: List<WatchlistItem>) {
        val stringToSave = watchlist.joinToString(";") { "${it.symbol}|${it.createdAtMs}|${it.expiresAtMs}" }
        prefs.edit().putString("watchlist_v1", stringToSave).apply()
    }
}

data class DashboardState(
    val symbol: String = "BTCUSDT",
    val interval: String = "5m",
    val isLoading: Boolean = true,
    val connectionStatus: String = "Initializing...",
    val livePrice: Double = 0.0,
    val liveCandle: CandleOrderFlow? = null,
    val initialDeltaSign: InitialDeltaSign = InitialDeltaSign.WAITING,
    val premiumIndex: PremiumIndexResponse? = null,
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
    val watchlist: List<WatchlistItem> = emptyList(),
    val watchlistError: String? = null,
    val error: String? = null
)

class FlowMatrixViewModel(application: Application) : AndroidViewModel(application) {

    init {
        TelemetryRepository.init(application)
        TelemetryForegroundService.startService(application)
    }

    val uiState: StateFlow<DashboardState> = TelemetryRepository.uiState

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
    fun addSymbolToWatchlist(symbol: String) = TelemetryRepository.addSymbolToWatchlist(symbol)
    fun removeSymbolFromWatchlist(symbol: String) = TelemetryRepository.removeSymbolFromWatchlist(symbol)
    fun clearWatchlistError() = TelemetryRepository.clearWatchlistError()
    fun refresh() = TelemetryRepository.refresh()

    fun exportActiveSymbol(context: Context, format: ExportFormat) {
        val (fileName, content) = TelemetryRepository.buildActiveSymbolExport(format)
        TelemetryExporter.exportAndShareFile(context, fileName, content, format)
    }

    fun exportFullWatchlist(context: Context) {
        val (fileName, content) = TelemetryRepository.buildFullWatchlistExport()
        TelemetryExporter.exportAndShareFile(context, fileName, content, ExportFormat.TXT_REPORT)
    }
}