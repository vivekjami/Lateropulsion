package com.lateropulsion.feature.report

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Pure chart geometry shared by the on-screen Compose chart and the PDF chart, so the two can never
 * disagree (ARCHITECTURE §12.2). Colours are indices into a palette owned by the painter.
 */
public data class Pt(val x: Double, val y: Double)

public data class Series(val name: String, val points: List<Pt>, val colorIndex: Int, val stepped: Boolean = false, val markers: Boolean = false, val secondaryAxis: Boolean = false)

public data class Shade(val x0: Double, val x1: Double, val colorIndex: Int, val label: String? = null)

public data class RefLine(val y: Double, val label: String, val colorIndex: Int, val dashed: Boolean = true, val secondaryAxis: Boolean = false)

public data class HBand(val y0: Double, val y1: Double, val colorIndex: Int, val label: String? = null)

public data class ChartSpec(
    val title: String,
    val xLabel: String,
    val yLabel: String,
    val series: List<Series>,
    val shades: List<Shade> = emptyList(),
    val refLines: List<RefLine> = emptyList(),
    val bands: List<HBand> = emptyList(),
    val markersX: List<Pair<Double, String>> = emptyList(),
    val yLabelSecondary: String? = null,
    val yMinHint: Double? = null,
    val yMaxHint: Double? = null,
    val xMinHint: Double? = null,
    val xMaxHint: Double? = null,
)

public data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    public val width: Double get() = right - left
    public val height: Double get() = bottom - top
}

public data class Axis(val min: Double, val max: Double, val ticks: List<Double>) {
    public fun frac(v: Double): Double = if (max == min) 0.5 else (v - min) / (max - min)
}

/** Everything a painter needs, in device pixels/points. */
public data class ChartLayout(
    val spec: ChartSpec,
    val plot: Rect,
    val xAxis: Axis,
    val yAxis: Axis,
    val y2Axis: Axis?,
) {
    public fun px(x: Double): Double = plot.left + xAxis.frac(x) * plot.width
    public fun py(y: Double, secondary: Boolean = false): Double {
        val axis = if (secondary) (y2Axis ?: yAxis) else yAxis
        return plot.bottom - axis.frac(y) * plot.height
    }
}

public object ChartModel {
    public fun layout(spec: ChartSpec, width: Double, height: Double, marginLeft: Double = 48.0, marginRight: Double = 40.0, marginTop: Double = 28.0, marginBottom: Double = 36.0): ChartLayout {
        val plot = Rect(marginLeft, marginTop, width - marginRight, height - marginBottom)
        val allX = spec.series.flatMap { s -> s.points.map { it.x } } + spec.shades.flatMap { listOf(it.x0, it.x1) } + spec.markersX.map { it.first }
        val primaryY = spec.series.filter { !it.secondaryAxis }.flatMap { s -> s.points.map { it.y } } +
            spec.refLines.filter { !it.secondaryAxis }.map { it.y } + spec.bands.flatMap { listOf(it.y0, it.y1) }
        val secondaryY = spec.series.filter { it.secondaryAxis }.flatMap { s -> s.points.map { it.y } } + spec.refLines.filter { it.secondaryAxis }.map { it.y }
        val x = niceAxis(spec.xMinHint ?: allX.minOrNull() ?: 0.0, spec.xMaxHint ?: allX.maxOrNull() ?: 1.0, 6)
        val y = niceAxis(spec.yMinHint ?: primaryY.minOrNull() ?: 0.0, spec.yMaxHint ?: primaryY.maxOrNull() ?: 1.0, 5)
        val y2 = if (secondaryY.isEmpty()) null else niceAxis(0.0, maxOf(1.0, secondaryY.maxOrNull() ?: 1.0), 5)
        return ChartLayout(spec, plot, x, y, y2)
    }

    /** "Nice" tick spacing (1, 2, 2.5, 5 × 10^n) covering [min, max]. */
    public fun niceAxis(min: Double, max: Double, targetTicks: Int): Axis {
        var lo = min; var hi = max
        if (!lo.isFinite()) lo = 0.0
        if (!hi.isFinite()) hi = lo + 1.0
        if (hi <= lo) hi = lo + 1.0
        val range = hi - lo
        val rough = range / targetTicks
        val mag = 10.0.pow(floor(log10(rough)))
        val norm = rough / mag
        val step = when {
            norm <= 1.0 -> 1.0
            norm <= 2.0 -> 2.0
            norm <= 2.5 -> 2.5
            norm <= 5.0 -> 5.0
            else -> 10.0
        } * mag
        val start = floor(lo / step) * step
        val end = ceil(hi / step) * step
        val ticks = ArrayList<Double>()
        var t = start
        while (t <= end + step / 2) { ticks += if (abs(t) < step * 1e-9) 0.0 else t; t += step }
        return Axis(start, end, ticks)
    }

    /** Min/max bucket downsampling that preserves peaks (episodes stay visible). */
    public fun downsample(points: List<Pt>, maxPoints: Int): List<Pt> {
        if (points.size <= maxPoints || maxPoints < 4) return points
        val buckets = maxPoints / 2
        val out = ArrayList<Pt>(maxPoints + 2)
        val per = points.size.toDouble() / buckets
        for (b in 0 until buckets) {
            val s = (b * per).toInt(); val e = ((b + 1) * per).toInt().coerceAtMost(points.size)
            if (s >= e) continue
            var mn = points[s]; var mx = points[s]
            for (i in s until e) { val p = points[i]; if (p.y < mn.y) mn = p; if (p.y > mx.y) mx = p }
            if (mn.x <= mx.x) { out += mn; if (mn !== mx) out += mx } else { out += mx; out += mn }
        }
        return out
    }

    public fun formatTick(v: Double): String = if (abs(v - Math.rint(v)) < 1e-9) Math.rint(v).toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
}
