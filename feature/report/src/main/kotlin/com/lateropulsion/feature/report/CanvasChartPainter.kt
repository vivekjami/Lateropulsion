package com.lateropulsion.feature.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Draws a [ChartLayout] onto any android.graphics.Canvas — the Compose chart (via nativeCanvas) and the
 * PDF page use this same code path (ARCHITECTURE §12.2).
 */
public class CanvasChartPainter(private val density: Float = 1f) {
    public val palette: IntArray = intArrayOf(
        Color.rgb(31, 119, 180), Color.rgb(255, 127, 14), Color.rgb(44, 160, 44), Color.rgb(214, 39, 40),
        Color.rgb(148, 103, 189), Color.rgb(140, 86, 75), Color.rgb(127, 127, 127), Color.rgb(23, 190, 207),
    )
    private val axisPaint = Paint().apply { color = Color.DKGRAY; strokeWidth = 1f * density; isAntiAlias = true }
    private val gridPaint = Paint().apply { color = Color.argb(40, 0, 0, 0); strokeWidth = 1f * density }
    private val textPaint = Paint().apply { color = Color.DKGRAY; textSize = 9f * density; isAntiAlias = true }
    private val titlePaint = Paint().apply { color = Color.BLACK; textSize = 11f * density; isFakeBoldText = true; isAntiAlias = true }
    private val linePaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; isAntiAlias = true; strokeJoin = Paint.Join.ROUND }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val dash = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)

    public fun draw(canvas: Canvas, layout: ChartLayout) {
        val p = layout.plot
        val spec = layout.spec
        canvas.drawText(spec.title, p.left.toFloat(), (p.top - 12 * density).toFloat(), titlePaint)

        // bands (MDC etc.)
        for (b in spec.bands) {
            fillPaint.color = withAlpha(palette[b.colorIndex % palette.size], 40)
            canvas.drawRect(RectF(p.left.toFloat(), layout.py(b.y1).toFloat(), p.right.toFloat(), layout.py(b.y0).toFloat()), fillPaint)
            b.label?.let { canvas.drawText(it, (p.left + 4 * density).toFloat(), (layout.py(b.y1) + 9 * density).toFloat(), textPaint) }
        }
        // shaded x regions (episodes, rests)
        for (s in spec.shades) {
            fillPaint.color = withAlpha(palette[s.colorIndex % palette.size], 50)
            canvas.drawRect(RectF(layout.px(s.x0).toFloat(), p.top.toFloat(), layout.px(s.x1).toFloat(), p.bottom.toFloat()), fillPaint)
        }
        // grid + ticks
        for (t in layout.yAxis.ticks) {
            val y = layout.py(t).toFloat()
            canvas.drawLine(p.left.toFloat(), y, p.right.toFloat(), y, gridPaint)
            val label = ChartModel.formatTick(t)
            canvas.drawText(label, (p.left - 6 * density - textPaint.measureText(label)).toFloat(), y + 3 * density, textPaint)
        }
        for (t in layout.xAxis.ticks) {
            val x = layout.px(t).toFloat()
            canvas.drawLine(x, p.bottom.toFloat(), x, (p.bottom + 3 * density).toFloat(), axisPaint)
            val label = ChartModel.formatTick(t)
            canvas.drawText(label, x - textPaint.measureText(label) / 2, (p.bottom + 12 * density).toFloat(), textPaint)
        }
        layout.y2Axis?.let { y2 ->
            for (t in y2.ticks) {
                val y = layout.py(t, secondary = true).toFloat()
                canvas.drawText(ChartModel.formatTick(t), (p.right + 6 * density).toFloat(), y + 3 * density, textPaint)
            }
            spec.yLabelSecondary?.let { canvas.drawText(it, (p.right + 4 * density).toFloat(), (p.top - 2 * density).toFloat(), textPaint) }
        }
        canvas.drawRect(RectF(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat()), axisPaint.apply { style = Paint.Style.STROKE })
        canvas.drawText(spec.yLabel, p.left.toFloat(), (p.top - 2 * density).toFloat(), textPaint)
        canvas.drawText(spec.xLabel, (p.right - textPaint.measureText(spec.xLabel)).toFloat(), (p.bottom + 24 * density).toFloat(), textPaint)

        // reference lines
        for (r in spec.refLines) {
            linePaint.color = palette[r.colorIndex % palette.size]
            linePaint.pathEffect = if (r.dashed) dash else null
            val y = layout.py(r.y, r.secondaryAxis).toFloat()
            canvas.drawLine(p.left.toFloat(), y, p.right.toFloat(), y, linePaint)
            canvas.drawText(r.label, (p.right - textPaint.measureText(r.label) - 2 * density).toFloat(), y - 3 * density, textPaint)
        }
        linePaint.pathEffect = null
        // x markers (checkpoints)
        for ((x, label) in spec.markersX) {
            linePaint.color = Color.argb(120, 0, 0, 0); linePaint.pathEffect = dash
            val px = layout.px(x).toFloat()
            canvas.drawLine(px, p.top.toFloat(), px, p.bottom.toFloat(), linePaint)
            canvas.drawText(label, px + 2 * density, (p.top + 10 * density).toFloat(), textPaint)
        }
        linePaint.pathEffect = null
        // series
        for (s in spec.series) {
            linePaint.color = palette[s.colorIndex % palette.size]
            val path = Path()
            var first = true
            var lastY = 0f
            for (pt in s.points) {
                val x = layout.px(pt.x).toFloat(); val y = layout.py(pt.y, s.secondaryAxis).toFloat()
                if (first) { path.moveTo(x, y); first = false } else if (s.stepped) { path.lineTo(x, lastY); path.lineTo(x, y) } else path.lineTo(x, y)
                lastY = y
            }
            canvas.drawPath(path, linePaint)
            if (s.markers) {
                fillPaint.color = linePaint.color
                for (pt in s.points) canvas.drawCircle(layout.px(pt.x).toFloat(), layout.py(pt.y, s.secondaryAxis).toFloat(), 2.5f * density, fillPaint)
            }
        }
        // legend
        var lx = p.left.toFloat()
        val ly = (p.bottom + 33 * density).toFloat()
        for (s in spec.series) {
            fillPaint.color = palette[s.colorIndex % palette.size]
            canvas.drawRect(lx, ly - 7 * density, lx + 8 * density, ly + 1 * density, fillPaint)
            canvas.drawText(s.name, lx + 11 * density, ly, textPaint)
            lx += 14 * density + textPaint.measureText(s.name) + 10 * density
        }
    }

    /** Angle distribution bars: shows directional bias at a glance (ARCHITECTURE §12.1 page 2). */
    public fun drawHistogram(canvas: Canvas, rect: Rect, counts: List<Int>, edges: List<Double>, title: String) {
        canvas.drawText(title, rect.left.toFloat(), (rect.top - 8 * density).toFloat(), titlePaint)
        if (counts.isEmpty() || edges.size != counts.size + 1) return
        val max = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
        val w = rect.width / counts.size
        for (i in counts.indices) {
            val h = rect.height * counts[i] / max
            val mid = (edges[i] + edges[i + 1]) / 2
            fillPaint.color = if (mid < 0) palette[0] else palette[1]
            canvas.drawRect(RectF((rect.left + i * w + 1).toFloat(), (rect.bottom - h).toFloat(), (rect.left + (i + 1) * w - 1).toFloat(), rect.bottom.toFloat()), fillPaint)
        }
        canvas.drawLine(rect.left.toFloat(), rect.bottom.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), axisPaint)
        for (i in edges.indices step 2) {
            val x = (rect.left + i * w).toFloat()
            canvas.drawText(ChartModel.formatTick(edges[i]), x - 4 * density, (rect.bottom + 10 * density).toFloat(), textPaint)
        }
        val zeroX = (rect.left + (0.0 - edges.first()) / (edges.last() - edges.first()) * rect.width).toFloat()
        linePaint.color = Color.BLACK; canvas.drawLine(zeroX, rect.top.toFloat(), zeroX, rect.bottom.toFloat(), linePaint)
        canvas.drawText("← left    right →", (rect.left + 4 * density).toFloat(), (rect.top + 10 * density).toFloat(), textPaint)
    }

    private fun withAlpha(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
