package com.siam.flowmatrix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class BinanceDataFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val futuresBaseUrl = "https://fapi.binance.com"
    private val spotBaseUrl = "https://api.binance.com"

    suspend fun getOpenInterestHistory(symbol: String, period: String = "5m", limit: Int = 50): List<OpenInterestHistResponse> {
        return fetch("$futuresBaseUrl/futures/data/openInterestHist", symbol, period, limit)
    }

    suspend fun getTakerLongShortRatio(symbol: String, period: String = "5m", limit: Int = 50): List<TakerLongShortRatioResponse> {
        return fetch("$futuresBaseUrl/futures/data/takerlongshortRatio", symbol, period, limit)
    }

    suspend fun getGlobalLongShortRatio(symbol: String, period: String = "5m", limit: Int = 50): List<GlobalLongShortRatioResponse> {
        return fetch("$futuresBaseUrl/futures/data/globalLongShortAccountRatio", symbol, period, limit)
    }

    suspend fun getTopTraderAccountRatio(symbol: String, period: String = "5m", limit: Int = 50): List<TopTraderLongShortRatioResponse> {
        return fetch("$futuresBaseUrl/futures/data/topLongShortAccountRatio", symbol, period, limit)
    }

    suspend fun getTopTraderPositionRatio(symbol: String, period: String = "5m", limit: Int = 50): List<TopTraderLongShortRatioResponse> {
        return fetch("$futuresBaseUrl/futures/data/topLongShortPositionRatio", symbol, period, limit)
    }

    suspend fun getOrderBookDepth(symbol: String, limit: Int = 20): OrderBookDepthResponse = withContext(Dispatchers.IO) {
        val httpUrl = "$futuresBaseUrl/fapi/v1/depth".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("symbol", symbol)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")

        fetchDepth(httpUrl.toString())
    }

    suspend fun getSpotOrderBookDepth(symbol: String, limit: Int = 20): OrderBookDepthResponse = withContext(Dispatchers.IO) {
        val httpUrl = "$spotBaseUrl/api/v3/depth".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("symbol", symbol)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")

        fetchDepth(httpUrl.toString())
    }

    private fun fetchDepth(urlString: String): OrderBookDepthResponse {
        val request = Request.Builder().url(urlString).build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string() ?: "{}"
            json.decodeFromString<OrderBookDepthResponse>(body)
        }
    }

    private suspend inline fun <reified T> fetch(url: String, symbol: String, period: String, limit: Int): List<T> = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("symbol", symbol)
            ?.addQueryParameter("period", period)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")

        val request = Request.Builder().url(httpUrl).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string() ?: "[]"
            json.decodeFromString(body)
        }
    }

    // Custom parser for Futures Klines array
    suspend fun getCandleOrderFlow(symbol: String, interval: String = "5m", limit: Int = 50): List<CandleOrderFlow> = withContext(Dispatchers.IO) {
        val httpUrl = "$futuresBaseUrl/fapi/v1/klines".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("symbol", symbol)
            ?.addQueryParameter("interval", interval)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")

        fetchAndParseKlines(httpUrl.toString())
    }

    // Custom parser for Spot Klines array (Fallback when Futures API is blocked or restricted)
    suspend fun getSpotCandleOrderFlow(symbol: String, interval: String = "5m", limit: Int = 50): List<CandleOrderFlow> = withContext(Dispatchers.IO) {
        val httpUrl = "$spotBaseUrl/api/v3/klines".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("symbol", symbol)
            ?.addQueryParameter("interval", interval)
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IllegalArgumentException("Invalid URL")

        fetchAndParseKlines(httpUrl.toString())
    }

    private fun fetchAndParseKlines(urlString: String): List<CandleOrderFlow> {
        val request = Request.Builder().url(urlString).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string() ?: "[]"

            val rootArray = json.parseToJsonElement(body).jsonArray

            return rootArray.mapNotNull { element ->
                try {
                    val candleArray = element.jsonArray
                    val openTime = candleArray[0].jsonPrimitive.content.toLongOrNull() ?: 0L
                    val open = candleArray[1].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                    val high = candleArray[2].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                    val low = candleArray[3].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                    val close = candleArray[4].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                    val totalVolume = candleArray[5].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                    val takerBuyVolume = candleArray[9].jsonPrimitive.content.toDoubleOrNull() ?: (totalVolume / 2.0)

                    CandleOrderFlow(
                        openTime = openTime,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        totalVolume = totalVolume,
                        takerBuyVolume = takerBuyVolume
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}