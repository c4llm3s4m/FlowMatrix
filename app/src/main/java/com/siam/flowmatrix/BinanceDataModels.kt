package com.siam.flowmatrix

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class OpenInterestHistResponse(
    val symbol: String,
    val sumOpenInterest: String,
    val sumOpenInterestValue: String,
    val timestamp: Long
)

@Serializable
data class TakerLongShortRatioResponse(
    val buySellRatio: String,
    val buyVol: String,
    val sellVol: String,
    val timestamp: Long
)

@Serializable
data class GlobalLongShortRatioResponse(
    val symbol: String,
    val longShortRatio: String,
    val longAccount: String,
    val shortAccount: String,
    val timestamp: Long
)

// 1. Top Trader (Whale) Account & Position Ratio Response
@Serializable
data class TopTraderLongShortRatioResponse(
    val symbol: String,
    val longShortRatio: String,
    val longAccount: String,
    val shortAccount: String,
    val timestamp: Long
)

// 2. Order Book Depth Response (Binance /fapi/v1/depth)
@Serializable
data class OrderBookDepthResponse(
    val lastUpdateId: Long = 0,
    val bids: List<List<String>> = emptyList(), // Each item is [Price, Quantity]
    val asks: List<List<String>> = emptyList()
)

// 3. Clean Order Book Imbalance Model (e.g., 47.87% Bids vs 52.13% Asks)
data class OrderBookImbalance(
    val bidVolume: Double = 0.0,
    val askVolume: Double = 0.0,
    val bidPercentage: Double = 50.0,
    val askPercentage: Double = 50.0
) {
    val isBidDominant: Boolean get() = bidPercentage > askPercentage
}

// 4. Initial Candle Volume Delta Sign
enum class InitialDeltaSign(val label: String) {
    WAITING("WAITING..."),
    POSITIVE("POSITIVE (+)"),
    NEGATIVE("NEGATIVE (-)")
}

// 5. Sentiment & Multi-Tier Positioning Matrix
enum class SentimentTag(val label: String, val isBullish: Boolean) {
    EXTREMELY_BULLISH("Extremely Bullish", true),
    BULLISH("Bullish", true),
    NEUTRAL("Neutral", true),
    BEARISH("Bearish", false),
    EXTREMELY_BEARISH("Extremely Bearish", false)
}

data class PositioningMatrix(
    val retailRatio: Double,
    val retailChangeDown: Boolean,
    val whaleAccountRatio: Double,
    val whaleAccountChangeDown: Boolean,
    val whalePositionRatio: Double,
    val whalePositionChangeDown: Boolean
) {
    private fun classifyRatio(r: Double): SentimentTag = when {
        r >= 2.20 -> SentimentTag.EXTREMELY_BULLISH
        r >= 1.15 -> SentimentTag.BULLISH
        r <= 0.65 -> SentimentTag.EXTREMELY_BEARISH
        r <= 0.90 -> SentimentTag.BEARISH
        else -> SentimentTag.NEUTRAL
    }

    val retailSentiment: SentimentTag get() = classifyRatio(retailRatio)
    val whaleAccountSentiment: SentimentTag get() = classifyRatio(whaleAccountRatio)
    val whalePositionSentiment: SentimentTag get() = classifyRatio(whalePositionRatio)

    // Smart Money Sentiment: Flips to Extremely Bearish when Retail is crowded Long (>1.5)
    // and out-leveraging actual Whale Position size (classic distribution/trap signature)
    val smartMoneySentiment: SentimentTag
        get() = when {
            retailRatio >= 1.50 && whalePositionRatio < retailRatio -> SentimentTag.EXTREMELY_BEARISH
            retailRatio <= 0.75 && whalePositionRatio > retailRatio -> SentimentTag.EXTREMELY_BULLISH
            whalePositionRatio >= 1.40 -> SentimentTag.BULLISH
            whalePositionRatio <= 0.85 -> SentimentTag.BEARISH
            else -> SentimentTag.NEUTRAL
        }
}

// 6. Dashboard Telemetry Card Types for Custom Ordering & Persistence (10 Cards Total)
enum class TelemetryCardType(val id: String, val title: String) {
    INITIAL_PULSE("INITIAL_PULSE", "Initial Candle Volume Delta"),
    ORDER_BOOK_IMBALANCE("ORDER_BOOK_IMBALANCE", "Order Book Imbalance"),
    POSITIONING_MATRIX("POSITIONING_MATRIX", "Multi-Tier Positioning & Smart Money"),
    POSITIONING_RATIO("POSITIONING_RATIO", "Positioning Ratio"),
    LOCAL_AGGRESSION("LOCAL_AGGRESSION", "Local Aggression"),
    OPEN_INTEREST("OPEN_INTEREST", "Open Interest"),
    OI_FLOW_REGIME("OI_FLOW_REGIME", "OI Flow Regime (Live Delta)"),
    ORDER_FLOW_REGIME("ORDER_FLOW_REGIME", "Order Flow Regime (Live WS)"),
    FLOW_CONSENSUS("FLOW_CONSENSUS", "Flow Consensus Engine"),
    CVD_CHART("CVD_CHART", "Cumulative Volume Delta");

