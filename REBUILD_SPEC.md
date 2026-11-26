# Flow Matrix — Clean-Room Rebuild Specification Prompt

> **System Prompt for AI Coding Assistant**: You are an expert Android developer building a native Android cryptocurrency telemetry application named **Flow Matrix** (`com.siam.flowmatrix`) from scratch in **Kotlin**, **Jetpack Compose**, **Material 3**, **StateFlow**, **OkHttp3**, and **Kotlinx Serialization**.
>
> Follow the deterministic architecture, exact mathematical formulas, threshold constants, network mappings, and UI specifications below. Do not inherit or implement any excluded cards or legacy components.

---

## 1. System Architecture & Project Setup

### 1.1 Tech Stack
- **Language**: Kotlin 2.1+ / JVM 11
- **UI Framework**: Jetpack Compose with Material 3 Design System
- **State Management**: Reactive `StateFlow` + `AndroidViewModel` + Repository Pattern
- **Networking**: OkHttp 5.5 + `kotlinx.serialization` (JSON parsing with `ignoreUnknownKeys = true`)
- **Background Keep-Alive**: Foreground Service (`TelemetryForegroundService`) + HyperOS/MIUI battery exemption intents.

### 1.2 Multi-Symbol Session Isolation & Self-Destructing Watchlist
- **Watchlist Capacity**: Hard cap of max **5 active contracts**.
- **Watchlist TTL**: 24-hour self-destructing expiry timer per symbol (`expiresAtMs = createdAtMs + 86,400,000ms`).
- **Concurrent WebSocket Multi-Symbol Ingestion**: All active watchlisted symbols (up to 5) stay subscribed simultaneously on the combined stream (`wss://fstream.binance.com/stream`). Incoming kline (`@kline_<tf>`) and trade (`@trade`) ticks are dispatched via `SharedFlow` to their respective `SymbolSession` buffers in parallel so background sessions never lose live tick accumulation when switching active view contracts.
- **Session Isolation**: Each watchlisted symbol maintains an isolated `SymbolSession` instance containing its own independent ArrayDeque buffers:
  - `snapshotBuffer`: Historical OI and price snapshots (`FlowSnapshot`).
  - `tradeSnapshotBuffer`: Live WS trade ticks (`TradeSnapshot`) retained for up to 1 hour (3,600,000 ms).
  - `smartMoneySnapshotBuffer`: SMPE inventory snapshots (`SmartMoneySnapshot`).
  - `volumeHistoryBuffer`: Rolling 20-window total volume history.
  - `tfWindowBuffers`: Map of `1m`, `5m`, `15m`, `1h` closed `OrderFlowSnapshot` windows.
  - `dirEffBuffers`: Map of directional efficiency histories.
  - `memoryHistoryList`: Rolling 5-event history log of non-neutral consensus transitions.
- **Thread Safety**: All reads and writes to `SymbolSession` buffers must be synchronized via `synchronized(session) { ... }` with defensive snapshotting (`toList()`, `filter()`).
- **Throttled State Emission**: UI `StateFlow` updates are coalesced/throttled to max once per **150ms** (`scheduleThrottledEmission()`) for smooth sub-second price responsiveness.

---

## 2. Binance API & WebSocket Stream Mapping

### 2.1 REST API Endpoints (`BinanceDataFetcher.kt`)
Base URLs: Futures = `https://fapi.binance.com`, Spot Fallback = `https://api.binance.com`
- **Premium Index & Funding**: `GET /fapi/v1/premiumIndex?symbol=<SYMBOL>`
- **Open Interest History**: `GET /futures/data/openInterestHist?symbol=<SYMBOL>&period=<PERIOD>&limit=50`
- **Taker Long/Short Ratio**: `GET /futures/data/takerlongshortRatio?symbol=<SYMBOL>&period=<PERIOD>&limit=50`
- **Global Account Long/Short**: `GET /futures/data/globalLongShortAccountRatio?symbol=<SYMBOL>&period=<PERIOD>&limit=50`
- **Top Trader Account Ratio**: `GET /futures/data/topLongShortAccountRatio?symbol=<SYMBOL>&period=<PERIOD>&limit=50`
- **Top Trader Position Ratio**: `GET /futures/data/topLongShortPositionRatio?symbol=<SYMBOL>&period=<PERIOD>&limit=50`
- **Futures Klines**: `GET /fapi/v1/klines?symbol=<SYMBOL>&interval=<INTERVAL>&limit=50`
- **Spot Klines Fallback**: `GET /api/v3/klines?symbol=<SYMBOL>&interval=<INTERVAL>&limit=50`

