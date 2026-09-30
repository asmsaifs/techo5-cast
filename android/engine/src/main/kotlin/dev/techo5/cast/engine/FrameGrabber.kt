package dev.techo5.cast.engine

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Video frames out (docs/android-app-plan.md 5.2, the GL option): the player renders into a
 * [SurfaceTexture]; each frame is drawn letterboxed into a small framebuffer on the GPU, read back,
 * and encoded as JPEG. The CPU only ever sees the small picture.
 *
 * Everything runs on one thread. A frame that arrives while a previous one is still being encoded is
 * coalesced by the SurfaceTexture (the older one is dropped), so nothing queues; frames above
 * [maxFps] are skipped before any GL work.
 *
 * [stampFor] maps a frame's presentation time to the clock time it is shown at.
 */
class FrameGrabber(
    private val outW: Int,
    private val outH: Int,
    private val stampFor: (presentationUs: Long) -> Long,
    private val onFrame: (stampUs: Long, jpeg: ByteArray) -> Unit,
) {
    @Volatile var quality = 80
    @Volatile var maxFps = 30

    /** How many frames reached the encoder, and how many were skipped for the frame-rate cap. */
    @Volatile var encoded = 0L; private set
    @Volatile var skipped = 0L; private set
    /** Average time spent per encoded frame on the grab thread (draw, read back, JPEG), in microseconds. */
    @Volatile var avgWorkUs = 0L; private set

    private val thread = HandlerThread("cast-grab").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var texture = 0
    private var fbo = 0
    private var fboTexture = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMatrix = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private val readBack: ByteBuffer = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
    private val bitmap: Bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    private val matrix = FloatArray(16)
    private val jpegOut = ByteArrayOutputStream(32 * 1024)

    @Volatile private var aspect = 16f / 9f // display aspect of the video, set by the player
    private var lastSentUs = -1L // presentation time of the last frame encoded; -1 before the first

    /** The surface to give the player. Blocks until the GL side is up. A producer that does not size its
     *  own buffers (a virtual display) needs [bufferW] x [bufferH]. */
    fun start(bufferW: Int = 0, bufferH: Int = 0): Surface {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                initGl()
                if (bufferW > 0 && bufferH > 0) surfaceTexture?.setDefaultBufferSize(bufferW, bufferH)
            } catch (t: Throwable) {
                failure = t
            }
            ready.countDown()
        }
        ready.await()
        failure?.let { throw IllegalStateException("cannot set up the frame grabber: ${it.message}", it) }
        return surface!!
    }

    /** The video's shape on screen (width/height, pixel aspect included), so it is letterboxed right. */
    fun setVideoAspect(aspect: Float) {
        if (aspect > 0f) this.aspect = aspect
    }

    /** The producer's size changed (a virtual display after a rotation). */
    fun setBufferSize(w: Int, h: Int) {
        handler.post { surfaceTexture?.setDefaultBufferSize(w, h) }
        setVideoAspect(w.toFloat() / h)
    }

    fun stop() {
        handler.post {
            surfaceTexture?.release()
            surface?.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    private fun initGl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // The small framebuffer every frame is drawn into and read back from.
        val ft = IntArray(1)
        GLES20.glGenTextures(1, ft, 0)
        fboTexture = ft[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, outW, outH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val fb = IntArray(1)
        GLES20.glGenFramebuffers(1, fb, 0)
        fbo = fb[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTexture, 0)
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer" }

        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uMatrix = GLES20.glGetUniformLocation(program, "uMatrix")

        val st = SurfaceTexture(texture)
        st.setOnFrameAvailableListener({ onFrameAvailable(it) }, handler)
        surfaceTexture = st
        surface = Surface(st)
    }

    private fun onFrameAvailable(st: SurfaceTexture) {
        // Always take the frame, even if it will be skipped, or the producer stalls on a full queue.
        st.updateTexImage()
        val presentationUs = st.timestamp / 1000
        val interval = 1_000_000L / maxFps.coerceAtLeast(1)
        // Frames come at the source's rate; keep at most maxFps of them. 2 ms of slack so a 30 fps
        // source with a 30 fps cap is not halved by rounding.
        if (lastSentUs >= 0 && presentationUs >= lastSentUs && presentationUs - lastSentUs < interval - 2000) {
            skipped++
            return
        }
        lastSentUs = presentationUs
        val t0 = System.nanoTime()

        st.getTransformMatrix(matrix)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // Letterbox: the biggest rectangle of the video's shape that fits.
        val boxAspect = outW.toFloat() / outH
        val w: Int
        val h: Int
        if (aspect > boxAspect) { w = outW; h = (outW / aspect).toInt() } else { h = outH; w = (outH * aspect).toInt() }
        GLES20.glViewport((outW - w) / 2, (outH - h) / 2, w, h)
        draw()

        readBack.rewind()
        GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBack)
        readBack.rewind()
        bitmap.copyPixelsFromBuffer(readBack)
        jpegOut.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut)

        encoded++
        val work = (System.nanoTime() - t0) / 1000
        avgWorkUs = if (encoded == 1L) work else (avgWorkUs * 15 + work) / 16
        onFrame(stampFor(presentationUs), jpegOut.toByteArray())
    }

    private fun draw() {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POSITIONS)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEXCOORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private companion object {
        // Vertices run top-left first: GL's origin is bottom-left and glReadPixels returns the bottom
        // row first, so drawing upside down here makes the read-back the right way up for a Bitmap.
        val POSITIONS: java.nio.FloatBuffer = floatBuffer(-1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f)
        val TEXCOORDS: java.nio.FloatBuffer = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floatBuffer(vararg v: Float): java.nio.FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(v).also { it.rewind() }

        const val VERTEX = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uMatrix * aTex).xy;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
        """
    }
}