    companion object {
        val DEFAULT_ORDER = listOf(
            INITIAL_PULSE,
            ORDER_BOOK_IMBALANCE,
            POSITIONING_MATRIX,
            POSITIONING_RATIO,
            LOCAL_AGGRESSION,
            OPEN_INTEREST,
            OI_FLOW_REGIME,
            ORDER_FLOW_REGIME,
            FLOW_CONSENSUS,
            CVD_CHART
        )

        fun fromId(id: String): TelemetryCardType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

// 7. Network Connection Mode for Adaptive Depth Streaming
enum class ConnectionMode(val id: String, val label: String, val streamIntervalMs: String) {
    AUTO("AUTO", "🌐 AUTO DETECT", "AUTO"),
    BROADBAND("BROADBAND", "📶 BROADBAND (100ms)", "100ms"),
    CELLULAR("CELLULAR", "📱 HOTSPOT / CELLULAR (1000ms)", "1000ms");

    companion object {
        fun fromId(id: String): ConnectionMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}

// 8. Flow Snapshot Model
data class FlowSnapshot(
    val timestampMs: Long,
    val price: Double,
    val openInterest: Double
)

// 9. OI Flow Regime Diagnostics / Debug State Model
data class OiFlowRegimeDebugState(
    val isDebugEnabled: Boolean = false,
    val snapshotCount: Int = 0,
    val requiredWindowSec: Long = 300L,
    val oldestSnapshotAgeSec: Long = 0L,
    val anchorSnapshotAgeSec: Long = 0L,
    val latestSnapshotAgeSec: Long = 0L,
    val bufferStatus: String = "BUILDING",
    val currentPrice: Double = 0.0,
    val anchorPrice: Double = 0.0,
    val currentOi: Double = 0.0,
    val anchorOi: Double = 0.0,
    val priceDelta: Double = 0.0,
    val priceDeltaPercent: Double = 0.0,
    val oiDelta: Double = 0.0,
    val oiDeltaPercent: Double = 0.0,
    val priceThresholdPass: Boolean = false,
    val oiThresholdPass: Boolean = false,
    val overallThresholdPass: Boolean = false,
    val regimeLogicCondition: String = "BUILDING",
    val oiScore: Int = 0,
    val priceScore: Int = 0,
    val totalScore: Int = 0,
    val currentCvd: Double = 0.0,
    val cvdDirection: String = "NONE",
    val transitionTimeSecAgo: Long? = null,
    val lastOiPollTimeFormatted: String = "--:--:--",
    val msSinceOiPoll: Long = 0L,
    val lastPriceTickTimeFormatted: String = "--:--:--.---",
    val msSincePriceTick: Long = 0L,
    val warnings: List<String> = emptyList()
)

// 10. Dedicated OI Flow Regime State Model (Enhanced Version with Debug)
data class OIFlowRegimeState(
    val regime: String = "BUILDING WINDOW",
    val mechanics: String = "Waiting for sufficient historical data",
    val badgeColor: Color = Color(0xFF9CA3AF),
    val strength: String = "NONE",
    val strengthColor: Color = Color(0xFF9CA3AF),
    val confirmation: String? = null,
    val previousRegime: String? = null,
    val priceDeltaPercent: Double = 0.0,
    val oiDeltaContracts: Double = 0.0,
    val oiDeltaPercent: Double = 0.0,
    val buildingWindow: Boolean = true,
    val debugState: OiFlowRegimeDebugState = OiFlowRegimeDebugState()
)

// 11. Application Mode System (Phase 5: GEMINI vs COPILOT)
enum class AppMode(val id: String, val label: String) {
    GEMINI("GEMINI", "GEMINI MODE"),
    COPILOT("COPILOT", "COPILOT MODE");

    companion object {
        fun fromId(id: String): AppMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMINI
    }
}

// 12. Trade Snapshot Data Model (Phase 4: Order Flow Engine)
data class TradeSnapshot(
    val timestampMs: Long,
    val price: Double,
    val quantity: Double,
    val isBuyerMaker: Boolean
)

// WebSocket Connection State Machine Stages
enum class WsConnectionStage {
    SOCKET_CREATING,
    SOCKET_OPEN,
    SUBSCRIBING,
    SUBSCRIBED,
    RECEIVING_MESSAGES,
    ERROR,
    DISCONNECTED
}

// 13. Order Flow Regime Diagnostics / Debug State Model (Full Diagnostics Pipeline)
data class OrderFlowRegimeDebugState(
    val isDebugEnabled: Boolean = false,
    val connectionStage: WsConnectionStage = WsConnectionStage.DISCONNECTED,
    val socketUrl: String = "wss://fstream.binance.com/ws/btcusdt@trade",
    val currentStream: String = "btcusdt@trade",
    val currentSymbol: String = "BTCUSDT",
    val previousSymbol: String? = null,
    val lastSubscriptionTimeFormatted: String = "--:--:--",
    val lastError: String? = null,
    val messagesReceived: Long = 0L,
    val messagesPerSecond: Double = 0.0,
    val lastMessageTimestampFormatted: String = "--:--:--.---",
    val msSinceLastMessage: Long = 0L,
    val rawMessagePreview: String = "No messages received",
    val reconnectCount: Int = 0,
    val bufferedTradesCount: Int = 0,
    val latestSnapshotTimestampFormatted: String = "--:--:--.---",
    val latestSnapshotPrice: Double = 0.0,
    val currentTimeframe: String = "5m",
    val windowStatus: String = "BUILDING",
    val lastPrice: Double = 0.0,
    val lastQuantity: Double = 0.0,
    val lastIsBuyerMaker: Boolean = false,
    val buyVolume: Double = 0.0,
    val sellVolume: Double = 0.0,
    val netDelta: Double = 0.0,
    val deltaPercent: Double = 0.0,
    val parseFailuresCount: Long = 0L,
    val warnings: List<String> = emptyList()
)

// 14. Order Flow Regime State Model (Live WS Stream Engine with Debug State)
data class OrderFlowRegimeState(
    val regime: String = "BALANCED FLOW",
    val badgeColor: Color = Color(0xFF9CA3AF),
    val buyVolume: Double = 0.0,
    val sellVolume: Double = 0.0,
    val netDelta: Double = 0.0,
    val deltaPercent: Double = 0.0,
    val priceDeltaPercent: Double = 0.0,
    val totalVolume: Double = 0.0,
    val buildingWindow: Boolean = true,
    val debugState: OrderFlowRegimeDebugState = OrderFlowRegimeDebugState()
)

// 15. Flow Consensus History Event Model (Consensus Memory Engine)
data class ConsensusHistoryEvent(
    val timestampFormatted: String,
    val consensus: String,
    val badgeColor: Color
)

// 16. Flow Consensus Diagnostics / Debug State Model
data class FlowConsensusDebugState(
    val isDebugEnabled: Boolean = false,
    val inputOiRegime: String = "NEUTRAL",
    val inputOrderFlowRegime: String = "BALANCED FLOW",
    val inputOiStrength: String = "LOW",
    val inputOrderFlowStrength: String = "LOW",
    val inputCvdConfirmation: String? = null,
    val confidenceScore: Int = 0,
    val ruleFired: String = "No rule fired (NEUTRAL)"
)

// 17. Flow Consensus State Model (Dual Engine Consensus & Memory Engine)
data class FlowConsensusState(
    val consensus: String = "NEUTRAL",
    val badgeColor: Color = Color(0xFF9CA3AF),
    val confidence: String = "LOW",
    val confidenceColor: Color = Color(0xFF9CA3AF),
    val oiRegimeDisplay: String = "NEUTRAL",
    val orderFlowDisplay: String = "BALANCED FLOW",
    val narrative: String = "No dominant market narrative detected.",
    val previousConsensus: String? = null,
    val previousConsensusColor: Color = Color(0xFF9CA3AF),
    val previousNarrative: String? = null,
    val previousConsensusAgeFormatted: String? = null,
    val consensusHistory: List<ConsensusHistoryEvent> = emptyList(),
    val debugState: FlowConsensusDebugState = FlowConsensusDebugState()
)

// The math engine to calculate candle-specific aggression (Dip vs Dump)
data class CandleOrderFlow(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val totalVolume: Double,
    val takerBuyVolume: Double
) {
    val takerSellVolume: Double
        get() = totalVolume - takerBuyVolume

    val volumeDelta: Double
        get() = takerBuyVolume - takerSellVolume

    val priceChangePercent: Double
        get() = abs(close - open) / open * 100.0

    val isBearishExpansion: Boolean
        get() = open > close && priceChangePercent >= 2.0
}