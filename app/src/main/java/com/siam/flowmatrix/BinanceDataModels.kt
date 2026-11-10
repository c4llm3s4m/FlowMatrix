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

// 6. Dashboard Telemetry Card Types for Custom Ordering & Persistence (8 Cards Total)
enum class TelemetryCardType(val id: String, val title: String) {
    INITIAL_PULSE("INITIAL_PULSE", "Initial Candle Volume Delta"),
    ORDER_BOOK_IMBALANCE("ORDER_BOOK_IMBALANCE", "Order Book Imbalance"),
    POSITIONING_MATRIX("POSITIONING_MATRIX", "Multi-Tier Positioning & Smart Money"),
    POSITIONING_RATIO("POSITIONING_RATIO", "Positioning Ratio"),
    LOCAL_AGGRESSION("LOCAL_AGGRESSION", "Local Aggression"),
    OPEN_INTEREST("OPEN_INTEREST", "Open Interest"),
    OI_FLOW_REGIME("OI_FLOW_REGIME", "OI Flow Regime (Live Delta)"),
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

// 8. Core 4-Quadrant OI Flow Regime Models
enum class OiRegimeTag(
    val badgeText: String,
    val subtext: String,
    val color: Color
) {
    NEW_LONGS("NEW LONGS", "Fresh capital entering Longs (Buy to Open)", Color(0xFF10B981)), // Emerald Green (#10B981)
    NEW_SHORTS("NEW SHORTS", "Fresh capital entering Shorts (Sell to Open)", Color(0xFFEF4444)), // Crimson Red (#EF4444)
    SHORT_COVERING("SHORT COVERING", "Shorts forced to Buy-to-Close", Color(0xFFEAB308)), // Amber Gold (#EAB308)
    LONG_LIQUIDATIONS("LONG LIQUIDATIONS", "Longs flushing / Sell-to-Close", Color(0xFFF97316)), // Orange (#F97316)
    NEUTRAL("NEUTRAL / FLAT", "Waiting for next OI delta", Color(0xFF9CA3AF)); // Muted Gray (#9CA3AF)

    companion object {
        fun compute(deltaOI: Double, deltaPrice: Double): OiRegimeTag {
            return when {
                deltaOI > 0.0 && deltaPrice > 0.0 -> NEW_LONGS
                deltaOI > 0.0 && deltaPrice < 0.0 -> NEW_SHORTS
                deltaOI < 0.0 && deltaPrice > 0.0 -> SHORT_COVERING
                deltaOI < 0.0 && deltaPrice < 0.0 -> LONG_LIQUIDATIONS
                else -> NEUTRAL
            }
        }
    }
}

data class OiFlowRegimeState(
    val regime: OiRegimeTag = OiRegimeTag.NEUTRAL,
    val priceDeltaPercent: Double = 0.0,
    val oiDeltaContracts: Double = 0.0,
    val oiDeltaPercent: Double = 0.0
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