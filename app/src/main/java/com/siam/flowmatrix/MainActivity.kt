package com.siam.flowmatrix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siam.flowmatrix.ui.theme.FlowMatrixTheme
import java.util.Locale
import kotlin.math.abs

// High-Contrast Color Palette
val GreenPositive = Color(0xFF00C853)
val CoralNegative = Color(0xFFFF5252) // Brightened coral red for high contrast against dark cards
val GoldNeutral = Color(0xFFFFB300)

fun formatCompactUsdt(amount: Double): String {
    val absAmount = abs(amount)
    return when {
        absAmount >= 1_000_000_000 -> String.format(Locale.US, "$%.2fB", amount / 1_000_000_000.0)
        absAmount >= 1_000_000 -> String.format(Locale.US, "$%.2fM", amount / 1_000_000.0)
        absAmount >= 1_000 -> String.format(Locale.US, "$%.2fK", amount / 1_000.0)
        else -> String.format(Locale.US, "$%.2f", amount)
    }
}

fun checkIsIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun requestIgnoreBatteryOptimizations(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        openAppDetailsSettings(context)
    }
}

fun openHyperOsAutostartSettings(context: Context) {
    try {
        val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            putExtra("extra_pkgname", context.packageName)
        }
        context.startActivity(miuiIntent)
    } catch (_: Exception) {
        openAppDetailsSettings(context)
    }
}