### 2.2 Combined Multi-Stream WebSocket (`BinanceWebSocketManager.kt`)
- **WebSocket URL**: `wss://fstream.binance.com/stream`
- **Subscribed Streams Per Symbol**:
  - `<symbol>@kline_<interval>` (e.g. `btcusdt@kline_5m`)
  - `<symbol>@trade`
- **Auto-Resubscribe**: When active symbol or timeframe changes, send JSON `SUBSCRIBE` / `UNSUBSCRIBE` payloads over the existing open socket rather than re-creating the socket connection.

### 2.3 Polling Schedules
- **REST Background Loop**: Staggered polling per symbol every **30 seconds**.
- **Fast Recovery Polling**: If REST data is missing (`openInterest.isEmpty()` or `klines.isEmpty()`), retry polling every **4 seconds** up to 6 times.

---

## 3. Scope Specification: The 9 Included Telemetry Cards

### 3.1 Excluded Cards (STRICT PROHIBITION)
Do **NOT** implement or reference:
1. *Initial Candle Volume Delta*
2. *Order Book Imbalance*
3. *Local Aggression*

---

### 3.2 Card 1: Multi-Tier Positioning & Smart Money
- **Data Inputs**: `GlobalLongShortRatioResponse` (`globalLongShortAccountRatio`), `TopTraderLongShortRatioResponse` (`topLongShortAccountRatio`), `TopTraderLongShortRatioResponse` (`topLongShortPositionRatio`).
- **Classification Rules (`classifyRatio(r)`)**:
  - `r >= 2.20`: `EXTREMELY_BULLISH`
  - `r >= 1.15`: `BULLISH`
  - `r <= 0.65`: `EXTREMELY_BEARISH`
  - `r <= 0.90`: `BEARISH`
  - otherwise: `NEUTRAL`
- **Smart Money Sentiment (`smartMoneySentiment`)**:
  - `retailRatio >= 1.50 && whalePositionRatio < retailRatio`: `EXTREMELY_BEARISH` (Retail crowded Long while Whales distribute)
  - `retailRatio <= 0.75 && whalePositionRatio > retailRatio`: `EXTREMELY_BULLISH` (Retail crowded Short while Whales accumulate)
  - `whalePositionRatio >= 1.40`: `BULLISH`
  - `whalePositionRatio <= 0.85`: `BEARISH`
  - otherwise: `NEUTRAL`

---

### 3.3 Card 2: Positioning Ratio
- **Data Inputs**: Latest `TakerLongShortRatioResponse` (`takerlongshortRatio`) and `GlobalLongShortRatioResponse` (`globalLongShortAccountRatio`).
- **Metrics Displayed**:
  - Smart Money Sentiment chip (`smartMoneySentiment`)
  - Taker Buy/Sell Volume split: `buyPct = (buyVol / totalVol) * 100`, `sellPct = (sellVol / totalVol) * 100`, ratio = `buySellRatio`.
  - Global Account L/S ratio + Retail Long % (`longAccount * 100`) and Retail Short % (`shortAccount * 100`).

---

### 3.4 Card 3: Smart Money Position Engine (SMPE)
- **Data Source for Positioning Ratios**: `longAccount` and `shortAccount` ratios come strictly from `session.topTraderPosition` (`GET /futures/data/topLongShortPositionRatio`).
- **Mathematical Inventory Calculations**:
  - `totalNotionalOI = sumOpenInterestValue ?: (sumOpenInterest * currentPrice)`
  - `longCapital = totalNotionalOI * longAccountPct`
  - `shortCapital = totalNotionalOI * shortAccountPct`
  - `capitalRatio = if (shortCapital > 0) longCapital / shortCapital else 0.0`
  - `netInventory = longCapital - shortCapital`
  - `longCapitalDeltaPct = ((longCapital - baselineLongCapital) / baselineLongCapital) * 100`
  - `shortCapitalDeltaPct = ((shortCapital - baselineShortCapital) / baselineShortCapital) * 100`
  - `inventoryMomentum = longCapitalDeltaPct - shortCapitalDeltaPct`
  - `positioningSpread = longPositionPct - shortPositionPct`
  - `smpePriceDeltaPct = ((currentPrice - baselinePrice) / baselinePrice) * 100`
