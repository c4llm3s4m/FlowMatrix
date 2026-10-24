package com.siam.flowmatrix

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

// 1. NEW: Top Trader (Whale) Account & Position Ratio Response
@Serializable
data class TopTraderLongShortRatioResponse(
    val symbol: String,
    val longShortRatio: String,
    val longAccount: String,
    val shortAccount: String,
    val timestamp: Long
)

// 2. NEW: Order Book Depth Response (Binance /fapi/v1/depth)
@Serializable
data class OrderBookDepthResponse(
    val lastUpdateId: Long = 0,
    val bids: List<List<String>> = emptyList(), // Each item is [Price, Quantity]
    val asks: List<List<String>> = emptyList()
)

// 3. NEW: Clean Order Book Imbalance Model (e.g., 47.87% Bids vs 52.13% Asks)
data class OrderBookImbalance(
    val bidVolume: Double = 0.0,
    val askVolume: Double = 0.0,
    val bidPercentage: Double = 50.0,
    val askPercentage: Double = 50.0
) {
    val isBidDominant: Boolean get() = bidPercentage > askPercentage
}

// 4. NEW: Initial Candle Volume Delta Sign
enum class InitialDeltaSign(val label: String) {
    WAITING("WAITING..."),
    POSITIVE("POSITIVE (+)"),
    NEGATIVE("NEGATIVE (-)")
}

// 5. NEW: Sentiment & Multi-Tier Positioning Matrix
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