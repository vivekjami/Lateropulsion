package com.lateropulsion.app.ui.hmd

import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Display
import android.view.View
import com.lateropulsion.app.session.SessionController
import com.lateropulsion.engine.render.OverlayGeometry
import com.lateropulsion.engine.render.OverlayInput
import com.lateropulsion.engine.render.Primitive
import com.lateropulsion.engine.render.RenderStateHolder
import com.lateropulsion.engine.render.RenderTelemetry
import kotlin.math.cos
import kotlin.math.sin

/**
 * Therapist mirror on a cast/second display (IMPLEMENTATION Phase 4): the same gravity-locked cue
 * geometry the patient sees, the live angle, block status and frame telemetry. Plain Canvas so it costs
 * the render thread nothing.
 */
class TherapistMirrorPresentation(ctx: Context, display: Display, private val controller: SessionController, private val states: RenderStateHolder) : Presentation(ctx, display) {
    private lateinit var view: MirrorView
    @Volatile private var telemetry: RenderTelemetry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = MirrorView(context)
        setContentView(view)
    }

    fun update(t: RenderTelemetry) { telemetry = t; if (::view.isInitialized) view.postInvalidateOnAnimation() }

    private inner class MirrorView(c: Context) : View(c) {
        private val bg = Paint().apply { color = Color.rgb(30, 30, 33) }
        private val text = Paint().apply { color = Color.WHITE; textSize = 36f; isAntiAlias = true }
        private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true; strokeCap = Paint.Cap.ROUND }
        private val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        private val path = android.graphics.Path()

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
            val st = controller.state.value
            val rs = states.get()
            val cx = width / 2f; val cy = height / 2f; val scale = height / 2.4f
            for (p in OverlayGeometry.build(OverlayInput(st.thetaHeadDeg, st.thetaDeg, rs, !st.trackingLost))) {
                when (p) {
                    is Primitive.Line -> { line.color = argb(p.color); line.strokeWidth = (p.width * scale).coerceAtLeast(3f); canvas.drawLine(cx + p.x1 * scale, cy - p.y1 * scale, cx + p.x2 * scale, cy - p.y2 * scale, line) }
                    is Primitive.Wedge -> { fill.color = argb(p.color); path.reset(); path.moveTo(cx + p.cx * scale,
                        cy - p.cy * scale); var a = p.a0; while (a <= p.a1) { path.lineTo(cx + (p.cx + p.radius * cos(a)) * scale,
                        cy - (p.cy + p.radius * sin(a)) * scale); a += 0.05f }; path.close(); canvas.drawPath(path, fill) }
                    is Primitive.Ring -> { line.color = argb(p.color); line.strokeWidth = (p.rOuter - p.rInner) * scale; val r = (p.rInner + p.rOuter) / 2 * scale; canvas.drawArc(cx + p.cx * scale - r,
                        cy - p.cy * scale - r, cx + p.cx * scale + r, cy - p.cy * scale + r, -Math.toDegrees(p.a0.toDouble()).toFloat(), -Math.toDegrees((p.a1 - p.a0).toDouble()).toFloat(), false, line) }
                    is Primitive.Marker -> { fill.color = argb(p.color); canvas.drawCircle(cx + p.x * scale, cy - p.y * scale, p.size * scale, fill) }
                }
            }
            canvas.drawText(String.format(java.util.Locale.ROOT, "θ %+.1f°  %s  episodes %d  block %d/%d %ds", st.thetaDeg, if (st.inBand) "IN BAND" else "OUT", st.episodes,
                st.blockIndex + 1, st.blockCount, st.blockRemainingS), 24f, 48f, text)
            telemetry?.let { canvas.drawText(String.format(java.util.Locale.ROOT, "frame %.1f ms  pose age %.1f ms  m2p est %.0f ms  rot %+.1f°%s", it.frameTimeMs, it.poseAgeMs,
                it.motionToPhotonEstMs, it.appliedRotationDeg, if (it.neutral) "  NEUTRAL" else ""), 24f, height - 24f, text) }
            if (st.trackingLost) canvas.drawText("TRACKING LOST", 24f, 96f, text.apply { color = Color.RED })
            text.color = Color.WHITE
        }

        private fun argb(c: com.lateropulsion.engine.render.Rgba): Int = Color.argb((c.a * 255).toInt(), (c.r * 255).toInt(), (c.g * 255).toInt(), (c.b * 255).toInt())
    }
}