fun openAppDetailsSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (_: Exception) {}
}

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
    var showCardVisibilityDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- TOP BAR: TITLE, LIVE STATUS, CARDS & GEAR SETTINGS ---
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
                TextButton(onClick = { showCardVisibilityDialog = true }) {
                    Text("👁 CARDS", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Text("⚙", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // --- APP MODE SYSTEM SELECTOR (GEMINI MODE vs COPILOT MODE) ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.appMode == mode,
                    onClick = { viewModel.setAppMode(mode) },
                    label = {
                        val labelText = if (mode == AppMode.GEMINI) "[ GEMINI MODE ]" else "[ COPILOT MODE ]"
                        Text(labelText, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

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

        Spacer(modifier = Modifier.height(8.dp))

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

        Spacer(modifier = Modifier.height(6.dp))

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

        // Filter cards based on AppMode and User Hidden Cards Selection
        val visibleCards = remember(state.cardOrder, state.appMode, state.hiddenCards) {
            state.cardOrder.filter { cardType ->
                val isAppModeAllowed = if (state.appMode == AppMode.GEMINI) {
                    cardType != TelemetryCardType.OI_FLOW_REGIME &&
                    cardType != TelemetryCardType.ORDER_FLOW_REGIME &&
                    cardType != TelemetryCardType.FLOW_CONSENSUS &&
                    cardType != TelemetryCardType.SMART_MONEY_POSITION
                } else {
                    true
                }
                val isUserVisible = cardType !in state.hiddenCards
                isAppModeAllowed && isUserVisible
            }
        }

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
                                String.format(Locale.US, "$%.5f", activeCandle.close)
                            } else {
                                String.format(Locale.US, "$%,.2f", activeCandle.close)
                            }
                            Text(priceStr, fontWeight = FontWeight.Black, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("Awaiting price...", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                }

                // DYNAMICALLY RENDERED & REORDERABLE TELEMETRY CARDS
                itemsIndexed(
                    items = visibleCards,
                    key = { _, cardType -> cardType.id }
                ) { index, cardType ->
                    val isFirst = index == 0
                    val isLast = index == visibleCards.size - 1

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
                            activeMode = state.activeMode,
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
                        TelemetryCardType.SMART_MONEY_POSITION -> SmartMoneyPositionCard(
                            smpeState = state.smpeUiState,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) },
                            onToggleDebug = { viewModel.toggleSmpeDebugMode() }
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
                        TelemetryCardType.OI_FLOW_REGIME -> OiFlowRegimeCard(
                            regimeState = state.oiFlowRegime,
                            interval = state.interval,
                            symbol = state.symbol,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) },
                            onToggleDebug = { viewModel.toggleOiDebugMode() }
                        )
                        TelemetryCardType.ORDER_FLOW_REGIME -> OrderFlowRegimeCard(
                            regimeState = state.orderFlowRegime,
                            interval = state.interval,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) },
                            onToggleDebug = { viewModel.toggleOrderFlowDebugMode() }
                        )
                        TelemetryCardType.FLOW_CONSENSUS -> FlowConsensusCard(
                            consensusState = state.flowConsensus,
                            isFirst = isFirst,
                            isLast = isLast,
                            onMoveUp = { viewModel.moveCardUp(cardType) },
                            onMoveDown = { viewModel.moveCardDown(cardType) },
                            onToggleDebug = { viewModel.toggleFlowConsensusDebugMode() }
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

    // --- CARD VISIBILITY SELECTION MODAL DIALOG ---
    if (showCardVisibilityDialog) {
        CardVisibilityDialog(
            allCards = TelemetryCardType.entries,
            hiddenCards = state.hiddenCards,
            onToggleVisibility = { card, isVisible ->
                viewModel.setCardVisible(card, isVisible)
            },
            onShowAll = {
                viewModel.showAllCards()
            },
            onDismiss = {
                showCardVisibilityDialog = false
            }
        )
    }

    // --- GEAR SETTINGS & NETWORK MODAL DIALOG ---
    if (showSettingsDialog) {
        SettingsDialog(
            connectionMode = state.connectionMode,
            activeMode = state.activeMode,
            onSelectConnectionMode = { mode ->
                viewModel.setConnectionMode(mode)
            },
            onRefresh = {
                viewModel.refresh()
            },
            onResetLayout = {
                viewModel.resetCardOrder()
            },
            onDismiss = {
                showSettingsDialog = false
            }
        )
    }
}

// Settings & Network Menu Modal Dialog (Accessible via Top Gear Icon ⚙)
@Composable
fun SettingsDialog(
    connectionMode: ConnectionMode,
    activeMode: ConnectionMode,
    onSelectConnectionMode: (ConnectionMode) -> Unit,
    onRefresh: () -> Unit,
    onResetLayout: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "⚙ SETTINGS & PREFERENCES",
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "NETWORK CONNECTION MODE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GoldNeutral
                )

                ConnectionMode.entries.forEach { mode ->
                    val isSelected = connectionMode == mode
                    Surface(
                        onClick = { onSelectConnectionMode(mode) },
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectConnectionMode(mode) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val displayLabel = if (connectionMode == ConnectionMode.AUTO && mode == ConnectionMode.AUTO) {
                                "🌐 AUTO DETECT (${activeMode.streamIntervalMs})"
                            } else mode.label
                            Text(
                                text = displayLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = "HYPEROS & BACKGROUND PROTECTION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GoldNeutral
                )

                val context = LocalContext.current
                val isExempted = remember(context) { checkIsIgnoringBatteryOptimizations(context) }

                Surface(
                    color = if (isExempted) GreenPositive.copy(alpha = 0.15f) else GoldNeutral.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isExempted) "🟢 KEEP ALIVE ACTIVE" else "🟡 KEEP ALIVE: UNEXEMPTED",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isExempted) GreenPositive else GoldNeutral
                            )
                            if (!isExempted) {
                                TextButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                                    Text("EXEMPT", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Exempt Flow Matrix from battery saver restrictions so HyperOS does not pause background WebSocket streams when display locks.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = { openHyperOsAutostartSettings(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚡ HYPEROS AUTOSTART & PERMISSIONS", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Text(
                    text = "QUICK ACTIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = GoldNeutral
                )

                OutlinedButton(
                    onClick = {
                        onRefresh()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("↻ REFRESH DATA & RECONNECT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        onResetLayout()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("⚙ RESET CARD LAYOUT", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

// Card Visibility Checkbox Selection Modal Dialog
@Composable
fun CardVisibilityDialog(
    allCards: List<TelemetryCardType>,
    hiddenCards: Set<TelemetryCardType>,
    onToggleVisibility: (TelemetryCardType, Boolean) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "MANAGE DASHBOARD CARDS",
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Check cards to show, uncheck to hide:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                allCards.forEach { card ->
                    val isVisible = card !in hiddenCards
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isVisible,
                            onCheckedChange = { isChecked ->
                                onToggleVisibility(card, isChecked)
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = card.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isVisible) MaterialTheme.colorScheme.onSurface else Color.Gray
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onShowAll) {
                Text("SHOW ALL", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            }
        }
    )
}

// Reusable Metric Card Container with Subtle Reorder Up/Down Controls & Optional Action
@Composable
fun MetricCard(
    title: String,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    headerExtra: (@Composable () -> Unit)? = null,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    if (headerExtra != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        headerExtra()
                    }
                }

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

// PHASE 6 (PART 3): Smart Money Position Engine Card
@Composable
fun SmartMoneyPositionCard(
    smpeState: SmpeUiState,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleDebug: () -> Unit
) {
    MetricCard(
        title = "Smart Money Position Engine",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        headerExtra = {
            Surface(
                onClick = onToggleDebug,
                color = if (smpeState.isDebugEnabled) MaterialTheme.colorScheme.primaryContainer else Color.Gray.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "[DEBUG]",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (smpeState.isDebugEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    ) {
        // ROW 1: Position Regime Badge
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Position Regime", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Surface(
                color = smpeState.regimeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                val displayRegime = smpeState.regime.replace("_", " ").uppercase()
                Text(
                    text = displayRegime,
                    color = smpeState.regimeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // ROW 2: Long Inventory
        val longDeltaColor = if (smpeState.longCapitalDeltaPct > 0) GreenPositive else if (smpeState.longCapitalDeltaPct < 0) CoralNegative else MaterialTheme.colorScheme.onSurface
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Long Inventory", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${formatCompactUsdt(smpeState.longCapital)} (${String.format(Locale.US, "%+.2f%%", smpeState.longCapitalDeltaPct)})",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = longDeltaColor
            )
        }

        // ROW 3: Short Inventory
        val shortDeltaColor = if (smpeState.shortCapitalDeltaPct > 0) GreenPositive else if (smpeState.shortCapitalDeltaPct < 0) CoralNegative else MaterialTheme.colorScheme.onSurface
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Short Inventory", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${formatCompactUsdt(smpeState.shortCapital)} (${String.format(Locale.US, "%+.2f%%", smpeState.shortCapitalDeltaPct)})",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = shortDeltaColor
            )
        }

        // ROW 4: Dominance Ratio
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Dominance Ratio", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.US, "%.2fx", smpeState.capitalRatio), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = smpeState.dominanceColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = smpeState.dominance,
                        color = smpeState.dominanceColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // ROW 5: Inventory Momentum
        val momColor = if (smpeState.inventoryMomentum > 0) GreenPositive else if (smpeState.inventoryMomentum < 0) CoralNegative else MaterialTheme.colorScheme.onSurface
        DataRow("Inventory Momentum", String.format(Locale.US, "%+.2f%%", smpeState.inventoryMomentum), momColor)

        // ROW 6: Position Share (L/S)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Position Share (L/S)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Text(String.format(Locale.US, "%.2f%%", smpeState.longPositionPct), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = GreenPositive)
                Text(" / ", fontSize = 14.sp, color = Color.Gray)
                Text(String.format(Locale.US, "%.2f%%", smpeState.shortPositionPct), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CoralNegative)
            }
        }

        // ROW 7: Transition
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transition", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (smpeState.transitionAgeFormatted != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("(${smpeState.transitionAgeFormatted})", fontSize = 11.sp, color = Color.Gray)
                }
            }
            Text(
                text = smpeState.transitionLabel ?: "NONE",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (smpeState.transitionLabel != null) MaterialTheme.colorScheme.primary else Color.Gray
            )
        }

        // --- COLLAPSIBLE DIAGNOSTICS / DEBUG DRAWER ---
        if (smpeState.isDebugEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = GoldNeutral.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🛠 SMPE ENGINE DIAGNOSTICS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = GoldNeutral)

                DebugHeader("SYSTEM & BUFFER")
                DebugRow("Snapshot Count", "${smpeState.snapshotCount}")
                DebugRow("Buffer Status", if (smpeState.snapshotCount >= 2) "READY" else "BUILDING", if (smpeState.snapshotCount >= 2) GreenPositive else Color.Gray)
                val pollAge = if (smpeState.lastPollTimestampMs > 0) System.currentTimeMillis() - smpeState.lastPollTimestampMs else 0L
                DebugRow("Last Poll Age", "$pollAge ms")
                DebugRow("HTTP Status", "${smpeState.lastHttpStatus}")

                DebugHeader("INVENTORY CALCULATIONS")
                DebugRow("Net Inventory", formatCompactUsdt(smpeState.netInventory))
                DebugRow("Position Share Spread", String.format(Locale.US, "%+.2f%%", smpeState.positioningSpread))
                DebugRow("Long Capital Delta", formatCompactUsdt(smpeState.longCapitalDelta))
                DebugRow("Short Capital Delta", formatCompactUsdt(smpeState.shortCapitalDelta))

                DebugHeader("REGIME & THRESHOLD TRACE")
                DebugRow("Rule Evaluated", smpeState.ruleEvaluated)
                DebugRow("Transition Age", smpeState.transitionAgeFormatted ?: "--")
            }
        }
    }
}

