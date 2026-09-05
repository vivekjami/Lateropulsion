package com.lateropulsion.engine.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** EGL 1.4 / GLES 3.0 context on a dedicated render thread (ARCHITECTURE §5). GLES 3.0 is the floor so mid-range phones qualify. */
public class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    public fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "no ES3 EGL config" }
        config = configs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}" }
    }

    public fun createWindowSurface(window: Surface) {
        surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
    }

    public fun makeCurrent() { check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" } }

    public fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

    public fun surfaceSize(): Pair<Int, Int> {
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    public fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        surface = EGL14.EGL_NO_SURFACE; context = EGL14.EGL_NO_CONTEXT; display = EGL14.EGL_NO_DISPLAY
    }
}

public object GlUtil {
    public fun checkError(op: String) {
        val e = GLES30.glGetError()
        check(e == GLES30.GL_NO_ERROR) { "$op: glError 0x${Integer.toHexString(e)}" }
    }

    public fun floatBuffer(capacityFloats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(capacityFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    public fun floatBuffer(data: FloatArray): FloatBuffer = floatBuffer(data.size).apply { put(data); position(0) }

    public fun shortBuffer(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(data); position(0) }

    public fun genTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(target, ids[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(target, 0)
        return ids[0]
    }

    public fun genBuffer(): Int { val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); return ids[0] }

    public const val EXTERNAL_OES: Int = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
}

public class GlProgram(vertexSrc: String, fragmentSrc: String) {
    public val id: Int

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs); GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "link failed: ${GLES30.glGetProgramInfoLog(id)}" }
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
    }

    public fun use(): Unit = GLES30.glUseProgram(id)
    public fun attrib(name: String): Int = GLES30.glGetAttribLocation(id, name)
    public fun uniform(name: String): Int = GLES30.glGetUniformLocation(id, name)
    public fun release(): Unit = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "shader compile failed: ${GLES30.glGetShaderInfoLog(s)}\n$src" }
        return s
    }
}

/** GLSL ES 3.00 sources (ARCHITECTURE §7.2). Kept as constants so the shader test can compile them offscreen. */
public object Shaders {
    public const val PASSTHROUGH_VS: String = """#version 300 es
in vec2 aPos;
in vec2 aTex;
out vec2 vTex;
void main() { vTex = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /**
     * Rotation about the optical centre in isotropic space, over-scan zoom, lateral shift, neutral fill
     * outside the valid camera region, then the SurfaceTexture transform.
     */
    public const val PASSTHROUGH_FS: String = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uCamera;
uniform mat4 uTexMatrix;
uniform float uAngle;     // radians, image-content rotation (sign verified per device)
uniform vec2 uCenter;     // optical centre in [0,1]^2 image space
uniform float uAspect;    // displayed image aspect (w/h) so the rotation is isotropic
uniform float uZoom;      // over-scan crop factor >= 1
uniform float uShift;     // lateral prism-like offset, image units
uniform vec3 uFill;       // neutral grey: never a smeared edge
in vec2 vTex;
out vec4 fragColor;
void main() {
    float c = cos(uAngle), s = sin(uAngle);
    vec2 p = vTex - uCenter;
    p.x *= uAspect;
    p /= uZoom;
    vec2 r = vec2(c * p.x - s * p.y, s * p.x + c * p.y);
    r.x /= uAspect;
    r += uCenter;
    r.x += uShift;
    if (r.x < 0.0 || r.x > 1.0 || r.y < 0.0 || r.y > 1.0) {
        fragColor = vec4(uFill, 1.0);
    } else {
        vec2 t = (uTexMatrix * vec4(r, 0.0, 1.0)).xy;
        fragColor = texture(uCamera, t);
    }
}
"""

    public const val OVERLAY_VS: String = """#version 300 es
in vec2 aPos;
in vec4 aColor;
uniform float uAspect;
out vec4 vColor;
void main() { vColor = aColor; gl_Position = vec4(aPos.x / uAspect, aPos.y, 0.0, 1.0); }
"""

    public const val OVERLAY_FS: String = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() { fragColor = vColor; }
"""

    public const val DISTORTION_VS: String = """#version 300 es
in vec2 aPos;
in vec2 aTexR;
in vec2 aTexG;
in vec2 aTexB;
out vec2 vR; out vec2 vG; out vec2 vB;
void main() { vR = aTexR; vG = aTexG; vB = aTexB; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    public const val DISTORTION_FS: String = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vR; in vec2 vG; in vec2 vB;
out vec4 fragColor;
void main() {
    fragColor = vec4(texture(uTex, vR).r, texture(uTex, vG).g, texture(uTex, vB).b, 1.0);
}
"""
}
