package com.lateropulsion.engine.render

import android.graphics.SurfaceTexture
import android.opengl.GLES30
import com.lateropulsion.core.common.Angles
import com.lateropulsion.core.model.CueType
import com.lateropulsion.core.model.DeviceProfile
import com.lateropulsion.core.model.HeadsetProfile
import com.lateropulsion.core.model.MutablePose
import com.lateropulsion.core.model.VisualConfig
import com.lateropulsion.core.model.VisualMode
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Per-frame numbers for the debug overlay and the session log. */
public class RenderTelemetry {
    public var frameTimeMs: Double = 0.0
    public var poseAgeMs: Double = 0.0
    public var motionToPhotonEstMs: Double = 0.0
    public var appliedRotationDeg: Double = 0.0
    public var cameraFrames: Long = 0
    public var neutral: Boolean = true
}

/**
 * Stereo passthrough renderer (ARCHITECTURE §7.1): camera OES texture → per-eye correction pass into an
 * offscreen framebuffer → gravity-locked overlays → lens distortion pass to the window. All GL calls
 * happen on the render thread. Nothing here allocates per frame after [init].
 */
public class StereoRenderer(
    private val headset: HeadsetProfile,
    private val device: DeviceProfile,
    visual: VisualConfig,
    private val gridN: Int = 32,
    /** (sensorOrientation − displayRotation) / 90, so any phone's camera comes out upright in the landscape HMD. */
    @Volatile public var cameraQuarterTurns: Int = 0,
) {
    public val correction: CorrectionTransform = CorrectionTransform(visual.slewLimitDegPerS, visual.predictionClampMs / 1000.0)
    public val telemetry: RenderTelemetry = RenderTelemetry()
    private val overscan = headset.overscan.coerceAtLeast(visual.overscan)

    public lateinit var cameraTexture: SurfaceTexture
        private set
    private var oesTex = 0
    private var fbo = 0
    private var fboTex = 0
    private var width = 0
    private var height = 0
    private var eyeW = 0
    private var eyeAspect = 1f

    private lateinit var passthrough: GlProgram
    private lateinit var overlay: GlProgram
    private lateinit var distortion: GlProgram
    private var quadVbo = 0
    private var overlayVbo = 0
    private val meshVbo = IntArray(2)
    private val meshIbo = IntArray(2)
    private var meshIndexCount = 0
    private lateinit var meshes: Array<DistortionMesh>

    private val texMatrix = FloatArray(16)
    private val frameAvailable = AtomicBoolean(false)
    private val tessellator = OverlayTessellator()
    private lateinit var overlayBuffer: FloatBuffer
    private val overlayInput = OverlayInputMutable()
    private var lastFrameNs = 0L

    // uniform/attrib locations
    private var pAPos = 0; private var pATex = 0; private var pUTexMatrix = 0; private var pUAngle = 0; private var pUCenter = 0
    private var pUAspect = 0; private var pUZoom = 0; private var pUShift = 0; private var pUFill = 0; private var pUCamera = 0; private var pUQuarter = 0
    private var oAPos = 0; private var oAColor = 0; private var oUAspect = 0
    private var dAPos = 0; private var dATexR = 0; private var dATexG = 0; private var dATexB = 0; private var dUTex = 0

    public fun init(width: Int, height: Int) {
        this.width = width; this.height = height
        eyeW = width / 2
        eyeAspect = eyeW.toFloat() / height

        oesTex = GlUtil.genTexture(GlUtil.EXTERNAL_OES)
        cameraTexture = SurfaceTexture(oesTex)

        passthrough = GlProgram(Shaders.PASSTHROUGH_VS, Shaders.PASSTHROUGH_FS)
        pAPos = passthrough.attrib("aPos"); pATex = passthrough.attrib("aTex")
        pUTexMatrix = passthrough.uniform("uTexMatrix"); pUAngle = passthrough.uniform("uAngle"); pUCenter = passthrough.uniform("uCenter")
        pUAspect = passthrough.uniform("uAspect"); pUZoom = passthrough.uniform("uZoom"); pUShift = passthrough.uniform("uShift")
        pUFill = passthrough.uniform("uFill"); pUCamera = passthrough.uniform("uCamera"); pUQuarter = passthrough.uniform("uQuarterTurns")

        overlay = GlProgram(Shaders.OVERLAY_VS, Shaders.OVERLAY_FS)
        oAPos = overlay.attrib("aPos"); oAColor = overlay.attrib("aColor"); oUAspect = overlay.uniform("uAspect")

        distortion = GlProgram(Shaders.DISTORTION_VS, Shaders.DISTORTION_FS)
        dAPos = distortion.attrib("aPos"); dATexR = distortion.attrib("aTexR"); dATexG = distortion.attrib("aTexG"); dATexB = distortion.attrib("aTexB")
        dUTex = distortion.uniform("uTex")

        // full-viewport quad: x, y, u, v  (v flipped so image space has y up)
        val quad = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        quadVbo = GlUtil.genBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, GlUtil.floatBuffer(quad), GLES30.GL_STATIC_DRAW)

        overlayVbo = GlUtil.genBuffer()
        overlayBuffer = GlUtil.floatBuffer(tessellator.data.size)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, overlayVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tessellator.data.size * 4, null, GLES30.GL_STREAM_DRAW)

        meshes = arrayOf(
            DistortionMesh.forEye(true, gridN, headset.distortionK1, headset.distortionK2, headset.chromaticRedScale, headset.chromaticBlueScale, eyeAspect, headset.eyeCenterOffsetY.toFloat()),
            DistortionMesh.forEye(false, gridN, headset.distortionK1, headset.distortionK2, headset.chromaticRedScale, headset.chromaticBlueScale, eyeAspect, headset.eyeCenterOffsetY.toFloat()),
        )
        GLES30.glGenBuffers(2, meshVbo, 0); GLES30.glGenBuffers(2, meshIbo, 0)
        for (e in 0..1) {
            val m = meshes[e]
            val inter = FloatArray(m.vertexCount * 8)
            for (v in 0 until m.vertexCount) {
                inter[v * 8] = m.positions[v * 2]; inter[v * 8 + 1] = m.positions[v * 2 + 1]
                inter[v * 8 + 2] = m.texR[v * 2]; inter[v * 8 + 3] = m.texR[v * 2 + 1]
                inter[v * 8 + 4] = m.texG[v * 2]; inter[v * 8 + 5] = m.texG[v * 2 + 1]
                inter[v * 8 + 6] = m.texB[v * 2]; inter[v * 8 + 7] = m.texB[v * 2 + 1]
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo[e])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, inter.size * 4, GlUtil.floatBuffer(inter), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIbo[e])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 2, GlUtil.shortBuffer(m.indices), GLES30.GL_STATIC_DRAW)
            meshIndexCount = m.indices.size
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0); GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        // offscreen framebuffer for the two eyes before distortion
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        fboTex = GlUtil.genTexture(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GlUtil.checkError("init")
    }

    /** Called from the SurfaceTexture listener (posted to the render thread). */
    public fun onCameraFrameAvailable() { frameAvailable.set(true) }

    /**
     * @param neutral abort/idle: truthful passthrough, no correction, no cues, drawn this very frame.
     * @param poseAgeNs age of the pose sample relative to the frame timestamp, for prediction and telemetry.
     */
    public fun drawFrame(pose: MutablePose, state: RenderState, neutral: Boolean, frameTimeNs: Long, poseAgeNs: Long, cameraOk: Boolean) {
        val t0 = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1.0 / 60 else ((frameTimeNs - lastFrameNs) / 1e9).coerceIn(0.001, 0.1)
        lastFrameNs = frameTimeNs

        if (frameAvailable.getAndSet(false)) {
            cameraTexture.updateTexImage()
            cameraTexture.getTransformMatrix(texMatrix)
            telemetry.cameraFrames++
        }

        // Correction: predicted head roll, gain-scaled, slew-limited. Neutral resets instantly.
        val applied: Double
        if (neutral || state.idle || state.mode != VisualMode.COMPENSATED_VIEW) {
            if (neutral) correction.reset() else correction.update(0.0, 0.0, dt)
            applied = correction.appliedDeg
        } else {
            val omegaDegS = Angles.radToDeg(pose.wz) * (if (device.rollSign == 0) 1 else device.rollSign)
            val predicted = correction.predictTheta(pose.thetaDeg, omegaDegS, poseAgeNs / 1e9)
            applied = correction.update(state.gain, predicted, dt)
        }
        telemetry.appliedRotationDeg = applied
        telemetry.neutral = neutral
        val angleRad = Angles.degToRad(applied) * device.renderRotationSign

        // Pass 1: both eyes into the FBO.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glClearColor(0.12f, 0.12f, 0.13f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // Overlays are identical for both eyes (no disparity: cues live at optical infinity by design).
        tessellator.reset()
        if (!neutral && !state.idle) {
            overlayInput.set(pose, state)
            tessellator.add(OverlayGeometry.build(overlayInput.snapshot()))
            if (CueType.DEVIATION_READOUT in state.cues && state.showReadout) {
                tessellator.number(pose.thetaDeg - state.targetDeg, -0.35f, -0.62f, 0.14f, Rgba.WHITE, 0)
            }
        }

        for (eye in 0..1) {
            GLES30.glViewport(eye * eyeW, 0, eyeW, height)
            if (cameraOk) drawCamera(angleRad, state, eye) else drawNoCameraField()
            if (tessellator.vertexCount > 0) drawOverlay()
        }

        // Pass 2: lens distortion to the window.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        distortion.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glUniform1i(dUTex, 0)
        for (eye in 0..1) {
            GLES30.glViewport(eye * eyeW, 0, eyeW, height)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, meshVbo[eye])
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, meshIbo[eye])
            GLES30.glEnableVertexAttribArray(dAPos); GLES30.glVertexAttribPointer(dAPos, 2, GLES30.GL_FLOAT, false, 32, 0)
            GLES30.glEnableVertexAttribArray(dATexR); GLES30.glVertexAttribPointer(dATexR, 2, GLES30.GL_FLOAT, false, 32, 8)
            GLES30.glEnableVertexAttribArray(dATexG); GLES30.glVertexAttribPointer(dATexG, 2, GLES30.GL_FLOAT, false, 32, 16)
            GLES30.glEnableVertexAttribArray(dATexB); GLES30.glVertexAttribPointer(dATexB, 2, GLES30.GL_FLOAT, false, 32, 24)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, meshIndexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0); GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)

        telemetry.frameTimeMs = (System.nanoTime() - t0) / 1e6
        telemetry.poseAgeMs = poseAgeNs / 1e6
    }

    private fun drawCamera(angleRad: Double, state: RenderState, eye: Int) {
        passthrough.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GlUtil.EXTERNAL_OES, oesTex)
        GLES30.glUniform1i(pUCamera, 0)
        GLES30.glUniformMatrix4fv(pUTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniform1f(pUAngle, angleRad.toFloat())
        GLES30.glUniform2f(pUCenter, 0.5f, 0.5f + headset.eyeCenterOffsetY.toFloat() / 2f)
        GLES30.glUniform1f(pUAspect, eyeAspect)
        GLES30.glUniform1f(pUZoom, overscan.toFloat())
        GLES30.glUniform1f(pUShift, (state.lateralShift * if (eye == 0) 1 else -1).toFloat())
        GLES30.glUniform3f(pUFill, 0.12f, 0.12f, 0.13f)
        GLES30.glUniform1i(pUQuarter, ((cameraQuarterTurns % 4) + 4) % 4)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glEnableVertexAttribArray(pAPos); GLES30.glVertexAttribPointer(pAPos, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(pATex); GLES30.glVertexAttribPointer(pATex, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Camera stalled: flat grey field so nothing freezes or smears (ARCHITECTURE §15); the plumb line stays. */
    private fun drawNoCameraField() {
        GLES30.glClearColor(0.25f, 0.25f, 0.27f, 1f)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val vp = IntArray(4); GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vp, 0)
        GLES30.glScissor(vp[0], vp[1], vp[2], vp[3])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private fun drawOverlay() {
        overlay.use()
        GLES30.glUniform1f(oUAspect, eyeAspect)
        overlayBuffer.clear()
        overlayBuffer.put(tessellator.data, 0, tessellator.vertexCount * OverlayTessellator.FLOATS_PER_VERTEX)
        overlayBuffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, overlayVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, tessellator.vertexCount * OverlayTessellator.FLOATS_PER_VERTEX * 4, overlayBuffer)
        GLES30.glEnableVertexAttribArray(oAPos); GLES30.glVertexAttribPointer(oAPos, 2, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(oAColor); GLES30.glVertexAttribPointer(oAColor, 4, GLES30.GL_FLOAT, false, 24, 8)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, tessellator.vertexCount)
    }

    public fun release() {
        if (::passthrough.isInitialized) { passthrough.release(); overlay.release(); distortion.release() }
        GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0); GLES30.glDeleteBuffers(1, intArrayOf(overlayVbo), 0)
        GLES30.glDeleteBuffers(2, meshVbo, 0); GLES30.glDeleteBuffers(2, meshIbo, 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0); GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
        if (::cameraTexture.isInitialized) cameraTexture.release()
    }

    /** Mutable carrier so the per-frame OverlayInput is not re-allocated. */
    private class OverlayInputMutable {
        private var thetaHead = 0.0; private var theta = 0.0; private var valid = true; private var state = RenderState.NEUTRAL
        fun set(p: MutablePose, s: RenderState) { thetaHead = p.thetaHeadDeg; theta = p.thetaDeg; valid = com.lateropulsion.core.model.ValidityFlags.isValidForMetrics(p.flags); state = s }
        fun snapshot() = OverlayInput(thetaHead, theta, state, valid)
    }
}
