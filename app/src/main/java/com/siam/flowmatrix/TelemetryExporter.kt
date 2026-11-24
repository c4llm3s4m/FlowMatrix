package com.siam.flowmatrix

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ExportFormat(val extension: String, val mimeType: String) {
    TXT_REPORT("txt", "text/plain"),
    CSV_TIME_SERIES("csv", "text/csv")
}

object TelemetryExporter {

    private val utcDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val localDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    private val fileTimestampFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun formatPrice(price: Double): String {
        return if (price < 1.0) {
            String.format(Locale.US, "$%.5f", price)
        } else {
            String.format(Locale.US, "$%,.2f", price)
        }
    }

    fun generateFileName(symbol: String, timeframe: String, format: ExportFormat, isFullWatchlist: Boolean = false): String {
        val ts = fileTimestampFormatter.format(Date())
        val nameTag = if (isFullWatchlist) "WATCHLIST_FULL" else "${symbol}_$timeframe"
        return "FLOWMATRIX_${nameTag}_$ts.${format.extension}"
    }

    fun generateSingleSymbolReport(
        session: SymbolSession,
        uiState: DashboardState,
        timeframe: String,
        watchlistTtl: String = "24h 00m"
    ): String {
        val now = Date()
        val symbol = session.symbol
        val activeCandle = session.liveCandle ?: session.klines.lastOrNull()

        val oiState = uiState.oiFlowRegime
        val ofState = uiState.orderFlowRegime
        val fcState = uiState.flowConsensus
        val smpeState = uiState.smpeUiState
        val matrix = uiState.positioningMatrix
        val depth = uiState.orderBookImbalance
        val prem = uiState.premiumIndex ?: session.premiumIndex

        val sb = StringBuilder()
        sb.appendLine("================================================================================")
        sb.appendLine("                      FLOW MATRIX TELEMETRY AI REPORT                          ")
        sb.appendLine("================================================================================")
        sb.appendLine("1. HEADER & CONTEXT")
        sb.appendLine("  Symbol                : $symbol")
        sb.appendLine("  Active Timeframe      : $timeframe")
        sb.appendLine("  Export Timestamp (UTC): ${utcDateFormatter.format(now)}")
        sb.appendLine("  Export Timestamp (Loc): ${localDateFormatter.format(now)}")
        sb.appendLine("  Connection Mode       : ${uiState.activeMode.label}")
        sb.appendLine("  Watchlist TTL         : $watchlistTtl")
        sb.appendLine()

        sb.appendLine("2. PRICE & INITIAL PULSE")
        val liveP = if (session.livePrice > 0.0) session.livePrice else activeCandle?.close ?: 0.0
        sb.appendLine("  Real-Time Live Price  : ${formatPrice(liveP)}")
        if (activeCandle != null) {
            sb.appendLine("  Candle OHLC           : Open ${formatPrice(activeCandle.open)} | High ${formatPrice(activeCandle.high)} | Low ${formatPrice(activeCandle.low)} | Close ${formatPrice(activeCandle.close)}")
            sb.appendLine("  Total Candle Volume   : ${String.format(Locale.US, "%,.2f", activeCandle.totalVolume)}")
            sb.appendLine("  Taker Buy Volume      : ${String.format(Locale.US, "%,.2f", activeCandle.takerBuyVolume)}")
            sb.appendLine("  Taker Sell Volume     : ${String.format(Locale.US, "%,.2f", activeCandle.takerSellVolume)}")
            sb.appendLine("  Candle Volume Delta   : ${String.format(Locale.US, "%+,.2f", activeCandle.volumeDelta)}")
            sb.appendLine("  Price Change %        : ${String.format(Locale.US, "%+.2f%%", activeCandle.priceChangePercent)}")
        }
        sb.appendLine("  Initial Delta Sign    : ${session.initialDeltaSign.label}")
        sb.appendLine()

        sb.appendLine("3. FLOW CONSENSUS & INTENT ENGINE")
        sb.appendLine("  Consensus State       : ${fcState.consensus}")
        sb.appendLine("  Confidence Score      : ${fcState.confidencePct}% (${fcState.confidence})")
        sb.appendLine("  Mechanics Bias        : ${fcState.mechanicsBias}")
        sb.appendLine("  Trade Conviction      : ${fcState.tradeBias}")
        sb.appendLine("  Intent State          : ${fcState.intentState}")
        sb.appendLine("  Intent Description    : ${fcState.intentDescription}")
        sb.appendLine("  Inventory vs Flow     : ${fcState.inventoryVsFlowLabel} [${fcState.inventoryVsFlowBias}]")
        sb.appendLine("  Narrative             : ${fcState.narrative}")
        sb.appendLine("  Last Consensus        : ${fcState.previousConsensus ?: "NONE"} (${fcState.previousConsensusAgeFormatted ?: "--"})")
        sb.appendLine("  Consensus History Log :")
        val hist = synchronized(session) { session.memoryHistoryList.toList() }
        if (hist.isNotEmpty()) {
            hist.forEach { evt ->
                sb.appendLine("    - [${evt.timestampFormatted}] ${evt.consensus}")
            }
        } else {
            sb.appendLine("    - No previous non-neutral events recorded")
        }
        sb.appendLine()

        sb.appendLine("4. SMART MONEY POSITION ENGINE (SMPE)")
        sb.appendLine("  Position Regime       : ${smpeState.regime}")
        sb.appendLine("  Dominance Ratio       : ${String.format(Locale.US, "%.2fx", smpeState.capitalRatio)} [${smpeState.dominance}]")
        sb.appendLine("  Long Capital & Delta  : ${formatCompactUsdt(smpeState.longCapital)} (${String.format(Locale.US, "%+.2f%%", smpeState.longCapitalDeltaPct)})")
        sb.appendLine("  Short Capital & Delta : ${formatCompactUsdt(smpeState.shortCapital)} (${String.format(Locale.US, "%+.2f%%", smpeState.shortCapitalDeltaPct)})")
        sb.appendLine("  Net Inventory         : ${formatCompactUsdt(smpeState.netInventory)}")
        sb.appendLine("  Inventory Momentum    : ${String.format(Locale.US, "%+.2f%%", smpeState.inventoryMomentum)}")
        sb.appendLine("  Position Share (L/S)  : ${String.format(Locale.US, "%.2f%% Long / %.2f%% Short", smpeState.longPositionPct, smpeState.shortPositionPct)}")
        sb.appendLine("  Transition Label      : ${smpeState.transitionLabel ?: "NONE"}")
        sb.appendLine()

        sb.appendLine("5. OI FLOW REGIME (LIVE DELTA)")
        sb.appendLine("  Active OI Regime      : ${oiState.regime}")
        sb.appendLine("  Mechanics             : ${oiState.mechanics}")
        sb.appendLine("  Strength Level        : ${oiState.strength}")
        sb.appendLine("  CVD Confirmation      : ${oiState.confirmation ?: "NONE"}")
        sb.appendLine("  Window Price Delta %  : ${String.format(Locale.US, "%+.2f%%", oiState.priceDeltaPercent)}")
        sb.appendLine("  OI Delta (Contracts)  : ${String.format(Locale.US, "%+,.2f (%+.2f%%)", oiState.oiDeltaContracts, oiState.oiDeltaPercent)}")
        sb.appendLine()

        sb.appendLine("6. ORDER FLOW REGIME (WYCKOFF V3.5)")
        sb.appendLine("  Active Flow Regime    : ${ofState.regime}")
        sb.appendLine("  Effort vs Result      : ${ofState.effortLevel} EFFORT / ${ofState.resultLevel} RESULT (Ratio: ${String.format(Locale.US, "%.2fx", ofState.effortRatio)})")
        sb.appendLine("  Flow Efficiency & St  : ${String.format(Locale.US, "%.4f", ofState.flowEfficiency)} [${ofState.efficiencyState}]")
        sb.appendLine("  Dir Efficiency & Bias : ${String.format(Locale.US, "%+.4f", ofState.directionalEfficiency)} [${ofState.efficiencyBias}]")
        sb.appendLine("  Persistence & Momentum: ${ofState.persistenceState} (${ofState.persistenceCount}w) • ${ofState.regimeMomentum}")
        sb.appendLine("  Defense State         : ${ofState.defenseState}")
        sb.appendLine("  Transition Signal     : ${ofState.transitionSignal} (${ofState.transitionText})")
        sb.appendLine("  Buy Volume            : ${String.format(Locale.US, "%,.2f", ofState.buyVolume)}")
        sb.appendLine("  Sell Volume           : ${String.format(Locale.US, "%,.2f", ofState.sellVolume)}")
        sb.appendLine("  Net Delta             : ${String.format(Locale.US, "%+,.2f (%+.2f%%)", ofState.netDelta, ofState.deltaPercent)}")
        sb.appendLine("  Price Delta %         : ${String.format(Locale.US, "%+.2f%%", ofState.priceDeltaPercent)}")
        sb.appendLine()

        sb.appendLine("7. MULTI-TIER POSITIONING & ORDER BOOK")
        if (matrix != null) {
            sb.appendLine("  Smart Money Sentiment : ${matrix.smartMoneySentiment.label}")
            sb.appendLine("  Retail L/S Ratio      : ${String.format(Locale.US, "%.2f", matrix.retailRatio)} [${matrix.retailSentiment.label}]")
            sb.appendLine("  Whale Account L/S     : ${String.format(Locale.US, "%.2f", matrix.whaleAccountRatio)} [${matrix.whaleAccountSentiment.label}]")
            sb.appendLine("  Whale Position L/S    : ${String.format(Locale.US, "%.2f", matrix.whalePositionRatio)} [${matrix.whalePositionSentiment.label}]")
        }
        if (depth != null) {
            sb.appendLine("  Order Book Imbalance  : ${String.format(Locale.US, "Bids %.2f%% / Asks %.2f%%", depth.bidPercentage, depth.askPercentage)}")
            sb.appendLine("  Total Depth Volume    : ${String.format(Locale.US, "Bids %,.2f / Asks %,.2f", depth.bidVolume, depth.askVolume)}")
        }
        sb.appendLine()

        sb.appendLine("8. FUNDING RATE & MARK/INDEX BASIS")
        if (prem != null) {
            sb.appendLine("  Funding Rate          : ${String.format(Locale.US, "%+.4f%%", prem.fundingRatePct)} (Annualized: ${String.format(Locale.US, "%+.2f%%", prem.annualizedFundingPct)})")
            sb.appendLine("  Crowding Status       : ${prem.crowdingStatus}")
            sb.appendLine("  Mark Price            : ${formatPrice(prem.markPriceDouble)}")
            sb.appendLine("  Index Price           : ${formatPrice(prem.indexPriceDouble)}")
            sb.appendLine("  Mark/Index Basis      : ${String.format(Locale.US, "%+,.2f (%+.4f%%)", prem.markIndexBasis, prem.markIndexBasisPct)}")
            val nextFundingStr = if (prem.nextFundingTime > 0) utcDateFormatter.format(Date(prem.nextFundingTime)) else "--:--:--"
            sb.appendLine("  Next Funding Time     : $nextFundingStr")
        } else {
            sb.appendLine("  Funding Rate Data     : Awaiting Futures API response...")
        }
        sb.appendLine()

        sb.appendLine("================================================================================")
        sb.appendLine("                      EMBEDDED TIME-SERIES CSV APPENDIX                         ")
        sb.appendLine("================================================================================")

        sb.appendLine("--- CLOSED_ORDER_FLOW_WINDOWS_CSV ---")
        sb.appendLine("timestampMs,regime,effortRatio,flowEfficiency,directionalEfficiency,deltaPct,priceDeltaPct")
        val tfBuffer = synchronized(session) {
            session.tfWindowBuffers[timeframe]?.toList()
        }
        if (tfBuffer != null && tfBuffer.isNotEmpty()) {
            tfBuffer.forEach { snap ->
                sb.appendLine("${snap.timestampMs},${snap.regime},${snap.effortRatio},${snap.normalizedEfficiency},${snap.directionalEfficiency},${snap.deltaPct},${snap.priceDeltaPct}")
            }
        } else {
            sb.appendLine("# No closed windows committed for $timeframe yet")
        }
        sb.appendLine()

        sb.appendLine("--- OPEN_INTEREST_HISTORY_CSV ---")
        sb.appendLine("timestampMs,sumOpenInterest,sumOpenInterestValue")
        if (session.openInterest.isNotEmpty()) {
            session.openInterest.takeLast(30).forEach { oi ->
                sb.appendLine("${oi.timestamp},${oi.sumOpenInterest},${oi.sumOpenInterestValue}")
            }
        } else {
            sb.appendLine("# No open interest history loaded")
        }
        sb.appendLine()

        sb.appendLine("--- RECENT_CANDLES_CSV ---")
        sb.appendLine("openTime,open,high,low,close,totalVolume,takerBuyVolume,volumeDelta")
        if (session.klines.isNotEmpty()) {
            session.klines.takeLast(30).forEach { c ->
                sb.appendLine("${c.openTime},${c.open},${c.high},${c.low},${c.close},${c.totalVolume},${c.takerBuyVolume},${c.volumeDelta}")
            }
        } else {
            sb.appendLine("# No klines loaded")
        }

        return sb.toString()
    }

