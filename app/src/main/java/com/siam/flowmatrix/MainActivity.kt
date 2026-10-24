package com.siam.flowmatrix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

// High-Contrast Color Palette
val GreenPositive = Color(0xFF00C853)
val CoralNegative = Color(0xFFFF5252) // Brightened coral red for high contrast against dark cards
val GoldNeutral = Color(0xFFFFB300)

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
                    color = if (state.connectionStatus.contains("LIVE")) GreenPositive else GoldNeutral
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.resetCardOrder() }) {
                    Text("⚙ RESET LAYOUT", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
                TextButton(onClick = { viewModel.refresh() }) {
                    Text("↻ REFRESH", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
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
                // Active Contract Header
                item(key = "header") {
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

                // DYNAMICALLY RENDERED & REORDERABLE TELEMETRY CARDS (1st through 7th)
                itemsIndexed(
                    items = state.cardOrder,
                    key = { _, cardType -> cardType.id }
                ) { index, cardType ->
                    val isFirst = index == 0
                    val isLast = index == state.cardOrder.size - 1

                    when (cardType) {
                        TelemetryCardType.INITIAL_PULSE -> InitialPulseCard(
                            sign = state.initialDeltaSign,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) }
                        )
                        TelemetryCardType.ORDER_BOOK_IMBALANCE -> OrderBookImbalanceCard(
                            imbalance = state.orderBookImbalance,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) }
                        )
                        TelemetryCardType.POSITIONING_MATRIX -> MultiTierPositioningCard(
                            matrix = state.positioningMatrix,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) }
                        )
                        TelemetryCardType.POSITIONING_RATIO -> PositioningRatioCard(
                            matrix = state.positioningMatrix,
                            takerLongShort = state.takerLongShort,
                            globalLongShort = state.globalLongShort,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) }
                        )
                        TelemetryCardType.LOCAL_AGGRESSION -> LocalAggressionCard(
                            activeCandle = activeCandle,
                            interval = state.interval,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) }
                        )
                        TelemetryCardType.OPEN_INTEREST -> OpenInterestCard(
                            openInterest = state.openInterest,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) }
                        )
                        TelemetryCardType.CVD_CHART -> CvdCard(
                            candles = state.candles,
                            activeCandle = activeCandle,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) }
                        )
                    }
                }
            }
        }
    }
}

// Reusable Metric Card Container with Subtle Reorder Up/Down Controls
@Composable
fun MetricCard(
    title: String,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = !isFirst,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = "▲",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isFirst) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = !isLast,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = "▼",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isLast) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

