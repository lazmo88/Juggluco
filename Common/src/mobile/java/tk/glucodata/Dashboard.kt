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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
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
import com.google.android.material.button.MaterialButton
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
import tk.glucodata.nums.item
import tk.glucodata.nums.numio
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
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Phone home screen in portrait: the latest reading with its trend, a chart of the
 * last hours and a 14-day summary (time in ranges, average, GMI, variability).
 *
 * It lies on top of the native graph (GlucoseCurve), which stays the detailed view:
 * turn the phone, tap "Full graph", or pick a graph action in the menu. Back
 * returns to the dashboard. Values come from Natives.dashboardValues(), which uses
 * the same sensor ranges and calibration as Juggluco's statistics.
 *
 * Finger-stick readings of a glucose meter are stored as amounts under the blood
 * glucose label. They are read as well (never written): the newest one is shown on
 * top when it is newer than the sensor value, and the latest ones are listed. Someone
 * with only a meter (no sensor value for 14 days: "meter mode") gets the chart and
 * the summary of the meter readings instead, over days rather than hours.
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

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            owner.windowVisible(visibility == View.VISIBLE)
        }
    }

    private class Readings(val time: LongArray, val mgdl: FloatArray) {
        val size get() = time.size

        /** The readings from [start] on; times are ascending. */
        fun from(start: Long): Readings {
            val first = time.indexOfFirst { it >= start }.let { if (it < 0) size else it }
            return Readings(time.copyOfRange(first, size), mgdl.copyOfRange(first, size))
        }
    }

    private class Summary(
        val spanSec: Long,
        val bands: DoubleArray, // very low, low, in range, high, very high (fractions)
        val meanMgdl: Double,
        val cv: Double,
        val inTarget: Double,
        val coverage: Double,
    )

    /** Summary of finger-stick readings: shares are shares of readings, not of time. */
    private class MeterSummary(
        val count: Int,
        val bands: DoubleArray, // same order as Summary.bands
        val meanMgdl: Double,
        val lowestMgdl: Float,
        val highestMgdl: Float,
        val inTarget: Double,
    )

    private class Data(
        val now: Long,
        val start: Long, // of the chart period
        val hours: Int, // length of the chart period
        val recent: Readings, // sensor values in the chart period
        val summary: Summary?,
        val last: strGlucose?,
        val meter: Readings, // finger-stick readings of the last 90 days, oldest first
        val meterMode: Boolean,
        val xUnit: Int, // seconds per x step of the chart
        val plot: Readings, // what the chart draws: recent, or in meter mode the meter readings of the period
        val meterSummary: MeterSummary?,
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
    private var loadedAmounts = Long.MIN_VALUE
    private var loadedAt = 0L
    private var wasActive = false
    private var latestTime = 0L
    private var latestIsMeter = false

    private var hours = prefs.getInt(PREF_HOURS, 6).let { if (it in HOUR_CHOICES) it else 6 }
    private var meterHours = prefs.getInt(PREF_METER_HOURS, 168).let { if (it in METER_HOUR_CHOICES) it else 168 }

    /** Decided by load(); remembered so the period buttons start with the right labels. */
    private var meterMode = prefs.getBoolean(PREF_METER_MODE, false)
    private var settingButtons = false
    private var mmol = true
    private var targetLow = 70f
    private var targetHigh = 180f
    private var minY = 0.0
    private var maxY = 1.0
    private var yStep = 2.0
    private var chartStart = 0L
    private var chartUnit = 60 // seconds per x step
    private var chartMaxX = hours * 60.0
    private var chartMeter = false // the chart shows meter readings
    private var dateLabels = false // x axis labelled with dates instead of hours
    private var meterDotDp = 10f
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
    private val cOutline = color(com.google.android.material.R.attr.colorOutline, Color.GRAY)
    private val cSurface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val cSurfaceHighest = color(com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.LTGRAY)
    private val cCard = color(com.google.android.material.R.attr.colorSurfaceContainerLow, Color.WHITE)

    /** Very low, low, in range, high, very high: the order of Summary.bands and meterLevel(). */
    private val rangeColors = intArrayOf(cVeryLow, cLow, cInRange, cHigh, cVeryHigh)

    private val content: View = LayoutInflater.from(ctx).inflate(R.layout.dashboard, root, false)
    private val vStatus: TextView = content.findViewById(R.id.dash_status)
    private val vValue: TextView = content.findViewById(R.id.dash_value)
    private val vUnit: TextView = content.findViewById(R.id.dash_unit)
    private val vArrow: ImageView = content.findViewById(R.id.dash_arrow)
    private val vTrend: TextView = content.findViewById(R.id.dash_trend)
    private val vDetail: TextView = content.findViewById(R.id.dash_detail)
    private val vEmpty: View = content.findViewById(R.id.dash_empty)
    private val vBloodBox: View = content.findViewById(R.id.dash_blood_box)
    private val vValueRow: View = content.findViewById(R.id.dash_value_row)
    private val vChartFrame: View = content.findViewById(R.id.dash_chart_frame)
    private val vSummaryCard: View = content.findViewById(R.id.dash_summary_card)
    private val vChart: CartesianChartView = content.findViewById(R.id.dash_chart)
    private val vChartEmpty: TextView = content.findViewById(R.id.dash_chart_empty)
    private val vChartCaption: TextView = content.findViewById(R.id.dash_chart_caption)
    private val vSummaryTitle: TextView = content.findViewById(R.id.dash_summary_title)
    private val vTirValue: TextView = content.findViewById(R.id.dash_tir_value)
    private val vTirLabel: TextView = content.findViewById(R.id.dash_tir_label)
    private val vTirLegend: LinearLayout = content.findViewById(R.id.dash_tir_legend)
    private val vMetrics: LinearLayout = content.findViewById(R.id.dash_metrics)
    private val vSummaryNote: TextView = content.findViewById(R.id.dash_summary_note)
    private val vSummaryDivider: View = content.findViewById(R.id.dash_summary_divider)
    private val vHours: MaterialButtonToggleGroup = content.findViewById(R.id.dash_hours)
    private val vMeterCard: View = content.findViewById(R.id.dash_meter_card)
    private val vMeterRows: LinearLayout = content.findViewById(R.id.dash_meter_rows)
    private val tirBar = TirBar(ctx)

    private val tick = object : Runnable {
        override fun run() {
            refresh(false)
            handler.postDelayed(this, TICK_MS)
        }
    }


    private fun color(attr: Int, fallback: Int) = MaterialColors.getColor(ctx, attr, fallback)

    // ---------------------------------------------------------------- period buttons

    /** The four buttons choose hours of sensor values, or in meter mode days of meter readings. */
    private val rangeButtons = intArrayOf(R.id.dash_h3, R.id.dash_h6, R.id.dash_h12, R.id.dash_h24)

    private fun showRangeButtons() {
        val labels = if (meterMode) METER_HOUR_LABELS else HOUR_LABELS
        for (i in rangeButtons.indices) content.findViewById<MaterialButton>(rangeButtons[i]).setText(labels[i])
        val choice = if (meterMode) METER_HOUR_CHOICES.indexOf(meterHours) else HOUR_CHOICES.indexOf(hours)
        settingButtons = true // checking a button here is not the user choosing a period
        vHours.check(rangeButtons[max(0, choice)])
        settingButtons = false
    }

    private fun setMeterMode(on: Boolean) {
        if (on == meterMode) return
        meterMode = on
        prefs.edit().putBoolean(PREF_METER_MODE, on).apply()
        showRangeButtons()
    }

    private fun rangeChosen(id: Int) {
        if (settingButtons) return
        val choice = rangeButtons.indexOf(id)
        if (choice < 0) return
        if (meterMode) {
            if (METER_HOUR_CHOICES[choice] == meterHours) return
            meterHours = METER_HOUR_CHOICES[choice]
            prefs.edit().putInt(PREF_METER_HOURS, meterHours).apply()
        } else {
            if (HOUR_CHOICES[choice] == hours) return
            hours = HOUR_CHOICES[choice]
            prefs.edit().putInt(PREF_HOURS, hours).apply()
        }
        refresh(true)
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
        val active = show && root.isAttachedToWindow
        if (active) {
            act.lightBars(!dark)
            ViewCompat.requestApplyInsets(content)
            // Back from another screen: amounts may have been added, changed or deleted there.
            refresh(!wasActive)
            handler.postDelayed(tick, TICK_MS)
        }
        wasActive = active
    }

    /**
     * The app left the screen or came back. Coming back reloads at once: the meter may
     * have sent readings in the meantime.
     */
    private fun windowVisible(visible: Boolean) {
        if (visible) {
            updateVisibility()
        } else {
            wasActive = false
            handler.removeCallbacks(tick)
        }
    }

    fun stop() {
        handler.removeCallbacks(tick)
        job?.cancel()
    }

    // ---------------------------------------------------------------- data

    private fun refresh(force: Boolean) {
        val lastTime = Natives.lastglucosetime()
        val amounts = amountsStamp()
        val stale = SystemClock.elapsedRealtime() - loadedAt > RELOAD_MS
        if (!force && lastTime == loadedGlucoseTime && amounts == loadedAmounts && !stale) {
            showAge()
            return
        }
        readSettings()
        loadedGlucoseTime = lastTime
        loadedAmounts = amounts
        loadedAt = SystemClock.elapsedRealtime()
        job?.cancel()
        val h = hours
        val meterH = meterHours
        job = scope.launch {
            val data = try {
                withContext(Dispatchers.Default) { load(h, meterH) }
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

    /**
     * Changes when an amount is added, so a reading the meter sends while the dashboard
     * is open shows at the next tick. Changes made on other screens are caught by the
     * reload in updateVisibility().
     */
    private fun amountsStamp(): Long {
        var stamp = 0L
        try {
            for (ptr in numio.numptrs) if (ptr != 0L) stamp = stamp * 1_000_003L + Natives.getlastNum(ptr)
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "amountsStamp", th)
        }
        return stamp
    }

    /** Whether a label is set as blood glucose (Settings, Calibration); the meter page sets it too. */
    private fun bloodLabelSet(): Boolean = try {
        val blood = Natives.getbloodvar().toInt()
        blood >= 0 && blood < (Natives.getLabels()?.size ?: 0)
    } catch (th: Throwable) {
        Log.stack(LOG_ID, "bloodLabelSet", th)
        true // no hint rather than a wrong one
    }

    /**
     * Finger-stick readings of the last 90 days, oldest first: the amounts with the blood
     * glucose label from both amount sources (this phone and the other device), read the
     * way AmountListPage does. Amounts hold the value in the display unit; here it becomes
     * mg/dL like the sensor values, so the range code works on both. Read-only.
     */
    private fun meterReadings(now: Long): Readings {
        val found = ArrayList<item>()
        try {
            // Without a blood glucose label this is the label count, which no amount has as label.
            val blood = Natives.getbloodvar().toInt()
            if (blood >= 0 && blood < (Natives.getLabels()?.size ?: 0)) {
                val from = now - METER_DAYS * 86400L
                val until = now + CLOCK_SLACK_SEC
                for (ptr in numio.numptrs) {
                    if (ptr == 0L) continue
                    for (pos in Natives.getfirstNum(ptr) until Natives.getlastNum(ptr)) {
                        val num = Natives.getNumitem(ptr, pos) ?: continue
                        if (num.label == blood && num.value > 0f && !num.value.isInfinite() && num.time in from..until)
                            found.add(num)
                    }
                }
            }
        } catch (th: Throwable) {
            Log.stack(LOG_ID, "meterReadings", th) // the sensor part of the dashboard still works
        }
        found.sortBy { it.time }
        // The same factor as readSettings() uses for the target, so a reading on the target limit is in target.
        val toMg = if (mmol) MGDL_PER_MMOL.toFloat() else 1f
        val times = ArrayList<Long>(found.size)
        val values = ArrayList<Float>(found.size)
        for (num in found) {
            if (times.isNotEmpty() && times[times.size - 1] == num.time) continue // same reading stored twice
            times.add(num.time)
            values.add(num.value * toMg)
        }
        return Readings(times.toLongArray(), values.toFloatArray())
    }

    private fun load(h: Int, meterH: Int): Data {
        val now = System.currentTimeMillis() / 1000L
        val week2 = readings(SUMMARY_DAYS * 86400)
        val last = Natives.lastglucose()
        val meter = meterReadings(now)
        // Meter mode: there are meter readings, but no sensor value in the last 14 days.
        val sensorTime = max(if (week2.size > 0) week2.time[week2.size - 1] else 0L, last?.time ?: 0L)
        if (meter.size > 0 && now - sensorTime > SUMMARY_DAYS * 86400L) {
            val unit = meterXUnit(meterH)
            val start = meterStart(now, meterH, unit)
            val none = Readings(LongArray(0), FloatArray(0))
            return Data(now, start, meterH, none, null, last, meter, true, unit, meter.from(start), summarizeMeter(meter, now))
        }
        val start = (now - h * 3600L) / 60L * 60L
        val recent = week2.from(start)
        return Data(now, start, h, recent, summarize(week2, now), last, meter, false, 60, recent, null)
    }

    /** Seconds per x step of the meter chart: coarser for longer periods, at most about 1500 steps. */
    private fun meterXUnit(h: Int) = when {
        h <= 24 -> 60
        h <= 168 -> 600
        h <= 720 -> 3600
        else -> 7200
    }

    /**
     * Start of the meter chart. Periods of days start a whole number of x steps before
     * today's midnight, so the date labels, counted back from today, fall on midnights.
     */
    private fun meterStart(now: Long, h: Int, unit: Int): Long {
        val raw = now - h * 3600L
        if (h <= 24) return raw / 60L * 60L
        val today = startOfDay(now)
        return today - (today - raw + unit - 1) / unit * unit
    }

    /** Local midnight (epoch seconds) of the day [time] lies in. */
    private fun startOfDay(time: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = time * 1000L
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000L
    }

    private fun summarizeMeter(m: Readings, now: Long): MeterSummary? {
        val from = now - METER_SUMMARY_DAYS * 86400L
        val counts = IntArray(5)
        var count = 0
        var sum = 0.0
        var inTarget = 0
        var lowest = Float.MAX_VALUE
        var highest = 0f
        for (i in 0 until m.size) {
            if (m.time[i] < from) continue
            val g = m.mgdl[i]
            count++
            sum += g
            counts[meterBand(g)]++
            if (g >= targetLow && g <= targetHigh) inTarget++
            lowest = min(lowest, g)
            highest = max(highest, g)
        }
        if (count < METER_SUMMARY_MIN) return null
        val n = count.toDouble()
        return MeterSummary(count, DoubleArray(5) { counts[it] / n }, sum / n, lowest, highest, inTarget / n)
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

    /**
     * Consensus range of a meter reading, compared as whole mg/dL: a reading in mmol/L has
     * one decimal, and 10.0 mmol/L (180.2 mg/dL) belongs in the range shown as 3.9–10.0.
     */
    private fun meterBand(g: Float) = band(g.roundToInt().toFloat())

    /**
     * Where a meter reading lies for its colour and status, like statusColor():
     * 0 very low, 1 below target, 2 in target, 3 above target, 4 very high.
     */
    private fun meterLevel(mgdl: Float): Int {
        val whole = mgdl.roundToInt()
        return when {
            whole < 54 -> 0
            mgdl < targetLow -> 1
            whole > 250 -> 4
            mgdl > targetHigh -> 3
            else -> 2
        }
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
        setMeterMode(d.meterMode)
        showLatest(d)
        showChart(d)
        showMeterList(d)
        if (d.meterMode) showMeterSummary(d.meterSummary) else showSummary(d.summary)
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
        val hasSensor = d.recent.size > 0 || d.last != null
        val meterLast = d.meter.size - 1
        if (!hasSensor && meterLast < 0) {
            latestTime = 0
            latestIsMeter = false
            vEmpty.visibility = View.VISIBLE
            vBloodBox.visibility = if (bloodLabelSet()) View.GONE else View.VISIBLE
            vValueRow.visibility = View.GONE
            vStatus.visibility = View.GONE
            vTrend.visibility = View.GONE
            vDetail.visibility = View.GONE
            return
        }
        vEmpty.visibility = View.GONE
        vValueRow.visibility = View.VISIBLE
        vTrend.visibility = View.VISIBLE
        vDetail.visibility = View.VISIBLE
        val lastIndex = d.recent.size - 1
        val time: Long
        val mgdl: Float
        if (lastIndex >= 0) {
            time = d.recent.time[lastIndex]
            mgdl = d.recent.mgdl[lastIndex]
        } else if (d.last != null) {
            time = d.last.time
            mgdl = parseToMgdl(d.last.value)
        } else {
            time = Long.MIN_VALUE
            mgdl = 0f
        }
        // Whichever is newer: the sensor value or the last finger-stick reading.
        if (meterLast >= 0 && d.meter.time[meterLast] > time) {
            showLatestMeter(d.meter.time[meterLast], d.meter.mgdl[meterLast])
            return
        }
        latestTime = time
        latestIsMeter = false
        vUnit.visibility = View.VISIBLE
        val rate = d.last?.takeIf { abs(it.time - time) < 90 }?.rate ?: Float.NaN
        val stale = d.now - time > STALE_SEC
        val col = if (stale) cOnSurfaceVariant else statusColor(mgdl)

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

    /**
     * A finger-stick reading on top. It is a spot value: coloured by range and never
     * "stale", without a trend, and with the moment it was taken instead.
     */
    private fun showLatestMeter(time: Long, mgdl: Float) {
        latestTime = time
        latestIsMeter = true
        trendRate = null
        val level = meterLevel(mgdl)
        val col = rangeColors[level]
        vUnit.visibility = View.VISIBLE
        vValue.text = fmt(mgdl.toDouble())
        vValue.setTextColor(col)
        vValue.contentDescription = vValue.text.toString() + " " + unitLabel()

        vStatus.visibility = View.VISIBLE
        vStatus.setText(
            when (level) {
                0 -> R.string.dash_status_very_low
                1 -> R.string.dash_status_below
                2 -> R.string.dash_status_in_target
                else -> R.string.dash_status_above
            }
        )
        vStatus.setTextColor(col)
        (vStatus.background.mutate() as? GradientDrawable)?.setColor((col and 0x00FFFFFF) or 0x2E000000)

        vArrow.visibility = View.GONE // not INVISIBLE: the unit moves up next to the value
        vTrend.setText(R.string.dash_meter_reading)
        vTrend.setTextColor(cOnSurface)
        showAge()
    }

    private fun showAge() {
        if (latestTime == 0L) return
        val now = System.currentTimeMillis() / 1000L
        val ageSec = now - latestTime
        val res = ctx.resources
        val age = when {
            ageSec < 60 -> ctx.getString(R.string.dash_just_now)
            ageSec < 3600 -> (ageSec / 60).toInt().let { res.getQuantityString(R.plurals.dash_minutes_ago, it, it) }
            // Sensor values keep counting hours; only a meter reading stays on top for days.
            ageSec < 86400 || !latestIsMeter -> (ageSec / 3600).toInt().let { res.getQuantityString(R.plurals.dash_hours_ago, it, it) }
            else -> (ageSec / 86400).toInt().let { res.getQuantityString(R.plurals.dash_days_ago, it, it) }
        }
        if (latestIsMeter) {
            vDetail.text = whenLabel(latestTime, now) + " · " + age
            return
        }
        val rate = trendRate
        vDetail.text = if (rate == null) age else {
            val perMin = if (mmol) String.format(Locale.getDefault(), "%+.2f", rate / MGDL_PER_MMOL)
                else String.format(Locale.getDefault(), "%+.1f", rate)
            ctx.getString(R.string.dash_rate, perMin, unitLabel()) + " · " + age
        }
    }

    /** When a meter reading was taken: "Today 08:12", "Yesterday 21:40", else short weekday, date and time. */
    private fun whenLabel(time: Long, now: Long): String {
        val ms = time * 1000L
        val today = startOfDay(now)
        if (time >= startOfDay(today - 3600L)) { // an hour before today's midnight is yesterday
            val clock = DateFormat.getTimeFormat(ctx).format(Date(ms))
            return ctx.getString(if (time >= today) R.string.dash_today_at else R.string.dash_yesterday_at, clock)
        }
        return DateUtils.formatDateTime(
            ctx, ms,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME,
        )
    }

    private fun parseToMgdl(value: String?): Float {
        val v = value?.replace(',', '.')?.trim()?.toFloatOrNull() ?: return 0f
        return if (mmol) v * MGDL_PER_MMOL.toFloat() else v
    }

    // ---------------------------------------------------------------- chart

    private val rangeProvider = object : CartesianLayerRangeProvider {
        override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = chartMaxX
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

    /** Meter readings are joined by a thin line; the dots on it carry the range colour. */
    private val meterLine = LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(cOutline)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 1.5f, cap = Paint.Cap.ROUND),
    )

    private val meterDotLines = HashMap<Int, LineCartesianLayer.Line>()

    /** Dots only, in the colour of one meterLevel(); smaller dots for the longer periods. */
    private fun meterDotLine(level: Int): LineCartesianLayer.Line =
        meterDotLines.getOrPut(level * 100 + meterDotDp.roundToInt()) {
            val point = LineCartesianLayer.Point(
                ShapeComponent(Fill(rangeColors[level]), CorneredShape.Pill, strokeFill = Fill(cCard), strokeThicknessDp = 1.5f),
                sizeDp = meterDotDp,
            )
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(Fill.Transparent),
                pointProvider = LineCartesianLayer.PointProvider.single(point),
            )
        }

    /** Time (ms) at chart position x. */
    private fun timeAt(x: Double) = (chartStart + (x * chartUnit).roundToLong()) * 1000L

    private fun timeLabel(x: Double): String = DateFormat.getTimeFormat(ctx).format(Date(timeAt(x)))

    /** In the marker of a meter reading: numeric date and time, "11/12, 08:12". */
    private fun dateTimeLabel(x: Double): String = DateUtils.formatDateTime(
        ctx, timeAt(x),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_SHOW_TIME,
    )

    /** Axis labels of the periods of days: day and month as numbers, "11/12" or "12.11.". */
    private val dayFormat by lazy {
        java.text.SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "Md"), Locale.getDefault())
    }

    /**
     * These labels are a whole number of days before today's midnight, which is an hour
     * off midnight across a daylight saving change: three hours later is the day meant.
     */
    private fun dayLabel(x: Double): String = dayFormat.format(Date(timeAt(x) + 3 * 3600_000L))

    /** Axis labels sit on whole hours: "10 PM" or "22" depending on the locale. */
    private val hourFormat by lazy {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(ctx)) "HH" else "ha")
        java.text.SimpleDateFormat(pattern, Locale.getDefault())
    }

    private fun hourLabel(x: Double): String = hourFormat.format(Date(timeAt(x)))

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
            valueFormatter = CartesianValueFormatter { _: CartesianMeasuringContext, value: Double, _: Axis.Position.Vertical? ->
                if (dateLabels) dayLabel(value) else hourLabel(value)
            },
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
                    if (chartMeter) text + " " + unitLabel() + " · " + dateTimeLabel(target.x)
                    else text + " · " + timeLabel(target.x)
                }
            },
            labelPosition = DefaultCartesianMarker.LabelPosition.AbovePoint,
            guideline = LineComponent(Fill(cOutlineVariant), thicknessDp = 1f),
        )
        val layer = LineCartesianLayer(
            lineProvider = { key, _, _ ->
                when (key) {
                    LAST_KEY -> lastDotLine()
                    METER_KEY -> meterLine
                    in METER_DOT_KEYS -> meterDotLine(METER_DOT_KEYS.indexOf(key))
                    else -> glucoseLine
                }
            },
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
        val r = d.plot
        val meter = d.meterMode
        chartStart = d.start
        chartUnit = d.xUnit
        // Periods of days start a little early (meterStart), so they run up to now.
        chartMaxX = if (d.hours <= 24) d.hours * 3600.0 / d.xUnit else ceil((d.now - d.start).toDouble() / d.xUnit)
        chartMeter = meter
        val lowUnit = toUnit(targetLow.toDouble())
        val highUnit = toUnit(targetHigh.toDouble())
        vChartCaption.text = ctx.getString(
            if (meter) R.string.dash_meter_chart_caption else R.string.dash_chart_caption,
            fmt(targetLow.toDouble()), fmt(targetHigh.toDouble()), unitLabel(),
        )
        val frameHeight = dp(if (r.size == 0) 150f else 260f)
        if (vChartFrame.layoutParams.height != frameHeight) {
            vChartFrame.layoutParams.height = frameHeight
            vChartFrame.requestLayout()
        }
        if (r.size == 0) {
            vChart.visibility = View.INVISIBLE
            vChartEmpty.visibility = View.VISIBLE
            vChartCaption.visibility = View.GONE
            return
        }
        vChart.visibility = View.VISIBLE
        vChartEmpty.visibility = View.GONE
        vChartCaption.visibility = View.VISIBLE

        // Sensor values: a line, broken where values are missing. Meter readings: one thin
        // line through all of them, and the readings again as dots per range colour.
        var dataMin = Double.MAX_VALUE
        var dataMax = -Double.MAX_VALUE
        val segments = ArrayList<Pair<List<Double>, List<Double>>>()
        var xs = ArrayList<Double>()
        var ys = ArrayList<Double>()
        val dotXs = List(rangeColors.size) { ArrayList<Double>() }
        val dotYs = List(rangeColors.size) { ArrayList<Double>() }
        var prevT = Long.MIN_VALUE
        for (i in 0 until r.size) {
            val t = r.time[i]
            if (!meter && prevT != Long.MIN_VALUE && t - prevT > MAX_GAP_SEC && xs.isNotEmpty()) {
                segments.add(xs to ys)
                xs = ArrayList()
                ys = ArrayList()
            }
            prevT = t
            // A reading of this very minute (or from a meter whose clock runs ahead) stays on the chart's right edge.
            val x = if (meter) min(chartMaxX, (t - d.start).toDouble() / d.xUnit) else ((t - d.start) / 60.0 * 100).roundToInt() / 100.0
            val y = toUnit(r.mgdl[i].toDouble())
            dataMin = min(dataMin, y)
            dataMax = max(dataMax, y)
            xs.add(x)
            ys.add(y)
            if (meter) {
                val level = meterLevel(r.mgdl[i])
                dotXs[level].add(x)
                dotYs[level].add(y)
            }
        }
        if (xs.isNotEmpty()) segments.add(xs to ys)

        // The larger dot on the newest value.
        val newest = r.size - 1
        dotColor = when {
            meter -> rangeColors[meterLevel(r.mgdl[newest])]
            d.now - r.time[newest] > STALE_SEC -> cOnSurfaceVariant
            else -> statusColor(r.mgdl[newest])
        }
        meterDotDp = when {
            d.hours <= 168 -> 10f
            d.hours <= 720 -> 8f
            else -> 7f
        }

        // y range: data and target band, rounded to whole label steps
        val lo = min(dataMin, lowUnit)
        val hi = max(dataMax, highUnit)
        val step = if (mmol) (if (hi - lo > 12) 4.0 else 2.0) else (if (hi - lo > 300) 100.0 else 50.0)
        val pad = if (mmol) 0.5 else 9.0
        yStep = step
        minY = max(0.0, floor((lo - pad) / step) * step)
        maxY = ceil((hi + pad) / step) * step
        if (maxY - minY < 3 * step) maxY = minY + 3 * step

        if (d.hours <= 24) {
            // time labels on whole hours (every 1, 2, 3 or 4 hours)
            dateLabels = false
            val hoursPerLabel = when (d.hours) {
                3 -> 1
                6 -> 1
                12 -> 3
                else -> 4
            }
            labelSpacing = hoursPerLabel * 3600 / d.xUnit
            val cal = Calendar.getInstance()
            cal.timeInMillis = d.start * 1000L
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            while (cal.timeInMillis / 1000L < d.start || cal.get(Calendar.HOUR_OF_DAY) % hoursPerLabel != 0)
                cal.add(Calendar.HOUR_OF_DAY, 1)
            labelOffset = max(0, ((cal.timeInMillis / 1000L - d.start) / d.xUnit).toInt())
        } else {
            // date labels on midnights, counted back from today: every 2, 7 or 21 days
            dateLabels = true
            val daysPerLabel = when {
                d.hours <= 168 -> 2
                d.hours <= 720 -> 7
                else -> 21
            }
            labelSpacing = daysPerLabel * 86400 / d.xUnit
            labelOffset = ((startOfDay(d.now) - d.start) / d.xUnit).toInt() % labelSpacing
        }

        val lastX = xs.last()
        val lastY = ys.last()
        modelProducer.runTransaction {
            lineSeries {
                if (meter) {
                    series(xs, ys, key = METER_KEY)
                    for (level in dotXs.indices)
                        if (dotXs[level].isNotEmpty()) series(dotXs[level], dotYs[level], key = METER_DOT_KEYS[level])
                } else {
                    for ((index, seg) in segments.withIndex()) series(seg.first, seg.second, key = index)
                }
                series(listOf(lastX), listOf(lastY), key = LAST_KEY)
            }
        }
    }

    // ---------------------------------------------------------------- meter readings

    /** The latest finger-stick readings, newest first, whether a sensor is used or not. */
    private fun showMeterList(d: Data) {
        vMeterRows.removeAllViews()
        val m = d.meter
        vMeterCard.visibility = if (m.size == 0) View.GONE else View.VISIBLE
        for (i in m.size - 1 downTo max(0, m.size - METER_LIST_ROWS)) vMeterRows.addView(meterRow(m.time[i], m.mgdl[i], d.now))
    }

    private fun meterRow(time: Long, mgdl: Float, now: Long): View {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8f), 0, dp(8f))
        val dot = View(ctx)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(rangeColors[meterLevel(mgdl)])
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(10f), dp(10f)).apply { marginEnd = dp(12f) })
        val value = TextView(ctx)
        value.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        value.setTypeface(value.typeface, Typeface.BOLD)
        value.setTextColor(cOnSurface)
        value.text = fmt(mgdl.toDouble()) + " " + unitLabel()
        row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The rest of the row, so a long date wraps instead of being cut off.
        val taken = TextView(ctx)
        taken.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        taken.setTextColor(cOnSurfaceVariant)
        taken.gravity = Gravity.END
        taken.text = whenLabel(time, now)
        row.addView(taken, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12f) })
        row.contentDescription = value.text.toString() + ", " + taken.text
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        return row
    }

    // ---------------------------------------------------------------- summary

    private fun showSummary(s: Summary?) {
        val res = ctx.resources
        vTirLegend.removeAllViews()
        vMetrics.removeAllViews()
        // Nothing to summarise: leave the card out instead of showing an empty box.
        vSummaryCard.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) {
            vSummaryTitle.text = res.getQuantityString(R.plurals.dash_summary_days, SUMMARY_DAYS, SUMMARY_DAYS)
            vTirValue.text = ""
            vTirLabel.setText(R.string.dash_summary_empty)
            tirBar.visibility = View.GONE
            vSummaryNote.visibility = View.GONE
            vSummaryDivider.visibility = View.GONE
            return
        }
        tirBar.visibility = View.VISIBLE
        vSummaryNote.visibility = View.VISIBLE
        vSummaryDivider.visibility = View.VISIBLE
        val days = (s.spanSec / 86400L).toInt()
        vSummaryTitle.text = if (days >= SUMMARY_DAYS - 1) res.getQuantityString(R.plurals.dash_summary_days, SUMMARY_DAYS, SUMMARY_DAYS)
            else if (days >= 1) res.getQuantityString(R.plurals.dash_summary_days, days + 1, days + 1)
            else (s.spanSec / 3600L).toInt().let { res.getQuantityString(R.plurals.dash_summary_hours, it, it) }

        vTirLabel.text = ctx.getString(R.string.dash_tir_label, fmt(70.0), fmt(180.0), unitLabel())
        showBands(s.bands)

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

    private fun pct(fraction: Double) = (fraction * 100).roundToInt()

    /** The big in-range figure, the stacked bar and its legend: shares of time, or of meter readings. */
    private fun showBands(bands: DoubleArray) {
        vTirValue.text = String.format(Locale.getDefault(), "%d%%", pct(bands[2]))
        vTirValue.setTextColor(cInRange)
        tirBar.set(bands, rangeColors)

        val names = intArrayOf(R.string.dash_band_very_low, R.string.dash_band_low, R.string.dash_band_in_range, R.string.dash_band_high, R.string.dash_band_very_high)
        // The consensus limits as published for each unit: converting 181 mg/dL would show
        // "10.0" as the start of High, the same figure as the end of In range.
        fun mm(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
        val ranges = if (mmol) arrayOf(
            "< " + mm(3.0),
            mm(3.0) + "–" + mm(3.8),
            mm(3.9) + "–" + mm(10.0),
            mm(10.1) + "–" + mm(13.9),
            "> " + mm(13.9),
        ) else arrayOf(
            "< " + fmt(54.0),
            fmt(54.0) + "–" + fmt(69.0),
            fmt(70.0) + "–" + fmt(180.0),
            fmt(181.0) + "–" + fmt(250.0),
            "> " + fmt(250.0),
        )
        for (i in 4 downTo 0) vTirLegend.addView(legendRow(rangeColors[i], ctx.getString(names[i]), ranges[i], pct(bands[i])))
    }

    /**
     * Meter mode: the last 30 days of finger-stick readings. Shares count readings, which
     * are spot checks, so no GMI and no variability here; the note says what it is.
     */
    private fun showMeterSummary(s: MeterSummary?) {
        vTirLegend.removeAllViews()
        vMetrics.removeAllViews()
        vSummaryCard.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) return // too few readings to say anything
        tirBar.visibility = View.VISIBLE
        vSummaryNote.visibility = View.VISIBLE
        vSummaryDivider.visibility = View.VISIBLE
        vSummaryTitle.text = ctx.resources.getQuantityString(R.plurals.dash_summary_days, METER_SUMMARY_DAYS, METER_SUMMARY_DAYS)
        vTirLabel.text = ctx.getString(R.string.dash_meter_tir_label, fmt(70.0), fmt(180.0), unitLabel())
        showBands(s.bands)

        // Four figures in one row: plain labels, the unit is in the line above.
        vMetrics.addView(metric(fmt(s.meanMgdl), ctx.getString(R.string.dash_metric_average)))
        vMetrics.addView(metric(String.format(Locale.getDefault(), "%d", s.count), ctx.getString(R.string.dash_metric_readings)))
        vMetrics.addView(metric(fmt(s.lowestMgdl.toDouble()), ctx.getString(R.string.dash_metric_lowest)))
        vMetrics.addView(metric(fmt(s.highestMgdl.toDouble()), ctx.getString(R.string.dash_metric_highest)))

        vSummaryNote.text = ctx.getString(
            R.string.dash_meter_summary_note,
            s.count, fmt(targetLow.toDouble()), fmt(targetHigh.toDouble()), unitLabel(), pct(s.inTarget),
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
        showRangeButtons()
        vHours.addOnButtonCheckedListener { _, id, checked -> if (checked) rangeChosen(id) }
        content.findViewById<View>(R.id.dash_fullgraph).setOnClickListener { showFullGraph(act) }
        content.findViewById<View>(R.id.dash_connect).setOnClickListener { Menus.openSensors(act) }
        content.findViewById<View>(R.id.dash_add_meter).setOnClickListener { Menus.openMeters(act) }
        content.findViewById<View>(R.id.dash_blood_label).setOnClickListener { Menus.openCalibration(act) }
        content.findViewById<View>(R.id.dash_meter_all).setOnClickListener { Menus.openAmounts(act) }
        content.findViewById<View>(R.id.dash_q_sensors).setOnClickListener { Menus.openSensors(act) }
        content.findViewById<View>(R.id.dash_q_amount).setOnClickListener { Menus.openNewAmount(act) }
        content.findViewById<View>(R.id.dash_q_stats).setOnClickListener { Menus.openStatistics(act) }
        content.findViewById<View>(R.id.dash_q_alarms).setOnClickListener { Menus.openAlarms(act) }
        setupChart()
        vTirLabel.minHeight = (20 * density).toInt()
    }

    companion object {
        private const val LOG_ID = "Dashboard"
        private const val PREF_HOURS = "hours"
        private const val PREF_METER_HOURS = "meter_hours"
        private const val PREF_METER_MODE = "meter_mode"
        private val HOUR_CHOICES = intArrayOf(3, 6, 12, 24)
        private val HOUR_LABELS = intArrayOf(R.string.dash_h3, R.string.dash_h6, R.string.dash_h12, R.string.dash_h24)

        // Meter mode: 24 hours, 7, 30 and 90 days.
        private val METER_HOUR_CHOICES = intArrayOf(24, 7 * 24, 30 * 24, 90 * 24)
        private val METER_HOUR_LABELS = intArrayOf(R.string.dash_h24, R.string.dash_m7d, R.string.dash_m30d, R.string.dash_m90d)
        private const val METER_DAYS = 90 // how far back meter readings are read
        private const val METER_SUMMARY_DAYS = 30
        private const val METER_SUMMARY_MIN = 3 // readings needed for a summary
        private const val METER_LIST_ROWS = 5
        private const val CLOCK_SLACK_SEC = 5 * 60L // a reading may be this far ahead of the phone's clock
        private const val SUMMARY_DAYS = 14
        private const val MGDL_PER_MMOL = 18.0182
        private const val MAX_GAP_SEC = 20 * 60L
        private const val STALE_SEC = 15 * 60L
        private const val DUPLICATE_SEC = 30L
        private const val TICK_MS = 30_000L
        private const val RELOAD_MS = 5 * 60_000L
        private const val LAST_KEY = "last"
        private const val METER_KEY = "meter"
        private val METER_DOT_KEYS: List<Any> = listOf("dot0", "dot1", "dot2", "dot3", "dot4") // per meterLevel()

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