// PHASE 5 & 6: Flow Consensus Engine Card with Memory Engine & Inventory vs Flow Detector
@Composable
fun FlowConsensusCard(
    consensusState: FlowConsensusState,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleDebug: () -> Unit
) {
    val debug = consensusState.debugState

    MetricCard(
        title = "Flow Consensus Engine",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        headerExtra = {
            Surface(
                onClick = onToggleDebug,
                color = if (debug.isDebugEnabled) MaterialTheme.colorScheme.primaryContainer else Color.Gray.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "[DEBUG]",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (debug.isDebugEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    ) {
        // ROW 1: Consensus Badge
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Consensus", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Surface(
                color = consensusState.badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = consensusState.consensus.uppercase(),
                    color = consensusState.badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // ROW 2: Confidence Badge
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Confidence", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = consensusState.confidenceColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = consensusState.confidence,
                    color = consensusState.confidenceColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // ROW 3: OI Regime Display
        DataRow("OI Regime", consensusState.oiRegimeDisplay)

        // ROW 4: Order Flow Display
        DataRow("Order Flow", consensusState.orderFlowDisplay)

        // ROW 5: Inventory vs Flow Conflict Detector Row
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Inventory vs Flow", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val biasColor = when (consensusState.inventoryVsFlowBias) {
                "STRONG BULLISH" -> GreenPositive
                "BULLISH" -> Color(0xFF10B981)
                "BEARISH" -> Color(0xFFF97316)
                "STRONG BEARISH" -> CoralNegative
                else -> GoldNeutral
            }
            Surface(
                color = biasColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = consensusState.inventoryVsFlowLabel,
                    color = biasColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // ROW 6: Narrative Text Explanation
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text("Narrative", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
            Text(
                text = consensusState.narrative,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f),
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        // ROW 7: Last Consensus (Consensus Memory Engine)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Last Consensus", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (consensusState.previousConsensusAgeFormatted != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("(${consensusState.previousConsensusAgeFormatted})", fontSize = 11.sp, color = Color.Gray)
                }
            }
            val lastConsText = consensusState.previousConsensus ?: "None"
            val lastConsColor = if (consensusState.previousConsensus != null) consensusState.previousConsensusColor else Color.Gray
            Surface(
                color = lastConsColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = lastConsText.uppercase(),
                    color = lastConsColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // ROW 8: Last Narrative
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text("Last Narrative", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.width(90.dp))
            Text(
                text = consensusState.previousNarrative ?: "Awaiting first non-neutral consensus signal.",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f)
            )
        }

        // --- DEBUG MODE SECTION & CONSENSUS HISTORY ---
        if (debug.isDebugEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = GoldNeutral.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🛠 CONSENSUS ENGINE DEBUG", fontSize = 11.sp, fontWeight = FontWeight.Black, color = GoldNeutral)

                DebugHeader("CONSENSUS INPUTS")
                DebugRow("Input OI Regime", debug.inputOiRegime)
                DebugRow("Input Order Flow Regime", debug.inputOrderFlowRegime)
                DebugRow("Input OI Strength", debug.inputOiStrength)
                DebugRow("Input Order Flow Strength", debug.inputOrderFlowStrength)
                DebugRow("CVD Confirmation", debug.inputCvdConfirmation ?: "NONE")
                DebugRow("Input SMPE Regime", debug.inputSmpeRegime)
                DebugRow("Input SMPE Dominance", debug.inputSmpeDominance)
                DebugRow("Inventory vs Flow Bias", debug.inventoryVsFlowBias)

                DebugHeader("CONFIDENCE ENGINE")
                DebugRow("Confidence Score", "${debug.confidenceScore}")
                DebugRow("Confidence Level", consensusState.confidence, consensusState.confidenceColor)

                DebugHeader("RULE TRIGGERED")
                DebugRow("Rule Fired", debug.ruleFired, consensusState.badgeColor)

                DebugHeader("CONSENSUS HISTORY (LAST 5 EVENTS)")
                if (consensusState.consensusHistory.isNotEmpty()) {
                    consensusState.consensusHistory.forEach { event ->
                        DebugRow(event.timestampFormatted, event.consensus, event.badgeColor)
                    }
                } else {
                    DebugRow("History Status", "No history recorded yet", Color.Gray)
                }
            }
        }
    }
}

