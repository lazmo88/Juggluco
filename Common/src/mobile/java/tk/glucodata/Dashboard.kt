/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */

package tk.glucodata

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.patrykandpatrick.vico.views.cartesian.CartesianChart
import com.patrykandpatrick.vico.views.cartesian.CartesianChartView
import com.patrykandpatrick.vico.views.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.views.cartesian.ScrollHandler
import com.patrykandpatrick.vico.views.cartesian.Zoom
import com.patrykandpatrick.vico.views.cartesian.ZoomHandler
import com.patrykandpatrick.vico.views.cartesian.axis.Axis
import com.patrykandpatrick.vico.views.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.views.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.views.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.views.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.views.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.views.cartesian.data.lineSeries
import com.patrykandpatrick.vico.views.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.views.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.views.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.views.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.views.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.views.common.Fill
import com.patrykandpatrick.vico.views.common.Insets
import com.patrykandpatrick.vico.views.common.component.LineComponent
import com.patrykandpatrick.vico.views.common.component.ShapeComponent
import com.patrykandpatrick.vico.views.common.component.TextComponent
import com.patrykandpatrick.vico.views.common.data.ExtraStore
import com.patrykandpatrick.vico.views.common.shape.CorneredShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.settings.Settings
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phone home screen in portrait: the latest reading with its trend, a chart of the
 * last hours and a 14-day summary (time in ranges, average, GMI, variability).
 *
 * It lies on top of the native graph (GlucoseCurve), which stays the detailed view:
 * turn the phone, tap "Full graph", or pick a graph action in the menu. Back
 * returns to the dashboard. Values come from Natives.dashboardValues(), which uses
 * the same sensor ranges and calibration as Juggluco's statistics.
 */
class Dashboard private constructor(private val act: MainActivity) {

    /** Root view; the menu button treats it like the graph, not like a screen on top. */
    class Root(context: Context, private val owner: Dashboard) : FrameLayout(context) {
        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            owner.updateVisibility()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            owner.updateVisibility()
        }

