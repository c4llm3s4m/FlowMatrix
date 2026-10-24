package com.siam.flowmatrix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siam.flowmatrix.ui.theme.FlowMatrixTheme

class MainActivity : ComponentActivity() {
    private val viewModel: FlowMatrixViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlowMatrixTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DashboardScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: FlowMatrixViewModel) {
    val state by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- TOP BAR: TITLE & LIVE STATUS ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("FLOW MATRIX", fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 2.sp)
                Text(
                    text = state.connectionStatus,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (state.connectionStatus.contains("LIVE")) Color(0xFF00C853) else Color(0xFFFF9100)
                )
            }

            TextButton(onClick = { viewModel.refresh() }) {
                Text("↻ REFRESH", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- SEARCH INPUT & GO BUTTON ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.uppercase() },
                placeholder = { Text("Ticker (e.g. BTC, ETH, SOL)...") },
                modifier = Modifier.weight(1f).height(52.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                trailingIcon = {
                    IconButton(onClick = {
                        if (searchQuery.isNotBlank()) {
                            val finalTicker = if (searchQuery.endsWith("USDT")) searchQuery else "${searchQuery}USDT"
                            viewModel.setSymbol(finalTicker)
                            searchQuery = ""
                        }
                    }) {
                        Text("GO", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- TIMEFRAME SELECTOR ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1m", "5m", "15m", "1h").forEach { tf ->
                FilterChip(
                    selected = state.interval == tf,
                    onClick = { viewModel.setTimeframe(tf) },
                    label = { Text(tf, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
        }

        // --- OPTIONAL NON-BLOCKING ERROR BANNER ---
        if (state.error != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.error ?: "Network error",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.refresh() }) {
                        Text("Retry", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        val activeCandle = state.liveCandle ?: state.candles.lastOrNull()

        if (state.isLoading && activeCandle == null && state.candles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Connecting to ${state.symbol} market data...", fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Contract: ${state.symbol}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (activeCandle != null) {
                            val priceStr = if (activeCandle.close < 1.0) {
                                String.format("$%.5f", activeCandle.close)
                            } else {
                                String.format("$%,.2f", activeCandle.close)
                            }
                            Text(priceStr, fontWeight = FontWeight.Black, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("Awaiting price...", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                }

                // 1. Aggression
                item {
                    MetricCard("Local Aggression (Live ${state.interval})") {
                        if (activeCandle != null) {
                            DataRow("Taker Buy Vol", String.format("%,.2f", activeCandle.takerBuyVolume))
                            DataRow("Taker Sell Vol", String.format("%,.2f", activeCandle.takerSellVolume))
                            val deltaColor = if (activeCandle.volumeDelta > 0) Color(0xFF00C853) else Color(0xFFD50000)
                            DataRow("Volume Delta", String.format("%,.2f", activeCandle.volumeDelta), deltaColor)
                            val isDump = activeCandle.isBearishExpansion
                            DataRow("Structure", if (isDump) "AGGRESSIVE DUMP (>2%)" else "STANDARD ACTION", if (isDump) Color.Red else Color.Gray)
                        } else {
                            Text("Awaiting live tick data...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 2. Open Interest Chart
                item {
                    val latestOI = state.openInterest.lastOrNull()
                    MetricCard("Open Interest (Live)") {
                        if (latestOI != null) {
                            val totalOiStr = latestOI.sumOpenInterest.toDoubleOrNull() ?: 0.0
                            val notionalStr = latestOI.sumOpenInterestValue.toDoubleOrNull() ?: 0.0
                            DataRow("Total OI (Contracts)", String.format("%,.2f", totalOiStr))
                            DataRow("Notional Value ($)", String.format("$%,.2f", notionalStr))

                            Spacer(modifier = Modifier.height(16.dp))
                            val oiHistory = state.openInterest.mapNotNull { it.sumOpenInterest.toDoubleOrNull() }
                            SparklineChart(
                                data = oiHistory,
                                modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.Black.copy(alpha = 0.03f))
                            )
                        } else {
                            Text("Open Interest data unavailable (Futures API required)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 3. Positioning Ratios
                item {
                    val latestWhale = state.takerLongShort.lastOrNull()
                    val latestRetail = state.globalLongShort.lastOrNull()
                    MetricCard("Positioning Ratio") {
                        if (latestWhale != null && latestRetail != null) {
                            DataRow("Taker Buy/Sell Ratio", String.format("%.4f", latestWhale.buySellRatio.toDoubleOrNull() ?: 0.0))
                            DataRow("Global Account L/S", String.format("%.4f", latestRetail.longShortRatio.toDoubleOrNull() ?: 0.0))
                            val retailLongs = (latestRetail.longAccount.toDoubleOrNull() ?: 0.0) * 100
                            val retailShorts = (latestRetail.shortAccount.toDoubleOrNull() ?: 0.0) * 100
                            DataRow("Retail Longs", String.format("%.2f%%", retailLongs))
                            DataRow("Retail Shorts", String.format("%.2f%%", retailShorts))
                        } else {
                            Text("Positioning ratio data unavailable", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // 4. Cumulative Volume Delta (CVD) Chart
                item {
                    MetricCard("Cumulative Volume Delta (CVD)") {
                        val cvdHistory = remember(state.candles, state.liveCandle) {
                            val baseKlines = if (state.candles.isNotEmpty()) state.candles.dropLast(1) else emptyList()
                            val allKlines = if (activeCandle != null) baseKlines + activeCandle else baseKlines

                            var cumulative = 0.0
                            allKlines.map {
                                cumulative += it.volumeDelta
                                cumulative
                            }
                        }

                        if (cvdHistory.isNotEmpty()) {
                            val currentCvd = cvdHistory.last()
                            val cvdColor = if (currentCvd > 0) Color(0xFF00C853) else Color(0xFFD50000)
                            DataRow("Net Session Aggression", String.format("%,.2f", currentCvd), cvdColor)

                            Spacer(modifier = Modifier.height(16.dp))
                            SparklineChart(
                                data = cvdHistory,
                                modifier = Modifier.fillMaxWidth().height(120.dp).background(Color.Black.copy(alpha = 0.03f))
                            )
                        } else {
                            Text("CVD data unavailable (Awaiting market ticks)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun DataRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
fun SparklineChart(
    data: List<Double>,
    modifier: Modifier = Modifier
) {
    if (data.size < 2) return

    Canvas(modifier = modifier) {
        val maxValue = data.maxOrNull() ?: 1.0
        val minValue = data.minOrNull() ?: 0.0
        val range = if (maxValue == minValue) 1.0 else maxValue - minValue

        val width = size.width
        val height = size.height
        val xStep = width / (data.size - 1)

        val path = Path()
        val fillPath = Path() // For the gradient

        data.forEachIndexed { index, value ->
            val x = index * xStep
            val y = (height - ((value - minValue) / range) * height).toFloat()

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        // Close the fill path at the bottom of the canvas
        fillPath.lineTo(width, height)
        fillPath.lineTo(0f, height)
        fillPath.close()

        // Dynamic Aesthetics: Green if trending up, Red if trending down
        val isTrendUp = data.last() >= data.first()
        val lineColor = if (isTrendUp) Color(0xFF00C853) else Color(0xFFD50000)

        // Translucent fade-out gradient
        val gradientBrush = Brush.verticalGradient(
            colors = listOf(lineColor.copy(alpha = 0.4f), Color.Transparent),
            startY = 0f,
            endY = height
        )

        drawPath(path = fillPath, brush = gradientBrush)
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}