// Wyckoff Effort vs Result Order Flow Regime Card V3
@Composable
fun OrderFlowRegimeCard(
    regimeState: OrderFlowRegimeState,
    interval: String,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleDebug: () -> Unit
) {
    val debug = regimeState.debugState

    MetricCard(
        title = "Order Flow Regime (Live WS)",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        headerExtra = {
            Surface(
                onClick = onToggleDebug,
                color = if (debug.isDebugEnabled) MaterialTheme.colorScheme.primaryContainer else Color.Gray.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "[DEBUG]",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (debug.isDebugEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    ) {
        // ROW 1: Active Regime Badge
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Active Flow Regime", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Surface(
                color = regimeState.badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = regimeState.regime.uppercase(),
                    color = regimeState.badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // ROW 2: Effort vs Result Status & Mechanics Subtitle
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Effort vs Result", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${regimeState.effortLevel} EFFORT / ${regimeState.resultLevel} RESULT",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = regimeState.mechanicsSummary,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // ROW 3: Persistence Engine
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Persistence", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${regimeState.persistenceState} (${regimeState.persistenceCount} ${if (regimeState.persistenceCount == 1) "window" else "windows"})",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ROW 4: Defense State
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Defense State", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val defColor = when (regimeState.defenseState) {
                "ACTIVE_BID_DEFENSE" -> GreenPositive
                "ACTIVE_ASK_DEFENSE" -> CoralNegative
                else -> Color.Gray
            }
            if (regimeState.defenseState != "NONE") {
                Surface(
                    color = defColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = regimeState.defenseState.replace("_", " "),
                        color = defColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            } else {
                Text("NONE", fontSize = 12.sp, color = Color.Gray)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // ROW 5: Buy Volume
        DataRow(
            label = "Buy Volume ($interval)",
            value = String.format(Locale.US, "%,.2f Vol", regimeState.buyVolume),
            valueColor = GreenPositive
        )

        // ROW 6: Sell Volume
        DataRow(
            label = "Sell Volume ($interval)",
            value = String.format(Locale.US, "%,.2f Vol", regimeState.sellVolume),
            valueColor = CoralNegative
        )

        // ROW 7: Net Delta
        val deltaColor = if (regimeState.netDelta > 0) GreenPositive else if (regimeState.netDelta < 0) CoralNegative else MaterialTheme.colorScheme.onSurface
        DataRow(
            label = "Net Delta",
            value = String.format(Locale.US, "%+,.2f Vol", regimeState.netDelta),
            valueColor = deltaColor
        )

        // ROW 8: Delta %
        DataRow(
            label = "Delta %",
            value = String.format(Locale.US, "%+.2f%%", regimeState.deltaPercent),
            valueColor = deltaColor
        )

        // ROW 9: Dynamic Timeframe Price Delta %
        val priceColor = if (regimeState.priceDeltaPercent > 0) GreenPositive else if (regimeState.priceDeltaPercent < 0) CoralNegative else MaterialTheme.colorScheme.onSurface
        DataRow(
            label = "Price Delta % ($interval)",
            value = String.format(Locale.US, "%+.2f%%", regimeState.priceDeltaPercent),
            valueColor = priceColor
        )

        // --- WYCKOFF V3 DIAGNOSTICS & DEBUG SECTION ---
        if (debug.isDebugEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = GoldNeutral.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🛠 ORDER FLOW & WYCKOFF V3 DIAGNOSTICS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = GoldNeutral)

                // VALIDATION WARNING BANNERS
                debug.warnings.forEach { warning ->
                    Surface(
                        color = GoldNeutral.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = warning,
                            color = GoldNeutral,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                DebugHeader("WYCKOFF EFFORT vs RESULT TRACE")
                DebugRow("Active TF Thresholds", debug.activeThresholdsLabel)
                DebugRow("Effort Ratio (vs 20-bar)", String.format(Locale.US, "%.2fx [%s]", debug.effortRatio, debug.effortLevel))
                DebugRow("Price Efficiency", String.format(Locale.US, "%+.2f%%", debug.priceEfficiency))
                DebugRow("Normalized Flow Efficiency", String.format(Locale.US, "%.4f [%s]", debug.flowEfficiency, debug.efficiencyState))
                DebugRow("Delta % Check", String.format(Locale.US, "Actual %+.2f%%", debug.deltaPercent))
                DebugRow("Price Delta Check", String.format(Locale.US, "Actual %+.2f%%", regimeState.priceDeltaPercent))

                DebugHeader("PERSISTENCE & STREAKS (WINDOW-COMMIT)")
                DebugRow("Current Regime", debug.currentRegime)
                DebugRow("Previous Regime", debug.previousRegime ?: "NONE")
                DebugRow("Persistence Count", "${debug.persistenceCount} [${debug.persistenceState}]")
                DebugRow("Streaks (Abs/Exp/Vac)", "Abs: ${debug.absorptionStreak} | Exp: ${debug.expansionStreak} | Vac: ${debug.vacuumStreak}")
                DebugRow("Defense State", debug.defenseState)
                DebugRow("Regime Scores (Bull/Bear)", "Bull: ${debug.bullishRegimeScore} | Bear: ${debug.bearishRegimeScore}")

                DebugHeader("WS STATUS")
                val stageColor = when (debug.connectionStage) {
                    WsConnectionStage.RECEIVING_MESSAGES -> GreenPositive
                    WsConnectionStage.SUBSCRIBED, WsConnectionStage.SOCKET_OPEN -> GoldNeutral
                    else -> CoralNegative
                }
                DebugRow("Connection Stage", debug.connectionStage.name, stageColor)
                DebugRow("Socket URL", debug.socketUrl)
                DebugRow("Current Stream", debug.currentStream)
                DebugRow("Current Symbol", debug.currentSymbol)
                if (debug.lastError != null) {
                    DebugRow("Last Error", debug.lastError, CoralNegative)
                }

                DebugHeader("STREAM HEALTH")
                DebugRow("Messages Received", "${debug.messagesReceived}")
                DebugRow("Messages/sec", String.format(Locale.US, "%.1f msg/s", debug.messagesPerSecond))
                DebugRow("Reconnect Count", "${debug.reconnectCount}")
                DebugRow("Last Message Time", debug.lastMessageTimestampFormatted)

                DebugHeader("TRADE BUFFER")
                DebugRow("Buffered Trades", "${debug.bufferedTradesCount}")
                DebugRow("Latest Snapshot Time", debug.latestSnapshotTimestampFormatted)
                DebugRow("Latest Snapshot Price", String.format(Locale.US, "$%.5f", debug.latestSnapshotPrice))
                DebugRow("Window Status", debug.windowStatus, if (debug.windowStatus == "READY") GreenPositive else Color.Gray)
            }
        }
    }
}

// ENHANCED: Core 4-Quadrant OI Flow Regime Card (Copilot Mode Feature)
@Composable
fun OiFlowRegimeCard(
    regimeState: OIFlowRegimeState,
    interval: String,
    symbol: String,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleDebug: () -> Unit
) {
    val debug = regimeState.debugState

    MetricCard(
        title = "OI Flow Regime (Live Delta)",
        isFirst = isFirst,
        isLast = isLast,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        headerExtra = {
            Surface(
                onClick = onToggleDebug,
                color = if (debug.isDebugEnabled) MaterialTheme.colorScheme.primaryContainer else Color.Gray.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "[DEBUG]",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (debug.isDebugEnabled) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    ) {
        // ROW 1: Active Regime Badge
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Active Regime", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Surface(
                color = regimeState.badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = regimeState.regime.uppercase(),
                    color = regimeState.badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        // ROW 2: Price Delta (Window)
        val priceColor = when {
            regimeState.buildingWindow || regimeState.priceDeltaPercent == 0.0 -> MaterialTheme.colorScheme.onSurface
            regimeState.priceDeltaPercent > 0.0 -> GreenPositive
            else -> CoralNegative
        }
        val priceSignStr = if (regimeState.priceDeltaPercent > 0.0) "+" else ""
        DataRow(
            label = "Price Delta ($interval)",
            value = if (regimeState.buildingWindow) "--" else String.format(Locale.US, "%s%.2f%%", priceSignStr, regimeState.priceDeltaPercent),
            valueColor = priceColor
        )

        // ROW 3: OI Delta (Contracts)
        val oiColor = when {
            regimeState.buildingWindow || regimeState.oiDeltaContracts == 0.0 -> MaterialTheme.colorScheme.onSurface
            regimeState.oiDeltaContracts > 0.0 -> GreenPositive
            else -> CoralNegative
        }
        val oiSignStr = if (regimeState.oiDeltaContracts > 0.0) "+" else ""
        val oiValueStr = if (regimeState.buildingWindow) "--" else String.format(Locale.US, "%s%,.2f (%s%.2f%%)", oiSignStr, regimeState.oiDeltaContracts, oiSignStr, regimeState.oiDeltaPercent)
        DataRow(
            label = "OI Delta (Contracts)",
            value = oiValueStr,
            valueColor = oiColor
        )

        // ROW 4: Mechanics Subtext
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mechanics", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = regimeState.mechanics,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            )
        }

        // ROW 5: Strength Engine (LOW, MEDIUM, HIGH)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Strength", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = regimeState.strengthColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = regimeState.strength,
                    color = regimeState.strengthColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // ROW 6: Confirmation Engine (CVD CONFIRMED / DIVERGENCE)
        if (!regimeState.buildingWindow && regimeState.confirmation != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Confirmation", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val cvdColor = if (regimeState.confirmation == "CVD CONFIRMED") Color(0xFF10B981) else Color(0xFFF97316)
                Surface(
                    color = cvdColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = regimeState.confirmation,
                        color = cvdColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // ROW 7: Transition Tracking Engine (e.g. SHORT COVERING → NEW LONGS)
        if (!regimeState.buildingWindow && regimeState.previousRegime != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Transition", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = regimeState.previousRegime,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // --- COLLAPSIBLE DIAGNOSTICS / DEBUG SECTION ---
        if (debug.isDebugEnabled) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = GoldNeutral.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("🛠 DIAGNOSTICS / DEBUG MODE", fontSize = 11.sp, fontWeight = FontWeight.Black, color = GoldNeutral)

                // FAILURE DETECTION WARNING BANNERS
                debug.warnings.forEach { warning ->
                    Surface(
                        color = GoldNeutral.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = warning,
                            color = GoldNeutral,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                DebugHeader("SYSTEM STATE")
                DebugRow("Current Symbol", symbol)
                DebugRow("Current Regime", regimeState.regime)
                DebugRow("Building Window", if (regimeState.buildingWindow) "TRUE" else "FALSE")

                DebugHeader("SNAPSHOT BUFFER")
                DebugRow("Snapshot Count", "${debug.snapshotCount}")
                DebugRow("Anchor Age", "${debug.anchorSnapshotAgeSec} sec")
                DebugRow("Oldest Snapshot", "${debug.oldestSnapshotAgeSec} sec")
                DebugRow("Required Window", "${debug.requiredWindowSec} sec")
                DebugRow("Buffer Status", debug.bufferStatus, if (debug.bufferStatus == "READY") GreenPositive else Color.Gray)

                DebugHeader("CURRENT VALUES")
                DebugRow("Current Price", String.format(Locale.US, "$%.5f", debug.currentPrice))
                DebugRow("Anchor Price", String.format(Locale.US, "$%.5f", debug.anchorPrice))
                DebugRow("Current OI", String.format(Locale.US, "%,.0f", debug.currentOi))
                DebugRow("Anchor OI", String.format(Locale.US, "%,.0f", debug.anchorOi))

                DebugHeader("CALCULATED VALUES")
                DebugRow("Price Delta", String.format(Locale.US, "%+.5f", debug.priceDelta))
                DebugRow("Price Delta %", String.format(Locale.US, "%+.2f%%", debug.priceDeltaPercent))
                DebugRow("OI Delta", String.format(Locale.US, "%+,.0f", debug.oiDelta))
                DebugRow("OI Delta %", String.format(Locale.US, "%+.2f%%", debug.oiDeltaPercent))

                DebugHeader("THRESHOLD ENGINE")
                DebugRow("Price Threshold", String.format(Locale.US, "Req 0.15%% | Actual: %.2f%%", abs(debug.priceDeltaPercent)), if (debug.priceThresholdPass) GreenPositive else CoralNegative, if (debug.priceThresholdPass) "PASS" else "FAIL")
                DebugRow("OI Threshold", String.format(Locale.US, "Req 0.25%% | Actual: %.2f%%", abs(debug.oiDeltaPercent)), if (debug.oiThresholdPass) GreenPositive else CoralNegative, if (debug.oiThresholdPass) "PASS" else "FAIL")
                DebugRow("Overall Result", if (debug.overallThresholdPass) "PASS" else "FAIL", if (debug.overallThresholdPass) GreenPositive else CoralNegative)

                DebugHeader("REGIME ENGINE DEBUG")
                DebugRow("Regime Logic", debug.regimeLogicCondition)
                DebugRow("Classification", regimeState.regime, regimeState.badgeColor)

                DebugHeader("STRENGTH ENGINE DEBUG")
                DebugRow("OI Score", "+${debug.oiScore}")
                DebugRow("Price Score", "+${debug.priceScore}")
                DebugRow("Total Score / Strength", "${debug.totalScore} [${regimeState.strength}]", regimeState.strengthColor)

                DebugHeader("CVD CONFIRMED / DIVERGENCE DEBUG")
                DebugRow("Current CVD", String.format(Locale.US, "%+,.0f", debug.currentCvd))
                DebugRow("CVD Direction", debug.cvdDirection)
                DebugRow("Confirmation Result", regimeState.confirmation ?: "NONE")

                DebugHeader("TRANSITION DEBUG")
                DebugRow("Previous Regime", regimeState.previousRegime ?: "NONE")
                DebugRow("Transition Age", if (debug.transitionTimeSecAgo != null) "${debug.transitionTimeSecAgo} sec ago" else "--")

                DebugHeader("POLLING DEBUG")
                DebugRow("Last OI Poll", debug.lastOiPollTimeFormatted)
                DebugRow("Ms Since Poll", "${debug.msSinceOiPoll} ms")
                DebugRow("Last Price Tick", debug.lastPriceTickTimeFormatted)
                DebugRow("Ms Since Price Tick", "${debug.msSincePriceTick} ms")
            }
        }
    }
}

@Composable
fun DebugHeader(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun DebugRow(label: String, value: String, valueColor: Color = Color.Gray, badge: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = valueColor)
            if (badge != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Surface(color = valueColor.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                    Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Black, color = valueColor, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                }
            }
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
                    String.format(Locale.US, "(%.1f%% / %.1f%%)", buyPct, sellPct)
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
                        Text(String.format(Locale.US, "%.4f", ratioVal), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ratioColor)
                    }
                }
            }

            // Row 3: Global Account L/S
            if (latestRetail != null) {
                val globalRatio = latestRetail.longShortRatio.toDoubleOrNull() ?: 0.0
                DataRow("Global Account L/S", String.format(Locale.US, "%.4f", globalRatio))

                // Row 4: Retail Longs
                val retailLongs = (latestRetail.longAccount.toDoubleOrNull() ?: 0.0) * 100.0
                DataRow("Retail Longs", String.format(Locale.US, "%.2f%%", retailLongs), GreenPositive)

                // Row 5: Retail Shorts
                val retailShorts = (latestRetail.shortAccount.toDoubleOrNull() ?: 0.0) * 100.0
                DataRow("Retail Shorts", String.format(Locale.US, "%.2f%%", retailShorts), CoralNegative)
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
    activeMode: ConnectionMode,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val rateText = if (activeMode == ConnectionMode.CELLULAR) "HOTSPOT (1000ms)" else "BROADBAND (100ms)"
    MetricCard(
        title = "Order Book Imbalance [$rateText]",
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

            DataRow("Total Depth Bids", String.format(Locale.US, "%,.2f Vol", imbalance.bidVolume), GreenPositive)
            DataRow("Total Depth Asks", String.format(Locale.US, "%,.2f Vol", imbalance.askVolume), CoralNegative)
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
            Text(text = String.format(Locale.US, "Bids %.2f%%", bidPct), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GreenPositive)
            Text(text = String.format(Locale.US, "Asks %.2f%%", askPct), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CoralNegative)
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
                    Text(String.format(Locale.US, "%.2f", matrix.retailRatio), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(6.dp))
                    SentimentChip(sentiment = matrix.retailSentiment)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Whale Account L/S", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.US, "%.2f", matrix.whaleAccountRatio), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(6.dp))
                    SentimentChip(sentiment = matrix.whaleAccountSentiment)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Whale Position L/S", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(Locale.US, "%.2f", matrix.whalePositionRatio), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
            DataRow("Taker Buy Vol", String.format(Locale.US, "%,.2f", activeCandle.takerBuyVolume))
            DataRow("Taker Sell Vol", String.format(Locale.US, "%,.2f", activeCandle.takerSellVolume))
            val deltaColor = if (activeCandle.volumeDelta > 0) GreenPositive else CoralNegative
            DataRow("Volume Delta", String.format(Locale.US, "%,.2f", activeCandle.volumeDelta), deltaColor)
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
            DataRow("Total OI (Contracts)", String.format(Locale.US, "%,.2f", totalOiStr))
            DataRow("Notional Value ($)", String.format(Locale.US, "$%,.2f", notionalStr))

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
            DataRow("Net Session Aggression", String.format(Locale.US, "%,.2f", currentCvd), cvdColor)

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