- **Baseline Lookback Mechanics**:
  - `baselineLongCapital`, `baselineShortCapital`, and `baselinePrice` are extracted by finding the most recent historical snapshot in `session.smartMoneySnapshotBuffer` where `snapshot.longPositionPct != latest.longPositionPct || snapshot.shortPositionPct != latest.shortPositionPct` (i.e. the previous distinct position ratio update). If no distinct snapshot exists, falls back to `smartMoneySnapshotBuffer.firstOrNull()` or `latest`.
- **Dominance Classification**:
  - `capitalRatio < 0.70`: `"BEAR DOMINANCE"` (`#EF4444`)
  - `0.70..1.30`: `"BALANCED"` (`#9CA3AF`)
  - `1.30..3.00`: `"BULL DOMINANCE"` (`#10B981`)
  - `3.00..5.00`: `"STRONG BULL DOMINANCE"` (`#00C853`)
  - `5.00..10.00`: `"SPONSORED RALLY"` (`#22C55E`)
  - `> 10.00`: `"EXTREME SPONSORSHIP"` (`#00E676`)
- **Regime Rules**:
  - `SPONSORED_RALLY`: `longCapitalDeltaPct >= 0.20% && shortCapitalDeltaPct <= -0.10% && smpePriceDeltaPct > 0.10%` (`#10B981`)
  - `ACCUMULATION`: `longCapitalDeltaPct >= 0.20% && smpePriceDeltaPct in -0.25%..0.75%` (`#3B82F6`)
  - `ABANDONMENT`: `longCapitalDeltaPct <= -0.60% && (shortCapitalDeltaPct >= 0.20% || smpePriceDeltaPct < -0.20%)` (`#EF4444`)
  - `DISTRIBUTION`: `longCapitalDeltaPct <= -0.20% && smpePriceDeltaPct in -0.25%..0.50%` (`#F97316`)
  - `NEUTRAL`: otherwise (`#9CA3AF`)

---

### 3.5 Card 4: Open Interest (Live)
- **Data Inputs**: Latest `OpenInterestHistResponse` array (`/futures/data/openInterestHist`).
- **Metrics Displayed**: Total OI in Contracts, Notional Value ($), and 50-period historical Sparkline chart (`SparklineChart`).

---

### 3.6 Card 5: Funding Rate & Mark/Index Basis
- **Data Inputs**: `PremiumIndexResponse` (`/fapi/v1/premiumIndex`).
- **Formulas**:
  - `fundingRatePct = (lastFundingRate.toDouble() ?: 0.0) * 100`
  - `annualizedFundingPct = fundingRatePct * 3.0 * 365.0`
  - `markIndexBasis = markPriceDouble - indexPriceDouble`
  - `markIndexBasisPct = if (indexPriceDouble > 0) ((markPriceDouble - indexPriceDouble) / indexPriceDouble) * 100 else 0.0`
- **Crowding Status**:
  - `fundingRatePct >= 0.03%`: `"CROWDED LONGS (ELEVATED +FUNDING)"`
  - `fundingRatePct <= -0.03%`: `"CROWDED SHORTS (ELEVATED -FUNDING)"`
  - `fundingRatePct > 0.01%`: `"MILD LONG BIAS"`
  - `fundingRatePct < -0.01%`: `"MILD SHORT BIAS"`
  - otherwise: `"NEUTRAL FUNDING"`
- **Countdown Display**: Live countdown timer until `nextFundingTime` (`02h 45m 12s`).

---

