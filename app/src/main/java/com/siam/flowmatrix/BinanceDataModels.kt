package com.siam.flowmatrix

import kotlinx.serialization.Serializable

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
        get() = Math.abs(close - open) / open * 100

    val isBearishExpansion: Boolean
        get() = open > close && priceChangePercent >= 2.0
}