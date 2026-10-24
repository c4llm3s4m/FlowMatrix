package com.siam.flowmatrix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testKlineJsonParsing() {
        val jsonStr = """[["1607412000000","18800.00","18800.00","18800.00","18800.00","1.000",1607412059999,"18800.00000",1,"0.500","9400.00000","0"]]"""
        val json = Json { ignoreUnknownKeys = true }
        val rootArray = json.parseToJsonElement(jsonStr).jsonArray
        val candleArray = rootArray[0].jsonArray

        val openTime = candleArray[0].jsonPrimitive.content.toLong()
        val open = candleArray[1].jsonPrimitive.content.toDouble()
        assertEquals(1607412000000L, openTime)
        assertEquals(18800.00, open, 0.001)
    }

    @Test
    fun testJsonDeserialization() {
        val json = Json { ignoreUnknownKeys = true }

        val oiJson = """[{"symbol":"BTCUSDT","sumOpenInterest":"20403.41","sumOpenInterestValue":"12056789.00","timestamp":1583127000000}]"""
        val oiList: List<OpenInterestHistResponse> = json.decodeFromString(oiJson)
        assertEquals(1, oiList.size)
        assertEquals("BTCUSDT", oiList[0].symbol)

        val takerJson = """[{"buySellRatio":"1.1234","buyVol":"100.5","sellVol":"89.5","timestamp":1583127000000}]"""
        val takerList: List<TakerLongShortRatioResponse> = json.decodeFromString(takerJson)
        assertEquals(1, takerList.size)

        val globalJson = """[{"symbol":"BTCUSDT","longShortRatio":"1.2345","longAccount":"0.5521","shortAccount":"0.4479","timestamp":1583127000000}]"""
        val globalList: List<GlobalLongShortRatioResponse> = json.decodeFromString(globalJson)
        assertEquals(1, globalList.size)
    }
}