### 3.7 Card 6: OI Flow Regime (Live Delta)
- **Anchor Snapshot**: Finds historical `FlowSnapshot` in `snapshotBuffer` where `(now - snapshotTimestampMs) >= timeframeDurationMs`.
- **Significance Filter**: Requires `abs(priceDeltaPercent) >= 0.15% && abs(oiDeltaPercent) >= 0.25%`.
- **4-Quadrant Classification Rules**:
  - `NEW LONGS`: `deltaOI > 0 && deltaPrice > 0` (`#10B981`, `"Fresh capital entering Longs (Buy-to-Open)"`)
  - `NEW SHORTS`: `deltaOI > 0 && deltaPrice < 0` (`#EF4444`, `"Fresh capital entering Shorts (Sell-to-Open)"`)
  - `SHORT COVERING`: `deltaOI < 0 && deltaPrice > 0` (`#EAB308`, `"Shorts buying back positions (Buy-to-Close)"`)
  - `LONG LIQUIDATIONS`: `deltaOI < 0 && deltaPrice < 0` (`#F97316`, `"Longs exiting positions (Sell-to-Close)"`)
  - `NEUTRAL`: below filter thresholds (`#9CA3AF`)
- **Strength Engine**:
  - Below significance filter: `LOW`
  - `abs(oiDeltaPercent) >= 1.0%`: `HIGH` (`#10B981`)
  - otherwise: `MEDIUM` (`#EAB308`)
- **CVD Confirmation**:
  - `NEW LONGS` + `cvd > 0`: `"CVD CONFIRMED"`
  - `NEW SHORTS` + `cvd < 0`: `"CVD CONFIRMED"`
  - `NEW LONGS` + `cvd < 0` or `NEW SHORTS` + `cvd > 0`: `"DIVERGENCE"`

---

### 3.8 Card 7: Order Flow Regime (Wyckoff V3.5)
- **Volume Source**: `buyVol` and `sellVol` are calculated by aggregating live WebSocket `@trade` ticks in `session.tradeSnapshotBuffer` over the active timeframe window duration (`getTimeframeDurationMs(activeTf)`).
  - Taker Sell Volume: sum of `trade.quantity` where `trade.isBuyerMaker == true`.
  - Taker Buy Volume: sum of `trade.quantity` where `trade.isBuyerMaker == false`.
- **Timeframe Scaling Constants (`TimeframeThresholds`)**:
  - `1m`: Abs max `<= 0.03%`, Vac min `>= 0.20%`, Delta Trigger `15.0%`, Scale Factor `1.0`
  - `5m`: Abs max `<= 0.07%`, Vac min `>= 0.45%`, Delta Trigger `14.0%`, Scale Factor `2.2`
  - `15m`: Abs max `<= 0.12%`, Vac min `>= 0.75%`, Delta Trigger `12.0%`, Scale Factor `3.8`
  - `1h`: Abs max `<= 0.22%`, Vac min `>= 1.50%`, Delta Trigger `10.0%`, Scale Factor `7.5`
- **Wyckoff Mechanics Formulas**:
  - `totalVol = buyVol + sellVol`
  - `netDelta = buyVol - sellVol`
  - `deltaPct = (netDelta / totalVol) * 100`
  - `priceDeltaPct = ((lastPrice - anchorPrice) / anchorPrice) * 100`
  - `effortRatio = totalVol / avgWindowVol` (rolling 20-window average)
  - `normalizedPriceDelta = abs(priceDeltaPct) / tfScaleFactor`
  - `flowEfficiency = normalizedPriceDelta / max(abs(deltaPct), 1.0)`
  - `directionalEfficiency = (priceDeltaPct * deltaSign) / (tfScaleFactor * max(abs(deltaPct), 1.0))`
- **Flow Efficiency Label Thresholds**:
  - `flowEfficiency < 0.02`: `"ABSORPTION"`
  - `flowEfficiency <= 0.06`: `"NORMAL_AUCTION"`
  - `flowEfficiency <= 0.12`: `"INITIATIVE_MOVE"`
  - otherwise: `"LIQUIDITY_VACUUM"`
- **Strict Top-to-Bottom Evaluation Order**:
  Card 7 regimes must evaluate in strict `when` precedence order so Traps evaluate before Absorption:
  1. `LONG TRAP`: `deltaPct >= trigger && priceDeltaPct <= -absMax` (`#FF5252`)
  2. `SHORT TRAP`: `deltaPct <= -trigger && priceDeltaPct >= absMax` (`#00C853`)
  3. `ASK ABSORPTION`: `deltaPct >= trigger && priceDeltaPct <= absMax` (`#FF9100`)
  4. `BID ABSORPTION`: `deltaPct <= -trigger && priceDeltaPct >= -absMax` (`#00E5FF`)
  5. `EXPANSIVE BUYING`: `deltaPct >= trigger && priceDeltaPct > absMax` (`#00C853`)
  6. `EXPANSIVE SELLING`: `deltaPct <= -trigger && priceDeltaPct < absMax` (`#FF5252`)
  7. `THIN-ASK DRIFT`: `abs(deltaPct) < trigger && priceDeltaPct >= vacMin` (`#66BB6A`)
  8. `THIN-BID DROP`: `abs(deltaPct) < trigger && priceDeltaPct <= -vacMin` (`#EF5350`)
  9. `BALANCED FLOW`: otherwise (`#9CA3AF`)