// RESTORED: Classic Positioning Ratio Card (7th Card)
@Composable
fun PositioningRatioCard(
    matrix: PositioningMatrix?,
    takerLongShort: List<TakerLongShortRatioResponse>,
    globalLongShort: List<GlobalLongShortRatioResponse>,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    MetricCard(
        title = "Positioning Ratio",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown
    ) {
        val latestTaker = takerLongShort.lastOrNull()
        val latestRetail = globalLongShort.lastOrNull()

        if (latestTaker != null || latestRetail != null) {
            // Row 1: Smart Money Sentiment
            if (matrix != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Smart Money Sentiment", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SentimentChip(sentiment = matrix.smartMoneySentiment)
                }
            }

            // Row 2: Taker Buy/Sell Ratio (with Buy%/Sell% split)
            if (latestTaker != null) {
                val buyVol = latestTaker.buyVol.toDoubleOrNull() ?: 0.0
                val sellVol = latestTaker.sellVol.toDoubleOrNull() ?: 0.0
                val totalVol = buyVol + sellVol
                val ratioVal = latestTaker.buySellRatio.toDoubleOrNull() ?: 0.0
                val ratioColor = if (ratioVal >= 1.0) GreenPositive else CoralNegative

                val splitText = if (totalVol > 0.0) {
                    val buyPct = (buyVol / totalVol) * 100.0
                    val sellPct = (sellVol / totalVol) * 100.0
                    String.format("(%.1f%% / %.1f%%)", buyPct, sellPct)
                } else "(50.0% / 50.0%)"

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Taker Buy/Sell Ratio", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(splitText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(String.format("%.4f", ratioVal), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ratioColor)
                    }
                }
            }

            // Row 3: Global Account L/S
            if (latestRetail != null) {
                val globalRatio = latestRetail.longShortRatio.toDoubleOrNull() ?: 0.0
                DataRow("Global Account L/S", String.format("%.4f", globalRatio))

                // Row 4: Retail Longs
                val retailLongs = (latestRetail.longAccount.toDoubleOrNull() ?: 0.0) * 100.0
                DataRow("Retail Longs", String.format("%.2f%%", retailLongs), GreenPositive)

                // Row 5: Retail Shorts
                val retailShorts = (latestRetail.shortAccount.toDoubleOrNull() ?: 0.0) * 100.0
                DataRow("Retail Shorts", String.format("%.2f%%", retailShorts), CoralNegative)
            }
        } else {
            Text("Positioning ratio data loading...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InitialPulseCard(
    sign: InitialDeltaSign,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    MetricCard(
        title = "Initial Candle Volume Delta (Initial Pulse)",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("First Ticks Direction", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val chipColor = when (sign) {
                InitialDeltaSign.POSITIVE -> GreenPositive
                InitialDeltaSign.NEGATIVE -> CoralNegative
                InitialDeltaSign.WAITING -> GoldNeutral
            }
            Surface(
                color = chipColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = sign.label,
                    color = chipColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun OrderBookImbalanceCard(
    imbalance: OrderBookImbalance?,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    MetricCard(
        title = "Order Book Imbalance (Depth Dominance)",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown
    ) {
        if (imbalance != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Order Book Ratio", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val statusText = if (imbalance.isBidDominant) "BUY PRESSURE" else "SELL PRESSURE"
                val statusColor = if (imbalance.isBidDominant) GreenPositive else CoralNegative
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            OrderBookImbalanceBar(
                bidPct = imbalance.bidPercentage.toFloat(),
                askPct = imbalance.askPercentage.toFloat()
            )

            Spacer(modifier = Modifier.height(8.dp))

            DataRow("Total Depth Bids", String.format("%,.2f Vol", imbalance.bidVolume), GreenPositive)
            DataRow("Total Depth Asks", String.format("%,.2f Vol", imbalance.askVolume), CoralNegative)
        } else {
            Text("Order book depth loading...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun OrderBookImbalanceBar(bidPct: Float, askPct: Float) {
    val animatedBidPct by animateFloatAsState(targetValue = bidPct, label = "BidPctAnim")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = String.format("Bids %.2f%%", bidPct), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GreenPositive)
            Text(text = String.format("Asks %.2f%%", askPct), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CoralNegative)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(CoralNegative, shape = RoundedCornerShape(7.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = (animatedBidPct / 100f).coerceIn(0.01f, 0.99f))
                    .background(GreenPositive, shape = RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp))
            )
        }
    }
}

@Composable
fun MultiTierPositioningCard(
    matrix: PositioningMatrix?,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    MetricCard(
        title = "Multi-Tier Positioning & Smart Money",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown
    ) {
        if (matrix != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Smart Money Sentiment", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                SentimentChip(sentiment = matrix.smartMoneySentiment)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Retail L/S Ratio", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format("%.2f", matrix.retailRatio), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(6.dp))
                    SentimentChip(sentiment = matrix.retailSentiment)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Whale Account L/S", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format("%.2f", matrix.whaleAccountRatio), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(6.dp))
                    SentimentChip(sentiment = matrix.whaleAccountSentiment)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Whale Position L/S", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format("%.2f", matrix.whalePositionRatio), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(6.dp))
                    SentimentChip(sentiment = matrix.whalePositionSentiment)
                }
            }
        } else {
            Text("Positioning metrics loading...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LocalAggressionCard(
    activeCandle: CandleOrderFlow?,
    interval: String,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    MetricCard(
        title = "Local Aggression (Live $interval)",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown
    ) {
        if (activeCandle != null) {
            DataRow("Taker Buy Vol", String.format("%,.2f", activeCandle.takerBuyVolume))
            DataRow("Taker Sell Vol", String.format("%,.2f", activeCandle.takerSellVolume))
            val deltaColor = if (activeCandle.volumeDelta > 0) GreenPositive else CoralNegative
            DataRow("Volume Delta", String.format("%,.2f", activeCandle.volumeDelta), deltaColor)
            val isDump = activeCandle.isBearishExpansion
            DataRow("Structure", if (isDump) "AGGRESSIVE DUMP (>2%)" else "STANDARD ACTION", if (isDump) CoralNegative else Color.Gray)
        } else {
            Text("Awaiting live tick data...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun OpenInterestCard(
    openInterest: List<OpenInterestHistResponse>,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val latestOI = openInterest.lastOrNull()
    MetricCard(
        title = "Open Interest (Live)",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown
    ) {
        if (latestOI != null) {
            val totalOiStr = latestOI.sumOpenInterest.toDoubleOrNull() ?: 0.0
            val notionalStr = latestOI.sumOpenInterestValue.toDoubleOrNull() ?: 0.0
            DataRow("Total OI (Contracts)", String.format("%,.2f", totalOiStr))
            DataRow("Notional Value ($)", String.format("$%,.2f", notionalStr))

            Spacer(modifier = Modifier.height(16.dp))
            val oiHistory = openInterest.mapNotNull { it.sumOpenInterest.toDoubleOrNull() }
            SparklineChart(
                data = oiHistory,
                modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.Black.copy(alpha = 0.03f))
            )
        } else {
            Text("Open Interest data unavailable (Futures API required)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun CvdCard(
    candles: List<CandleOrderFlow>,
    activeCandle: CandleOrderFlow?,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    MetricCard(
        title = "Cumulative Volume Delta (CVD)",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown
    ) {
        val cvdHistory = remember(candles, activeCandle) {
            val baseKlines = if (candles.isNotEmpty()) candles.dropLast(1) else emptyList()
            val allKlines = if (activeCandle != null) baseKlines + activeCandle else baseKlines

            var cumulative = 0.0
            allKlines.map {
                cumulative += it.volumeDelta
                cumulative
            }
        }

        if (cvdHistory.isNotEmpty()) {
            val currentCvd = cvdHistory.last()
            val cvdColor = if (currentCvd > 0) GreenPositive else CoralNegative
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

@Composable
fun SentimentChip(sentiment: SentimentTag) {
    val (badgeColor, containerColor) = when (sentiment) {
        SentimentTag.EXTREMELY_BULLISH -> GreenPositive to Color(0x2B00C853)
        SentimentTag.BULLISH -> Color(0xFF66BB6A) to Color(0x2266BB6A)
        SentimentTag.NEUTRAL -> GoldNeutral to Color(0x22FFB300)
        SentimentTag.BEARISH -> CoralNegative to Color(0x22FF5252)
        SentimentTag.EXTREMELY_BEARISH -> CoralNegative to Color(0x33FF5252)
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = sentiment.label.uppercase(),
            color = badgeColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
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

        // Dynamic Aesthetics: Green if trending up, Coral Red if trending down
        val isTrendUp = data.last() >= data.first()
        val lineColor = if (isTrendUp) GreenPositive else CoralNegative

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