    fun generateFullWatchlistReport(
        sessions: Map<String, SymbolSession>,
        watchlist: List<WatchlistItem>,
        sessionStates: Map<String, DashboardState>,
        timeframe: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("================================================================================")
        sb.appendLine("               FLOW MATRIX FULL WATCHLIST MULTI-ENGINE REPORT                  ")
        sb.appendLine("================================================================================")
        sb.appendLine("Watchlist Size : ${watchlist.size} contracts")
        sb.appendLine("Export Time UTC: ${utcDateFormatter.format(Date())}")
        sb.appendLine()

        watchlist.forEachIndexed { idx, item ->
            val session = sessions[item.symbol]
            val state = sessionStates[item.symbol]
            if (session != null && state != null) {
                sb.appendLine("################################################################################")
                sb.appendLine("CONTRACT #${idx + 1}: ${item.symbol} (TTL Remaining: ${item.formattedTtl})")
                sb.appendLine("################################################################################")
                sb.appendLine(generateSingleSymbolReport(session, state, timeframe, item.formattedTtl))
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    fun generateTabularCsvExport(
        session: SymbolSession,
        uiState: DashboardState,
        timeframe: String
    ): String {
        val sb = StringBuilder()
        val symbol = session.symbol
        sb.appendLine("openTime,symbol,interval,open,high,low,close,totalVolume,takerBuyVolume,takerSellVolume,volumeDelta,priceChangePct,openInterest,notionalOi,fundingRatePct,annualizedFunding,markPrice,indexPrice,markIndexBasisPct,fundingCrowding,consensus,intentState,smpeRegime,orderFlowRegime,directionalEfficiency")

        val latestOi = session.openInterest.lastOrNull()?.sumOpenInterest ?: "0"
        val latestNotionalOi = session.openInterest.lastOrNull()?.sumOpenInterestValue ?: "0"

        val prem = uiState.premiumIndex ?: session.premiumIndex
        val fundingRate = prem?.fundingRatePct ?: 0.0
        val annFunding = prem?.annualizedFundingPct ?: 0.0
        val markP = prem?.markPriceDouble ?: 0.0
        val indexP = prem?.indexPriceDouble ?: 0.0
        val basisPct = prem?.markIndexBasisPct ?: 0.0
        val crowding = prem?.crowdingStatus ?: "NEUTRAL"

        val fc = uiState.flowConsensus
        val smpe = uiState.smpeUiState
        val of = uiState.orderFlowRegime

        val candlesToExport = session.klines.takeLast(50)
        if (candlesToExport.isNotEmpty()) {
            candlesToExport.forEach { c ->
                sb.appendLine(
                    "${c.openTime},$symbol,$timeframe,${c.open},${c.high},${c.low},${c.close},${c.totalVolume},${c.takerBuyVolume},${c.takerSellVolume},${c.volumeDelta},${c.priceChangePercent},$latestOi,$latestNotionalOi,$fundingRate,$annFunding,$markP,$indexP,$basisPct,$crowding,${fc.consensus},${fc.intentState},${smpe.regime},${of.regime},${of.directionalEfficiency}"
                )
            }
        } else {
            val now = System.currentTimeMillis()
            val liveP = if (session.livePrice > 0.0) session.livePrice else 0.0
            sb.appendLine(
                "$now,$symbol,$timeframe,$liveP,$liveP,$liveP,$liveP,0,0,0,0,0,$latestOi,$latestNotionalOi,$fundingRate,$annFunding,$markP,$indexP,$basisPct,$crowding,${fc.consensus},${fc.intentState},${smpe.regime},${of.regime},${of.directionalEfficiency}"
            )
        }

        return sb.toString()
    }

    // Helper to share content via FileProvider and MediaStore export
    fun exportAndShareFile(context: Context, fileName: String, fileContent: String, format: ExportFormat) {
        try {
            // 1. Write to Cache Directory for FileProvider Sharing
            val exportsDir = File(context.cacheDir, "exports")
            if (!exportsDir.exists()) exportsDir.mkdirs()

            val file = File(exportsDir, fileName)
            file.writeText(fileContent)

            // 2. Save directly to Downloads/FlowMatrix/ via MediaStore on Android 10+ (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/FlowMatrix")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { os ->
                        os.write(fileContent.toByteArray())
                    }
                }
            }

            // 3. Launch Native Android Share Sheet via FileProvider
            val contentUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "Flow Matrix Telemetry Export - $fileName")
                putExtra(Intent.EXTRA_TEXT, "Flow Matrix institutional telemetry report attached: $fileName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share Telemetry File via...")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Exception) {}
    }
}