- **Transition Signal Mechanics**:
  When a newly committed non-balanced window differs from `lastNonBal`:
  - `lastNonBal == "ASK ABSORPTION" && regimeStr == "THIN-ASK DRIFT"` -> `"BREAKOUT_RELEASE"` ("Passive overhead supply exhausted")
  - `lastNonBal == "BID ABSORPTION" && regimeStr == "THIN-BID DROP"` -> `"SUPPORT_FAILURE"` ("Passive bid support withdrawn")
  - `lastNonBal == "SHORT TRAP" && regimeStr == "EXPANSIVE BUYING"` -> `"SQUEEZE_IGNITION"` ("Trapped shorts fueling initiative buying")
  - `lastNonBal == "LONG TRAP" && regimeStr == "EXPANSIVE SELLING"` -> `"CASCADE_IGNITION"` ("Trapped longs fueling cascading supply")
  - Hold transition signal for 2 consecutive window commits before resetting to `"NONE"`.
- **Regime Momentum**:
  - Calculated from 3-window slopes: `effSlope = dirEffHist.last() - dirEffHist[last - 3]`, `volSlope = effortRatio.last() - effortRatio[last - 3]`
  - `EXHAUSTING ⚠`: persistence count `>= 6` && `effSlope < -0.005`
  - `STRENGTHENING ↑`: `effSlope > 0.005 || (effSlope >= -0.005 && volSlope > 0.10)`
  - `WEAKENING ↓`: `effSlope < -0.005 && volSlope <= 0.0`
  - `STABLE →`: otherwise
- **Defense State Engine**:
  - `ACTIVE_BID_DEFENSE`: last 4 committed windows contain `>= 3` occurrences of `BID ABSORPTION` or `SHORT TRAP`
  - `ACTIVE_ASK_DEFENSE`: last 4 committed windows contain `>= 3` occurrences of `ASK ABSORPTION` or `LONG TRAP`
  - `NONE`: otherwise

---

### 3.9 Card 8: Flow Consensus Engine
- **Inventory vs Flow Conflict Detector**:
  - `flowSelling && inventoryIncreasing` -> `"PROFIT TAKING INSIDE BULLISH CAMPAIGN"` [BULLISH]
  - `flowBuying && inventoryDecreasing` -> `"BUYING INTO DISTRIBUTION"` [BEARISH]
  - `flowBuying && inventoryIncreasing` -> `"CONFIRMED EXPANSION"` [STRONG BULLISH]
  - `flowSelling && inventoryDecreasing` -> `"CONFIRMED DISTRIBUTION"` [STRONG BEARISH]
  - otherwise -> `"ALIGNED / BALANCED"` [NEUTRAL]
- **Intent Engine**:
  - `(THIN-ASK DRIFT || EXPANSIVE BUYING) && SHORT COVERING` -> `"SHORT SQUEEZE"` (`#22C55E`)
  - `(THIN-BID DROP || EXPANSIVE SELLING) && LONG LIQUIDATIONS` -> `"LONG LIQUIDATION"` (`#EF4444`)
  - `(ASK ABSORPTION && NEW LONGS) || LONG TRAP` -> `"BULL TRAP FORMING"` (`#FF9100`)
  - `(BID ABSORPTION && NEW SHORTS) || SHORT TRAP` -> `"BEAR TRAP FORMING"` (`#00E5FF`)
  - `EXPANSIVE BUYING && NEW LONGS` -> `"INITIATIVE BUYING"` (`#00C853`)
  - `EXPANSIVE SELLING && NEW SHORTS` -> `"INITIATIVE SHORTING"` (`#FF5252`)
  - otherwise -> `"NEUTRAL AUCTION"` (`#9CA3AF`)