        override fun onDetachedFromWindow() {
            owner.stop()
            super.onDetachedFromWindow()
        }
    }

    private class Readings(val time: LongArray, val mgdl: FloatArray) {
        val size get() = time.size
    }

    private class Summary(
        val spanSec: Long,
        val bands: DoubleArray, // very low, low, in range, high, very high (fractions)
        val meanMgdl: Double,
        val cv: Double,
        val inTarget: Double,
        val coverage: Double,
    )

    private class Data(
        val now: Long,
        val start: Long,
        val hours: Int,
        val recent: Readings,
        val summary: Summary?,
        val last: strGlucose?,
    )

    private val ctx: Context = Menus.themed(act)
    val root = Root(ctx, this)
    private val dark = Natives.getInvertColors()
    private val scope: CoroutineScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences("dashboard", Context.MODE_PRIVATE)
    private val modelProducer = CartesianChartModelProducer()
    private var job: Job? = null
    private var loadedGlucoseTime = Long.MIN_VALUE
    private var loadedAt = 0L
    private var loadedHours = 0
    private var latestTime = 0L

    private var hours = prefs.getInt(PREF_HOURS, 6).let { if (it in HOUR_CHOICES) it else 6 }
    private var mmol = true
    private var targetLow = 70f
    private var targetHigh = 180f
    private var minY = 0.0
    private var maxY = 1.0
    private var yStep = 2.0
    private var chartStart = 0L
    private var labelOffset = 0
    private var labelSpacing = 60
    private var dotColor = 0

    // Colours: AGP-style range colours, lighter in dark mode.
    private val cVeryHigh = if (dark) 0xFFFFB74D.toInt() else 0xFFEF6C00.toInt()
    private val cHigh = if (dark) 0xFFFFE082.toInt() else 0xFFF9A825.toInt()
    private val cInRange = if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt()
    private val cLow = if (dark) 0xFFEF9A9A.toInt() else 0xFFD32F2F.toInt()
    private val cVeryLow = if (dark) 0xFFFF5252.toInt() else 0xFF8E0000.toInt()
    private val cOnSurface = color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    private val cOnSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
    private val cOutlineVariant = color(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
    private val cSurface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val cSurfaceHighest = color(com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.LTGRAY)

    private val content: View = LayoutInflater.from(ctx).inflate(R.layout.dashboard, root, false)
    private val vStatus: TextView = content.findViewById(R.id.dash_status)
    private val vValue: TextView = content.findViewById(R.id.dash_value)
    private val vUnit: TextView = content.findViewById(R.id.dash_unit)
    private val vArrow: ImageView = content.findViewById(R.id.dash_arrow)
    private val vTrend: TextView = content.findViewById(R.id.dash_trend)
    private val vDetail: TextView = content.findViewById(R.id.dash_detail)
    private val vChart: CartesianChartView = content.findViewById(R.id.dash_chart)
    private val vChartEmpty: TextView = content.findViewById(R.id.dash_chart_empty)
    private val vChartCaption: TextView = content.findViewById(R.id.dash_chart_caption)
    private val vSummaryTitle: TextView = content.findViewById(R.id.dash_summary_title)
    private val vTirValue: TextView = content.findViewById(R.id.dash_tir_value)
    private val vTirLabel: TextView = content.findViewById(R.id.dash_tir_label)
    private val vTirLegend: LinearLayout = content.findViewById(R.id.dash_tir_legend)
    private val vMetrics: LinearLayout = content.findViewById(R.id.dash_metrics)
    private val vSummaryNote: TextView = content.findViewById(R.id.dash_summary_note)
    private val tirBar = TirBar(ctx)

    private val tick = object : Runnable {
        override fun run() {
            refresh(false)
            handler.postDelayed(this, TICK_MS)
        }
    }


    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(ctx, attr, fallback)

    private fun buttonFor(h: Int) = when (h) {
        3 -> R.id.dash_h3
        12 -> R.id.dash_h12
        24 -> R.id.dash_h24
        else -> R.id.dash_h6
    }

    // ---------------------------------------------------------------- visibility

    private fun shouldShow(): Boolean {
        if (act.isFinishing) return false
        val conf = act.resources.configuration
        return conf.smallestScreenWidthDp < 600 &&
            conf.orientation == Configuration.ORIENTATION_PORTRAIT &&
            !fullGraph && !Menus.inScreen
    }

    fun updateVisibility() {
        val show = shouldShow()
        val wanted = if (show) View.VISIBLE else View.GONE
        if (root.visibility != wanted) root.visibility = wanted
        handler.removeCallbacks(tick)
        if (show && root.isAttachedToWindow) {
            act.lightBars(!dark)
            ViewCompat.requestApplyInsets(content)
            refresh(false)
            handler.postDelayed(tick, TICK_MS)
        }
    }

    fun stop() {
        handler.removeCallbacks(tick)
        job?.cancel()
    }

    // ---------------------------------------------------------------- data

    private fun refresh(force: Boolean) {
        val lastTime = Natives.lastglucosetime()
        val stale = SystemClock.elapsedRealtime() - loadedAt > RELOAD_MS
        if (!force && lastTime == loadedGlucoseTime && hours == loadedHours && !stale) {
            showAge()
            return
        }
        readSettings()
        loadedGlucoseTime = lastTime
        loadedHours = hours
        loadedAt = SystemClock.elapsedRealtime()
        job?.cancel()
        val h = hours
        job = scope.launch {
            val data = try {
                withContext(Dispatchers.Default) { load(h) }
            } catch (th: Throwable) {
                if (th is kotlinx.coroutines.CancellationException) throw th
                Log.stack(LOG_ID, "load", th)
                return@launch
            }
            show(data)
        }
    }

    private fun readings(seconds: Int): Readings {
        var raw = Natives.dashboardValues(seconds, false)
        if (raw == null || raw.size < 2) raw = Natives.dashboardValues(seconds, true)
        if (raw == null || raw.size < 2) return Readings(LongArray(0), FloatArray(0))
        val n = raw.size / 2
        val order = (0 until n).sortedBy { raw[2 * it].toLong() and 0xFFFFFFFFL }
        val times = ArrayList<Long>(n)
        val values = ArrayList<Float>(n)
        var prev = Long.MIN_VALUE
        for (i in order) {
            val t = raw[2 * i].toLong() and 0xFFFFFFFFL
            if (t - prev < DUPLICATE_SEC) continue // a second sensor reporting the same minute
            prev = t
            times.add(t)
            values.add(raw[2 * i + 1] / 10f)
        }
        return Readings(times.toLongArray(), values.toFloatArray())
    }

    private fun load(h: Int): Data {
        val now = System.currentTimeMillis() / 1000L
        val start = (now - h * 3600L) / 60L * 60L
        val week2 = readings(SUMMARY_DAYS * 86400)
        val from = week2.time.indexOfFirst { it >= start }.let { if (it < 0) week2.size else it }
        val recent = Readings(week2.time.copyOfRange(from, week2.size), week2.mgdl.copyOfRange(from, week2.size))
        return Data(now, start, h, recent, summarize(week2, now), Natives.lastglucose())
    }

    private fun summarize(r: Readings, now: Long): Summary? {
        if (r.size < 12) return null
        val span = now - r.time[0]
        if (span < 3 * 3600) return null
        val counts = IntArray(5)
        var sum = 0.0
        var sumSq = 0.0
        var inTarget = 0
        var covered = 0L
        for (i in 0 until r.size) {
            val g = r.mgdl[i]
            sum += g
            sumSq += g.toDouble() * g
            counts[band(g)]++
            if (g >= targetLow && g <= targetHigh) inTarget++
            if (i > 0) covered += min(r.time[i] - r.time[i - 1], MAX_GAP_SEC)
        }
        val n = r.size.toDouble()
        val mean = sum / n
        val sd = sqrt(max(0.0, sumSq / n - mean * mean))
        return Summary(
            spanSec = span,
            bands = DoubleArray(5) { counts[it] / n },
            meanMgdl = mean,
            cv = if (mean > 0) sd / mean else 0.0,
            inTarget = inTarget / n,
            coverage = min(1.0, covered.toDouble() / span),
        )
    }

    /** International consensus ranges, as in Juggluco's statistics: <54, 54-69, 70-180, 181-250, >250 mg/dL. */
    private fun band(g: Float) = when {
        g > 250f -> 4
        g > 180f -> 3
        g > 69f -> 2
        g > 53f -> 1
        else -> 0
    }

    // ---------------------------------------------------------------- display

    private fun toUnit(mgdl: Double) = if (mmol) mgdl / MGDL_PER_MMOL else mgdl
    private fun fmt(mgdl: Double) = if (mmol) String.format(Locale.getDefault(), "%.1f", mgdl / MGDL_PER_MMOL)
        else String.format(Locale.getDefault(), "%.0f", mgdl)
    private fun unitLabel() = act.getString(if (mmol) R.string.mmolL else R.string.mgdL)

    private fun readSettings() {
        mmol = Natives.getunit() == 1
        val lo = Natives.targetlow()
        val hi = Natives.targethigh()
        val toMg = if (mmol) MGDL_PER_MMOL.toFloat() else 1f
        if (lo > 0 && hi > lo) {
            targetLow = lo * toMg
            targetHigh = hi * toMg
        }
    }

    private suspend fun show(d: Data) {
        showLatest(d)
        showChart(d)
        showSummary(d.summary)
    }

    private fun statusColor(mgdl: Float) = when {
        mgdl < 54f -> cVeryLow
        mgdl < targetLow -> cLow
        mgdl > 250f -> cVeryHigh
        mgdl > targetHigh -> cHigh
        else -> cInRange
    }

    private fun showLatest(d: Data) {
        vUnit.text = unitLabel()
        if (d.recent.size == 0 && d.last == null) {
            latestTime = 0
            vValue.text = "–"
            vValue.setTextColor(cOnSurfaceVariant)
            vStatus.visibility = View.GONE
            vArrow.visibility = View.INVISIBLE
            vTrend.setText(R.string.dash_no_reading)
            vDetail.setText(R.string.dash_no_reading_detail)
            return
        }
        val lastIndex = d.recent.size - 1
        val time: Long
        val mgdl: Float
        if (lastIndex >= 0) {
            time = d.recent.time[lastIndex]
            mgdl = d.recent.mgdl[lastIndex]
        } else {
            time = d.last!!.time
            mgdl = parseToMgdl(d.last.value)
        }
        latestTime = time
        val rate = d.last?.takeIf { abs(it.time - time) < 90 }?.rate ?: Float.NaN
        val stale = d.now - time > STALE_SEC
        val col = if (stale) cOnSurfaceVariant else statusColor(mgdl)
        dotColor = col

        vValue.text = if (mgdl > 0) fmt(mgdl.toDouble()) else d.last?.value ?: "–"
        vValue.setTextColor(col)
        vValue.contentDescription = vValue.text.toString() + " " + unitLabel()

        vStatus.visibility = View.VISIBLE
        vStatus.setText(
            when {
                stale -> R.string.dash_status_stale
                mgdl < 54f -> R.string.dash_status_very_low
                mgdl < targetLow -> R.string.dash_status_below
                mgdl > targetHigh -> R.string.dash_status_above
                else -> R.string.dash_status_in_target
            }
        )
        vStatus.setTextColor(if (stale) cOnSurfaceVariant else col)
        (vStatus.background.mutate() as? GradientDrawable)?.setColor((col and 0x00FFFFFF) or 0x2E000000)

        if (stale || rate.isNaN()) {
            vArrow.visibility = View.INVISIBLE
            vTrend.setText(if (stale) R.string.dash_status_stale else R.string.dash_trend_unknown)
            vTrend.setTextColor(cOnSurfaceVariant)
        } else {
            vArrow.visibility = View.VISIBLE
            vArrow.imageTintList = ColorStateList.valueOf(col)
            vArrow.rotation = when {
                rate >= 2f -> -90f
                rate >= 1f -> -45f
                rate > -1f -> 0f
                rate > -2f -> 45f
                else -> 90f
            }
            vTrend.setText(
                when {
                    rate >= 3.5f -> R.string.dash_trend_up_fast
                    rate >= 2f -> R.string.dash_trend_up
                    rate >= 1f -> R.string.dash_trend_up_slow
                    rate > -1f -> R.string.dash_trend_flat
                    rate > -2f -> R.string.dash_trend_down_slow
                    rate > -3.5f -> R.string.dash_trend_down
                    else -> R.string.dash_trend_down_fast
                }
            )
            vTrend.setTextColor(cOnSurface)
            vArrow.contentDescription = vTrend.text
        }
        trendRate = if (stale || rate.isNaN()) null else rate
        showAge()
    }

    private var trendRate: Float? = null

    private fun showAge() {
        if (latestTime == 0L) return
        val ageSec = System.currentTimeMillis() / 1000L - latestTime
        val res = ctx.resources
        val age = when {
            ageSec < 60 -> ctx.getString(R.string.dash_just_now)
            ageSec < 3600 -> (ageSec / 60).toInt().let { res.getQuantityString(R.plurals.dash_minutes_ago, it, it) }
            else -> (ageSec / 3600).toInt().let { res.getQuantityString(R.plurals.dash_hours_ago, it, it) }
        }
        val rate = trendRate
        vDetail.text = if (rate == null) age else {
            val perMin = if (mmol) String.format(Locale.getDefault(), "%+.2f", rate / MGDL_PER_MMOL)
                else String.format(Locale.getDefault(), "%+.1f", rate)
            ctx.getString(R.string.dash_rate, perMin, unitLabel()) + " · " + age
        }
    }

    private fun parseToMgdl(value: String?): Float {
        val v = value?.replace(',', '.')?.trim()?.toFloatOrNull() ?: return 0f
        return if (mmol) v * MGDL_PER_MMOL.toFloat() else v
    }

    // ---------------------------------------------------------------- chart

    private val rangeProvider = object : CartesianLayerRangeProvider {
        override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = hours * 60.0
        override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = this@Dashboard.minY
        override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = this@Dashboard.maxY
    }

    private val lineStroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 3f, cap = Paint.Cap.ROUND)

    private val glucoseLine = LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.colorScale {
            val eps = (maxY - minY) / 1000.0
            val lo = toUnit(targetLow.toDouble())
            val hi = toUnit(targetHigh.toDouble())
            val veryLow = toUnit(54.0)
            cHigh at hi + eps
            cInRange at hi
            cInRange at lo
            if (veryLow < lo - 2 * eps) {
                cLow at lo - eps
                cLow at veryLow
                cVeryLow at veryLow - eps
            } else {
                cLow at lo - eps
            }
        },
        stroke = lineStroke,
    )

    private var dotLine: LineCartesianLayer.Line? = null
    private var dotLineColor = 0

    private fun lastDotLine(): LineCartesianLayer.Line {
        val existing = dotLine
        if (existing != null && dotLineColor == dotColor) return existing
        val point = LineCartesianLayer.Point(
            ShapeComponent(Fill(dotColor), CorneredShape.Pill, strokeFill = Fill(cSurface), strokeThicknessDp = 2.5f),
            sizeDp = 12f,
        )
        return LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill.Transparent),
            pointProvider = LineCartesianLayer.PointProvider.single(point),
        ).also {
            dotLine = it
            dotLineColor = dotColor
        }
    }

    private fun timeLabel(x: Double): String {
        val date = Date((chartStart + (x * 60).roundToInt()) * 1000L)
        return DateFormat.getTimeFormat(ctx).format(date)
    }

    /** Axis labels sit on whole hours: "10 PM" or "22" depending on the locale. */
    private val hourFormat by lazy {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(ctx)) "HH" else "ha")
        java.text.SimpleDateFormat(pattern, Locale.getDefault())
    }

    private fun hourLabel(x: Double): String =
        hourFormat.format(Date((chartStart + (x * 60).roundToInt()) * 1000L))

    @SuppressLint("ClickableViewAccessibility")
    private fun setupChart() {
        val axisText = TextComponent(color = cOnSurfaceVariant, textSizeSp = 11f)
        val guideline = LineComponent(Fill(cOutlineVariant), thicknessDp = 1f)
        val startAxis = VerticalAxis.start(
            label = axisText,
            guideline = guideline,
            valueFormatter = CartesianValueFormatter { _: CartesianMeasuringContext, value: Double, _: Axis.Position.Vertical? ->
                if (mmol && yStep < 1.0) String.format(Locale.getDefault(), "%.1f", value)
                else String.format(Locale.getDefault(), "%.0f", value)
            },
            itemPlacer = VerticalAxis.ItemPlacer.step({ yStep }),
        )
        val bottomAxis = HorizontalAxis.bottom(
            line = LineComponent(Fill(cOutlineVariant), thicknessDp = 1f),
            label = axisText,
            valueFormatter = CartesianValueFormatter { _: CartesianMeasuringContext, value: Double, _: Axis.Position.Vertical? -> hourLabel(value) },
            itemPlacer = HorizontalAxis.ItemPlacer.aligned(spacing = { labelSpacing }, offset = { labelOffset }),
        )
        val targetBand = HorizontalBox(
            y = { toUnit(targetLow.toDouble())..toUnit(targetHigh.toDouble()) },
            box = ShapeComponent(Fill((cInRange and 0x00FFFFFF) or 0x24000000)),
        )
        val marker = DefaultCartesianMarker(
            label = TextComponent(
                color = cOnSurface,
                textSizeSp = 13f,
                padding = Insets(8f, 4f, 8f, 4f),
                background = ShapeComponent(Fill(cSurfaceHighest), CorneredShape.rounded(8f)),
            ),
            valueFormatter = DefaultCartesianMarker.ValueFormatter { _, targets ->
                val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                val point = target?.points?.firstOrNull()
                if (target == null || point == null) "" else {
                    val y = point.entry.y
                    val text = if (mmol) String.format(Locale.getDefault(), "%.1f", y) else String.format(Locale.getDefault(), "%.0f", y)
                    text + " · " + timeLabel(target.x)
                }
            },
            labelPosition = DefaultCartesianMarker.LabelPosition.AbovePoint,
            guideline = LineComponent(Fill(cOutlineVariant), thicknessDp = 1f),
        )
        val layer = LineCartesianLayer(
            lineProvider = { key, _, _ -> if (key == LAST_KEY) lastDotLine() else glucoseLine },
            rangeProvider = rangeProvider,
        )
        vChart.chart = CartesianChart(
            layer,
            startAxis = startAxis,
            bottomAxis = bottomAxis,
            marker = marker,
            layerPadding = { CartesianLayerPadding(unscalableStartDp = 12f, unscalableEndDp = 12f) },
            decorations = listOf(targetBand),
            getXStep = { _, _, _ -> 1.0 },
        )
        vChart.zoomHandler = ZoomHandler(zoomEnabled = false, initialZoom = Zoom.Content, minZoom = Zoom.Content, maxZoom = Zoom.Content)
        vChart.scrollHandler = ScrollHandler(scrollEnabled = false)
        vChart.modelProducer = modelProducer
        // Let a finger on the chart read values instead of scrolling the page.
        vChart.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private suspend fun showChart(d: Data) {
        val r = d.recent
        chartStart = d.start
        val lowUnit = toUnit(targetLow.toDouble())
        val highUnit = toUnit(targetHigh.toDouble())
        vChartCaption.text = ctx.getString(R.string.dash_chart_caption, fmt(targetLow.toDouble()), fmt(targetHigh.toDouble()), unitLabel())
        if (r.size == 0) {
            vChart.visibility = View.INVISIBLE
            vChartEmpty.visibility = View.VISIBLE
            return
        }
        vChart.visibility = View.VISIBLE
        vChartEmpty.visibility = View.GONE

        var dataMin = Double.MAX_VALUE
        var dataMax = -Double.MAX_VALUE
        val segments = ArrayList<Pair<List<Double>, List<Double>>>()
        var xs = ArrayList<Double>()
        var ys = ArrayList<Double>()
        var prevT = Long.MIN_VALUE
        for (i in 0 until r.size) {
            val t = r.time[i]
            if (prevT != Long.MIN_VALUE && t - prevT > MAX_GAP_SEC && xs.isNotEmpty()) {
                segments.add(xs to ys)
                xs = ArrayList()
                ys = ArrayList()
            }
            prevT = t
            val y = toUnit(r.mgdl[i].toDouble())
            dataMin = min(dataMin, y)
            dataMax = max(dataMax, y)
            xs.add(((t - d.start) / 60.0 * 100).roundToInt() / 100.0)
            ys.add(y)
        }
        if (xs.isNotEmpty()) segments.add(xs to ys)

        // y range: data and target band, rounded to whole label steps
        val lo = min(dataMin, lowUnit)
        val hi = max(dataMax, highUnit)
        val step = if (mmol) (if (hi - lo > 12) 4.0 else 2.0) else (if (hi - lo > 300) 100.0 else 50.0)
        val pad = if (mmol) 0.5 else 9.0
        yStep = step
        minY = max(0.0, floor((lo - pad) / step) * step)
        maxY = ceil((hi + pad) / step) * step
        if (maxY - minY < 3 * step) maxY = minY + 3 * step

        // time labels on whole hours (every 1, 2, 3 or 4 hours)
        val hoursPerLabel = when (d.hours) {
            3 -> 1
            6 -> 1
            12 -> 3
            else -> 4
        }
        labelSpacing = hoursPerLabel * 60
        val cal = Calendar.getInstance()
        cal.timeInMillis = d.start * 1000L
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.timeInMillis / 1000L < d.start || cal.get(Calendar.HOUR_OF_DAY) % hoursPerLabel != 0)
            cal.add(Calendar.HOUR_OF_DAY, 1)
        labelOffset = max(0, ((cal.timeInMillis / 1000L - d.start) / 60L).toInt())

        val lastX = xs.lastOrNull() ?: segments.last().first.last()
        val lastY = ys.lastOrNull() ?: segments.last().second.last()
        modelProducer.runTransaction {
            lineSeries {
                for ((index, seg) in segments.withIndex()) series(seg.first, seg.second, key = index)
                series(listOf(lastX), listOf(lastY), key = LAST_KEY)
            }
        }
    }

    // ---------------------------------------------------------------- summary

    private fun showSummary(s: Summary?) {
        val res = ctx.resources
        vTirLegend.removeAllViews()
        vMetrics.removeAllViews()
        if (s == null) {
            vSummaryTitle.text = res.getQuantityString(R.plurals.dash_summary_days, SUMMARY_DAYS, SUMMARY_DAYS)
            vTirValue.text = ""
            vTirLabel.setText(R.string.dash_summary_empty)
            tirBar.visibility = View.GONE
            vSummaryNote.visibility = View.GONE
            return
        }
        tirBar.visibility = View.VISIBLE
        vSummaryNote.visibility = View.VISIBLE
        val days = (s.spanSec / 86400L).toInt()
        vSummaryTitle.text = if (days >= SUMMARY_DAYS - 1) res.getQuantityString(R.plurals.dash_summary_days, SUMMARY_DAYS, SUMMARY_DAYS)
            else if (days >= 1) res.getQuantityString(R.plurals.dash_summary_days, days + 1, days + 1)
            else (s.spanSec / 3600L).toInt().let { res.getQuantityString(R.plurals.dash_summary_hours, it, it) }

        val pct = { f: Double -> (f * 100).roundToInt() }
        vTirValue.text = String.format(Locale.getDefault(), "%d%%", pct(s.bands[2]))
        vTirValue.setTextColor(cInRange)
        vTirLabel.text = ctx.getString(R.string.dash_tir_label, fmt(70.0), fmt(180.0), unitLabel())

        val colors = intArrayOf(cVeryLow, cLow, cInRange, cHigh, cVeryHigh)
        tirBar.set(s.bands, colors)

        val names = intArrayOf(R.string.dash_band_very_low, R.string.dash_band_low, R.string.dash_band_in_range, R.string.dash_band_high, R.string.dash_band_very_high)
        val ranges = arrayOf(
            "< " + fmt(54.0),
            fmt(54.0) + "–" + fmt(69.0),
            fmt(70.0) + "–" + fmt(180.0),
            fmt(181.0) + "–" + fmt(250.0),
            "> " + fmt(250.0),
        )
        for (i in 4 downTo 0) vTirLegend.addView(legendRow(colors[i], ctx.getString(names[i]), ranges[i], pct(s.bands[i])))

        val gmiPercent = 3.31 + 0.02392 * s.meanMgdl
        val gmiMmolMol = 12.71 + 4.70587 * (s.meanMgdl / MGDL_PER_MMOL)
        vMetrics.addView(metric(fmt(s.meanMgdl), ctx.getString(R.string.dash_metric_average) + " · " + unitLabel()))
        vMetrics.addView(metric(String.format(Locale.getDefault(), "%.1f%%", gmiPercent),
            ctx.getString(R.string.dash_metric_gmi) + " ≈ " + String.format(Locale.getDefault(), "%.0f mmol/mol", gmiMmolMol)))
        vMetrics.addView(metric(String.format(Locale.getDefault(), "%.0f%%", s.cv * 100), ctx.getString(R.string.dash_metric_cv)))

        vSummaryNote.text = ctx.getString(
            R.string.dash_summary_note,
            fmt(targetLow.toDouble()), fmt(targetHigh.toDouble()), unitLabel(), pct(s.inTarget), pct(s.coverage),
        )
    }

    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun legendRow(col: Int, name: String, range: String, percent: Int): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(3f), 0, dp(3f))
        val dot = View(ctx)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(col)
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginEnd = dp(10f) })
        val label = TextView(ctx)
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        label.setTextColor(cOnSurface)
        label.text = name
        row.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val rangeView = TextView(ctx)
        rangeView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        rangeView.setTextColor(cOnSurfaceVariant)
        rangeView.text = range
        row.addView(rangeView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8f) })
        val value = TextView(ctx)
        value.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
        value.setTextColor(cOnSurface)
        value.text = String.format(Locale.getDefault(), "%d%%", percent)
        row.addView(value)
        row.contentDescription = "$name $range: $percent%"
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        return row
    }

    private fun metric(value: String, label: String): View {
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val v = TextView(ctx)
        v.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        v.setTextColor(cOnSurface)
        v.text = value
        box.addView(v)
        val l = TextView(ctx)
        l.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        l.setTextColor(cOnSurfaceVariant)
        l.text = label
        box.addView(l)
        box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12f) }
        return box
    }

    /** Horizontal stacked bar of the time spent in each range. */
    private class TirBar(context: Context) : View(context) {
        private var parts = DoubleArray(0)
        private var colors = IntArray(0)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()

        fun set(parts: DoubleArray, colors: IntArray) {
            this.parts = parts
            this.colors = colors
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || parts.isEmpty()) return
            val r = h / 2
            clip.reset()
            rect.set(0f, 0f, w, h)
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val gap = resources.displayMetrics.density * 2
            var x = 0f
            for (i in parts.indices) {
                val sw = (parts[i] * w).toFloat()
                if (sw <= 0f) continue
                paint.color = colors[i]
                val end = if (x + sw >= w - 0.5f) w else x + sw - gap
                if (end > x) canvas.drawRect(x, 0f, end, h, paint)
                x += sw
            }
            canvas.restore()
        }
    }

    // Last, so every property above is initialised before setupChart() uses it.
    init {
        root.isClickable = true // keep touches away from the graph underneath
        root.setBackgroundColor(cSurface)
        root.addView(content)
        content.findViewById<FrameLayout>(R.id.dash_tir_bar).addView(tirBar)
        val scroll = content
        val density = ctx.resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val group: MaterialButtonToggleGroup = content.findViewById(R.id.dash_hours)
        group.check(buttonFor(hours))
        group.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val h = when (id) {
                R.id.dash_h3 -> 3
                R.id.dash_h12 -> 12
                R.id.dash_h24 -> 24
                else -> 6
            }
            if (h != hours) {
                hours = h
                prefs.edit().putInt(PREF_HOURS, h).apply()
                refresh(true)
            }
        }
        content.findViewById<View>(R.id.dash_fullgraph).setOnClickListener { showFullGraph(act) }
        setupChart()
        vTirLabel.minHeight = (20 * density).toInt()
    }

    companion object {
        private const val LOG_ID = "Dashboard"
        private const val PREF_HOURS = "hours"
        private val HOUR_CHOICES = intArrayOf(3, 6, 12, 24)
        private const val SUMMARY_DAYS = 14
        private const val MGDL_PER_MMOL = 18.0182
        private const val MAX_GAP_SEC = 20 * 60L
        private const val STALE_SEC = 15 * 60L
        private const val DUPLICATE_SEC = 30L
        private const val TICK_MS = 30_000L
        private const val RELOAD_MS = 5 * 60_000L
        private const val LAST_KEY = "last"

        private var current: Dashboard? = null

        /** Phone graph shown instead of the dashboard (Full graph, or a graph action from the menu). */
        @JvmStatic
        var fullGraph = false
            private set

        @JvmStatic
        fun attach(act: MainActivity) {
            if (Build.VERSION.SDK_INT < 23) return // Vico needs Android 6
            detach()
            if (act.findViewById<ViewGroup>(android.R.id.content) == null) return
            val d = try {
                Dashboard(act)
            } catch (th: Throwable) {
                Log.stack(LOG_ID, "attach", th)
                return
            }
            act.addContentView(d.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            current = d
            d.updateVisibility()
        }

        @JvmStatic
        fun detach() {
            val d = current ?: return
            current = null
            d.stop()
            d.scope.cancel()
            Settings.removeContentView(d.root)
        }

        @JvmStatic
        fun update() {
            current?.updateVisibility()
        }

        @JvmStatic
        fun isDashboard(view: View) = view is Root

        /** Switch to the native graph; Back comes back to the dashboard. */
        @JvmStatic
        fun showFullGraph(act: MainActivity) {
            val d = current
            if (!fullGraph && d != null && d.root.visibility == View.VISIBLE) {
                fullGraph = true
                MainActivity.setonback {
                    fullGraph = false
                    update()
                }
            }
            update()
            act.requestRender()
        }
    }
}
