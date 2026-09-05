package com.lateropulsion.engine.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns overlay primitives into triangles in a caller-owned FloatArray — six floats per vertex
 * (x, y, r, g, b, a) — with no per-frame allocation (ARCHITECTURE §5).
 */
public class OverlayTessellator(capacityVertices: Int = 12_000) {
    public val data: FloatArray = FloatArray(capacityVertices * FLOATS_PER_VERTEX)
    public var vertexCount: Int = 0
        private set
    public var overflowed: Boolean = false
        private set

    public fun reset() { vertexCount = 0; overflowed = false }

    public fun add(primitives: List<Primitive>) { for (p in primitives) add(p) }

    public fun add(p: Primitive) {
        when (p) {
            is Primitive.Line -> line(p.x1, p.y1, p.x2, p.y2, p.width, p.color)
            is Primitive.Wedge -> wedge(p.cx, p.cy, p.radius, p.a0, p.a1, p.color)
            is Primitive.Ring -> ring(p.cx, p.cy, p.rInner, p.rOuter, p.a0, p.a1, p.color)
            is Primitive.Marker -> {
                val h = p.size / 2
                quad(p.x - h, p.y - h, p.x + h, p.y - h, p.x + h, p.y + h, p.x - h, p.y + h, p.color)
            }
        }
    }

    /** Seven-segment digits so the deviation readout needs no font atlas. */
    public fun number(value: Double, x: Float, y: Float, h: Float, color: Rgba, decimals: Int = 0) {
        var text = if (decimals == 0) Math.round(value).toString() else String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
        if (!text.startsWith("-")) text = "+$text"
        var cx = x
        val w = h * 0.55f
        val gap = h * 0.25f
        for (ch in text) {
            when (ch) {
                '-' -> segment(cx, y, w, h, 6, color)
                '+' -> Unit
                '.' -> quad(cx + w * 0.4f, y - h / 2, cx + w * 0.6f, y - h / 2, cx + w * 0.6f, y - h / 2 + h * 0.12f, cx + w * 0.4f, y - h / 2 + h * 0.12f, color)
                in '0'..'9' -> { val mask = DIGITS[ch - '0']; for (i in 0 until 7) if (mask and (1 shl i) != 0) segment(cx, y, w, h, i, color) }
            }
            cx += if (ch == '.') w * 0.5f else w + gap
        }
        // degree sign
        val d = h * 0.12f
        quad(cx, y + h / 2 - 2 * d, cx + 2 * d, y + h / 2 - 2 * d, cx + 2 * d, y + h / 2, cx, y + h / 2, color)
    }

    // Segment indices: 0 top, 1 top-right, 2 bottom-right, 3 bottom, 4 bottom-left, 5 top-left, 6 middle.
    private fun segment(x: Float, y: Float, w: Float, h: Float, idx: Int, color: Rgba) {
        val t = h * 0.12f
        val top = y + h / 2; val mid = y; val bot = y - h / 2
        when (idx) {
            0 -> quad(x, top - t, x + w, top - t, x + w, top, x, top, color)
            1 -> quad(x + w - t, mid, x + w, mid, x + w, top, x + w - t, top, color)
            2 -> quad(x + w - t, bot, x + w, bot, x + w, mid, x + w - t, mid, color)
            3 -> quad(x, bot, x + w, bot, x + w, bot + t, x, bot + t, color)
            4 -> quad(x, bot, x + t, bot, x + t, mid, x, mid, color)
            5 -> quad(x, mid, x + t, mid, x + t, top, x, top, color)
            6 -> quad(x, mid - t / 2, x + w, mid - t / 2, x + w, mid + t / 2, x, mid + t / 2, color)
        }
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, c: Rgba) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len < 1e-6f) return
        val nx = -dy / len * width / 2; val ny = dx / len * width / 2
        quad(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, c)
    }

    private fun wedge(cx: Float, cy: Float, r: Float, a0: Float, a1: Float, c: Rgba) {
        val n = segmentsFor(a0, a1)
        for (i in 0 until n) {
            val t0 = a0 + (a1 - a0) * i / n; val t1 = a0 + (a1 - a0) * (i + 1) / n
            tri(cx, cy, cx + r * cos(t0), cy + r * sin(t0), cx + r * cos(t1), cy + r * sin(t1), c)
        }
    }

    private fun ring(cx: Float, cy: Float, ri: Float, ro: Float, a0: Float, a1: Float, c: Rgba) {
        val n = segmentsFor(a0, a1)
        for (i in 0 until n) {
            val t0 = a0 + (a1 - a0) * i / n; val t1 = a0 + (a1 - a0) * (i + 1) / n
            quad(cx + ri * cos(t0), cy + ri * sin(t0), cx + ro * cos(t0), cy + ro * sin(t0), cx + ro * cos(t1), cy + ro * sin(t1), cx + ri * cos(t1), cy + ri * sin(t1), c)
        }
    }

    private fun segmentsFor(a0: Float, a1: Float): Int = (Math.abs(a1 - a0) / (2 * PI) * 96).toInt().coerceIn(1, 96)

    private fun quad(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, c: Rgba) {
        tri(x1, y1, x2, y2, x3, y3, c); tri(x1, y1, x3, y3, x4, y4, c)
    }

    private fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, c: Rgba) {
        if ((vertexCount + 3) * FLOATS_PER_VERTEX > data.size) { overflowed = true; return }
        v(x1, y1, c); v(x2, y2, c); v(x3, y3, c)
    }

    private fun v(x: Float, y: Float, c: Rgba) {
        val o = vertexCount * FLOATS_PER_VERTEX
        data[o] = x; data[o + 1] = y; data[o + 2] = c.r; data[o + 3] = c.g; data[o + 4] = c.b; data[o + 5] = c.a
        vertexCount++
    }

    public companion object {
        public const val FLOATS_PER_VERTEX: Int = 6
        // bit i = segment i lit
        private val DIGITS = intArrayOf(0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110, 0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111)
    }
}

/** Debounced band-exit / band-return edges for haptics and the episode marker (ARCHITECTURE §7.4). */
public class BandEdgeDetector(private val debounceNs: Long = 300_000_000L) {
    public enum class Edge { NONE, EXIT, RETURN }
    private var inBand = true
    private var lastEdgeNs = Long.MIN_VALUE

    public fun feed(tNs: Long, nowInBand: Boolean): Edge {
        if (nowInBand == inBand) return Edge.NONE
        if (lastEdgeNs != Long.MIN_VALUE && tNs - lastEdgeNs < debounceNs) return Edge.NONE
        inBand = nowInBand
        lastEdgeNs = tNs
        return if (nowInBand) Edge.RETURN else Edge.EXIT
    }

    public fun reset() { inBand = true; lastEdgeNs = Long.MIN_VALUE }
}