- **Consensus Synthesis Rules**:
  - `SMPE_OVERRIDE`: `smpe == SPONSORED_RALLY && (oi == NEW LONGS || SHORT COVERING) && (of == EXPANSIVE SELLING || ofState.netDelta < 0.0) && abs(deltaPct) < 25.0` -> `"SMART MONEY DOMINANCE"` (`#10B981`)
  - `BID_ABSORPTION + NEW_SHORTS`: -> `"BULLISH ABSORPTION TRAP"` (`#00C853`)
  - `ASK_ABSORPTION + (NEW_LONGS || DISTRIBUTION)`: -> `"BEARISH DISTRIBUTION WALL"` (`#FF9100`)
  - `NEW LONGS + BUY FLOW`: -> `"STRONG BULLISH CONTINUATION"` (`#10B981`)
  - `NEW SHORTS + SELL FLOW`: -> `"STRONG BEARISH CONTINUATION"` (`#EF4444`)
  - `NEW SHORTS + BUY FLOW`: -> `"SHORT TRAP"` (`#F59E0B`)
  - `NEW LONGS + SELL FLOW`: -> `"LONG TRAP"` (`#F97316`)
  - `LONG LIQUIDATIONS + BUY FLOW`: -> `"CAPITULATION REVERSAL"` (`#EAB308`)
  - `SHORT COVERING + BUY FLOW`: -> `"SHORT SQUEEZE"` (`#22C55E`)
  - `NEW LONGS + BALANCED FLOW + priceDeltaPct < 0`: -> `"DISTRIBUTION"` (`#F97316`)
  - `NEW SHORTS + BALANCED FLOW + priceDeltaPct > 0`: -> `"ACCUMULATION"` (`#3B82F6`)
  - otherwise -> `"NEUTRAL"` (`#9CA3AF`)
- **Exact Mechanics Bias Mapping Rules (`mechanicsBias`)**:
  - Maps to `"BULLISH"` if: `intentKey` in (`SHORT SQUEEZE`, `BEAR TRAP FORMING`, `INITIATIVE BUYING`) OR `consensus` in (`STRONG BULLISH CONTINUATION`, `SHORT SQUEEZE`, `CAPITULATION REVERSAL`, `SHORT TRAP`, `BULLISH ABSORPTION TRAP`, `ACCUMULATION`, `SMART MONEY DOMINANCE`) OR `flowBuying` is true.
  - Maps to `"BEARISH"` if: `intentKey` in (`LONG LIQUIDATION`, `BULL TRAP FORMING`, `INITIATIVE SHORTING`) OR `consensus` in (`STRONG BEARISH CONTINUATION`, `LONG LIQUIDATION CASCADE`, `LONG TRAP`, `BEARISH DISTRIBUTION WALL`, `DISTRIBUTION`) OR `flowSelling` is true.
  - otherwise -> `"NEUTRAL"`
- **Exact Trade Conviction Mapping Rules (`tradeConviction`)**:
  - Maps to `"LOW"` if: `invVsFlowLabel` in (`BUYING INTO DISTRIBUTION`, `SELLING INTO ACCUMULATION`) OR `confidencePct < 50`.
  - Maps to `"HIGH"` if: `confidencePct >= 75` AND (`mechanicsBias == BULLISH` and SMPE is in `SPONSORED_RALLY`/`ACCUMULATION`/`NEUTRAL` OR `mechanicsBias == BEARISH` and SMPE is in `DISTRIBUTION`/`ABANDONMENT`/`NEUTRAL`) AND `regimeMomentum` does NOT contain `"EXHAUSTING"`.
  - otherwise -> `"MEDIUM"`
- **0–100% Unified Confidence Score Breakdown**:
  - `smpeScore`: up to 30 pts (30 if aligned with mechanics bias, 15 if unaligned, 5 if conflict)
  - `oiScore`: up to 25 pts (25 if directional, 12 if closing, 10 if neutral)
  - `ofScore`: up to 20 pts (20 if expansive/trap, 18 if absorption/defense, 14 if vacuum, 10 neutral)
  - `momentumScore`: up to 15 pts (15 if STRENGTHENING, 8 STABLE, 3 WEAKENING, 0 EXHAUSTING)
  - `persistenceScore`: up to 10 pts (10 if >= 6, 8 if 4..5, 5 if 2..3, 2 if 1)
  - `Total = (smpe + oi + of + momentum + persistence).coerceIn(0, 100)`
  - Confidence label: `>= 75` -> `"HIGH"` (`#10B981`), `>= 45` -> `"MEDIUM"` (`#EAB308`), else `"LOW"` (`#9CA3AF`).

---

### 3.10 Card 9: Cumulative Volume Delta (CVD)
- **Formula**:
  - `allKlines = candles.dropLast(1) + (activeCandle ?: emptyList)`
  - Running cumulative sum: `cumulative += (takerBuyVolume - takerSellVolume)`
- **Chart**: Rendered using `SparklineChart` with vertical gradient fill (Green if `cvd.last() >= cvd.first()`, Coral Red if trending down).

---

## 4. Local Preferences & Settings Modal Specification

### 4.1 Persistence (`CardOrderPreferences`)
Stored via `SharedPreferences` (`"flow_matrix_card_prefs"`):
- `"card_order_v1"`: String of ordered card IDs separated by commas.
- `"hidden_cards_v1"`: String of hidden card IDs separated by commas.
- `"connection_mode_v1"`: Saved connection mode ID (`"AUTO"`, `"BROADBAND"`, `"CELLULAR"`).
- `"watchlist_v1"`: Watchlist items serialized as `symbol|createdAtMs|expiresAtMs;...`.

### 4.2 Settings Modal (`SettingsDialog`) Contents
- **Network Connection Mode**: Radio button selection for `AUTO` (`🌐 AUTO DETECT`), `BROADBAND` (`📶 BROADBAND (100ms)`), `CELLULAR` (`📱 HOTSPOT / CELLULAR (1000ms)`).
- **HyperOS & Background Protection**:
  - Keep-Alive Exemption Status Indicator + `"EXEMPT"` button (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
  - `"⚡ HYPEROS AUTOSTART & PERMISSIONS"` button launching MIUIsecuritycenter settings or application details.
- **Quick Actions**:
  - `"↻ REFRESH DATA & RECONNECT"`: Reconnects WebSocket & polls REST endpoints for all symbols.
  - `"⚙ RESET CARD LAYOUT"`: Resets card order and card visibility to defaults.

---

## 5. Telemetry Exporter Schema (`TelemetryExporter.kt`)

### 5.1 AI Text Report (`.txt`) Structure
```text
================================================================================
                      FLOW MATRIX TELEMETRY AI REPORT                          
================================================================================
1. HEADER & CONTEXT
  Symbol                : BTCUSDT
  Active Timeframe      : 5m
  Export Timestamp (UTC): 2026-03-30 14:00:00 UTC
  Export Timestamp (Loc): 2026-03-30 10:00:00 EDT
  Connection Mode       : 🌐 AUTO DETECT
  Watchlist TTL         : 23h 45m

2. PRICE & REAL-TIME SUMMARY
  Real-Time Live Price  : $68,450.20
  Candle OHLC           : Open $68,100.00 | High $68,600.00 | Low $68,050.00 | Close $68,450.20
  Total Candle Volume   : 1,245.50
  Taker Buy Volume      : 750.20
  Taker Sell Volume     : 495.30
  Candle Volume Delta   : +254.90
  Price Change %        : +0.51%

3. FLOW CONSENSUS & INTENT ENGINE
  Consensus State       : STRONG BULLISH CONTINUATION
  Confidence Score      : 88% (HIGH)
  Mechanics Bias        : BULLISH
  Trade Conviction      : HIGH
  Intent State          : INITIATIVE BUYING
  Intent Description    : New longs driving directional expansion.
  Inventory vs Flow     : CONFIRMED EXPANSION [STRONG BULLISH]
  Narrative             : Fresh longs entering while buyers control short-term order flow.
  Last Consensus        : ACCUMULATION (12 min ago)
  Consensus History Log :
    - [13:48:00] ACCUMULATION
    - [13:30:00] NEUTRAL

4. SMART MONEY POSITION ENGINE (SMPE)
  Position Regime       : ACCUMULATION
  Dominance Ratio       : 2.15x [BULL DOMINANCE]
  Long Capital & Delta  : $420.50M (+1.25%)
  Short Capital & Delta : $195.58M (-0.85%)
  Net Inventory         : $224.92M
  Inventory Momentum    : +2.10%
  Position Share (L/S)  : 68.25% Long / 31.75% Short
  Transition Label      : NONE

5. OI FLOW REGIME (LIVE DELTA)
  Active OI Regime      : NEW LONGS
  Mechanics             : Fresh capital entering Longs (Buy-to-Open)
  Strength Level        : HIGH
  CVD Confirmation      : CVD CONFIRMED
  Window Price Delta %  : +0.51%
  OI Delta (Contracts)  : +1,240.00 (+1.15%)

6. ORDER FLOW REGIME (WYCKOFF V3.5)
  Active Flow Regime    : EXPANSIVE BUYING
  Effort vs Result      : HIGH EFFORT / EXPANSIVE RESULT (Ratio: 1.45x)
  Flow Efficiency & St  : 0.1450 [LIQUIDITY_VACUUM]
  Dir Efficiency & Bias : +0.1250 [ALIGNED]
  Persistence & Momentum: ESTABLISHED (4w) • STRENGTHENING ↑
  Defense State         : NONE
  Transition Signal     : NONE (NONE)
  Buy Volume            : 750.20
  Sell Volume           : 495.30
  Net Delta             : +254.90 (+20.47%)
  Price Delta %         : +0.51%

7. MULTI-TIER POSITIONING
  Smart Money Sentiment : Bullish
  Retail L/S Ratio      : 0.85 [Bearish]
  Whale Account L/S     : 1.65 [Bullish]
  Whale Position L/S    : 2.15 [Bullish]

8. FUNDING RATE & MARK/INDEX BASIS
  Funding Rate          : +0.0100% (Annualized: +10.95%)
  Crowding Status       : MILD LONG BIAS
  Mark Price            : $68,452.10
  Index Price           : $68,448.50
  Mark/Index Basis      : +3.60 (+0.0053%)
  Next Funding Time     : 2026-03-30 16:00:00 UTC

================================================================================
                      EMBEDDED TIME-SERIES CSV APPENDIX                         
================================================================================
--- CLOSED_ORDER_FLOW_WINDOWS_CSV ---
timestampMs,regime,effortRatio,flowEfficiency,directionalEfficiency,deltaPct,priceDeltaPct
...

--- OPEN_INTEREST_HISTORY_CSV ---
timestampMs,sumOpenInterest,sumOpenInterestValue
...

--- RECENT_CANDLES_CSV ---
openTime,open,high,low,close,totalVolume,takerBuyVolume,volumeDelta
...
```

### 5.2 Time-Series CSV Export Schema
`openTime,symbol,interval,open,high,low,close,totalVolume,takerBuyVolume,takerSellVolume,volumeDelta,priceChangePct,openInterest,notionalOi,fundingRatePct,annualizedFunding,markPrice,indexPrice,markIndexBasisPct,fundingCrowding,consensus,intentState,smpeRegime,orderFlowRegime,directionalEfficiency`

---

## 6. UI Theme & Visual Styling Specification

### 6.1 Color Palette
- **Primary Positive / Bullish**: `#00C853` (`GreenPositive`)
- **Primary Negative / Bearish**: `#FF5252` (`CoralNegative`)
- **Warning / Neutral / Pending**: `#FFB300` (`GoldNeutral`)
- **Accent / Info Blue**: `#3B82F6`
- **Cyan / Absorption**: `#00E5FF`
- **Card Background**: `#22242A` (`surfaceVariant`)
- **App Background**: `#121316`

### 6.2 Sub-$1 Price Precision Rule
All price fields (`livePrice`, mark price, index price, candle OHLC) must format using:
```kotlin
fun formatPrice(price: Double): String {
    return if (price < 1.0) {
        String.format(Locale.US, "$%.5f", price)
    } else {
        String.format(Locale.US, "$%,.2f", price)
    }
}
```

### 6.3 Top Bar & Dialog Modals
- **Top Bar**: App Title ("FLOW MATRIX"), WebSocket Status Badge (`🟢 LIVE (Futures WS)`), Export (`📤 EXPORT`), Manage Cards (`👁 CARDS`), Settings (`⚙`).
- **Watchlist Bar**: Scrollable chip bar showing active symbols, TTL countdowns (`23h 45m`), add symbol (+) modal, remove (✕).
- **Card Controls**: ▲ / ▼ reorder buttons on every card header; `CardVisibilityDialog` modal for toggles.
