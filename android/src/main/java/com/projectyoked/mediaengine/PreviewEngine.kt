package com.projectyoked.mediaengine

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * PreviewEngine
 *
 * Drives RenderEngine in live-preview mode: sets up EGL against a provided Surface
 * (from a TextureView), then runs a 30-fps render loop on a dedicated HandlerThread.
 *
 * Audio is handled via a MediaPlayer pointed at the first audio/video URI in the config.
 * Video decoders and OpenGL resources all live on the render thread.
 */
class PreviewEngine(
    private val context: Context,
    @Volatile private var config: CompositeVideoComposer.CompositionConfig,
    private val surface: Surface,
    private val onTimeUpdate: (timeMs: Long) -> Unit,
    private val onPlaybackEnded: () -> Unit,
    private val onReady: () -> Unit,
    private val onError: (msg: String) -> Unit
) {
    companion object {
        private const val TAG = "PreviewEngine"
        private const val FRAME_INTERVAL_MS = 33L // ~30 fps
    }

    // ── Render thread ─────────────────────────────────────────────────────────
    private val renderThread = HandlerThread("media-engine-preview")
    private lateinit var renderHandler: Handler

    // ── OpenGL / EGL ─────────────────────────────────────────────────────────
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext  = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface  = EGL14.EGL_NO_SURFACE

    // ── Playback state ────────────────────────────────────────────────────────
    @Volatile private var renderEngine: RenderEngine? = null
    @Volatile private var isPlaying    = false
    @Volatile private var currentTimeUs = 0L
    private var durationUs   = 0L
    private var lastWallMs   = 0L

    // ── Audio ─────────────────────────────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun initialize() {
        renderThread.start()
        renderHandler = Handler(renderThread.looper)
        renderHandler.post { setupEGLAndEngine() }
    }

    fun play() {
        renderHandler.post {
            if (isPlaying) return@post
            isPlaying  = true
            lastWallMs = System.currentTimeMillis()
            scheduleNextFrame()
        }
        mediaPlayer?.let {
            it.seekTo(currentTimeUs.div(1000L).toInt())
            it.start()
        }
    }

    fun pause() {
        renderHandler.post { isPlaying = false }
        mediaPlayer?.pause()
    }

    fun seekTo(timeMs: Long) {
        renderHandler.post {
            currentTimeUs = timeMs * 1_000L
            if (!isPlaying) renderFrame()
            onTimeUpdate(timeMs)
        }
        mediaPlayer?.seekTo(timeMs.toInt())
    }

    /** Replace composition config (e.g. user added/moved a clip). Restarts engine. */
    fun updateConfig(newConfig: CompositeVideoComposer.CompositionConfig) {
        renderHandler.post {
            val wasPlaying = isPlaying
            isPlaying = false
            try {
                renderEngine?.release()
            } catch (_: Exception) {}
            config = newConfig
            durationUs = computeDuration(newConfig)
            val re = RenderEngine(context, newConfig)
            re.prepare()
            renderEngine = re
            renderFrame()
            if (wasPlaying) {
                isPlaying  = true
                lastWallMs = System.currentTimeMillis()
                scheduleNextFrame()
            }
        }
        setupAudio()
    }

    fun release() {
        isPlaying = false
        renderHandler.post {
            try {
                renderEngine?.release()
                renderEngine = null
            } catch (_: Exception) {}
            teardownEGL()
        }
        renderThread.quitSafely()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – EGL setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupEGLAndEngine() {
        try {
            // 1. EGL display + init
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
            checkEGL("eglInitialize")

            // 2. Choose config
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE,          8,
                EGL14.EGL_GREEN_SIZE,        8,
                EGL14.EGL_BLUE_SIZE,         8,
                EGL14.EGL_ALPHA_SIZE,        8,
                EGL14.EGL_RENDERABLE_TYPE,   EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,      EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val eglConfigs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, eglConfigs, 0, 1, numConfigs, 0)
            check(numConfigs[0] > 0) { "No EGL config found" }
            val eglConfig = eglConfigs[0]!!

            // 3. Create context (ES 2.0)
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            checkEGL("eglCreateContext")

            // 4. Window surface bound to the provided Surface
            val sfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, sfAttribs, 0)
            checkEGL("eglCreateWindowSurface")

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            checkEGL("eglMakeCurrent")

            // 5. Create + prepare RenderEngine (must be on the GL thread)
            val re = RenderEngine(context, config)
            re.prepare()
            renderEngine = re

            // 6. Duration + first frame
            durationUs = computeDuration(config)
            renderFrame()

            // 7. Audio
            setupAudio()

            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "Preview setup failed", e)
            onError(e.message ?: "Unknown error")
        }
    }

    private fun teardownEGL() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) { EGL14.eglDestroySurface(eglDisplay, eglSurface); eglSurface = EGL14.EGL_NO_SURFACE }
        if (eglContext != EGL14.EGL_NO_CONTEXT) { EGL14.eglDestroyContext(eglDisplay, eglContext); eglContext = EGL14.EGL_NO_CONTEXT }
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun checkEGL(label: String) {
        val err = EGL14.eglGetError()
        if (err != EGL14.EGL_SUCCESS) error("$label failed: 0x${err.toString(16)}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – Render loop
    // ─────────────────────────────────────────────────────────────────────────

    private fun scheduleNextFrame() {
        renderHandler.postDelayed({
            if (!isPlaying) return@postDelayed

            val now     = System.currentTimeMillis()
            val elapsed = (now - lastWallMs).coerceAtLeast(0L)
            lastWallMs  = now

            currentTimeUs += elapsed * 1_000L

            if (durationUs > 0L && currentTimeUs >= durationUs) {
                currentTimeUs = durationUs
                renderFrame()
                isPlaying = false
                onPlaybackEnded()
                return@postDelayed
            }

            renderFrame()
            scheduleNextFrame()
        }, FRAME_INTERVAL_MS)
    }

    private fun renderFrame() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        val re = renderEngine ?: return
        try {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            re.render(currentTimeUs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            onTimeUpdate(currentTimeUs / 1_000L)
        } catch (e: Exception) {
            Log.w(TAG, "renderFrame error", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal – Audio (best-effort: plays the first audio-bearing URI)
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupAudio() {
        mediaPlayer?.release()
        mediaPlayer = null

        // Prefer a dedicated AUDIO track; fall back to the first VIDEO clip for its audio
        val audioUri = config.tracks
            .firstOrNull { it.type == CompositeVideoComposer.TrackType.AUDIO }
            ?.clips?.firstOrNull()?.uri
            ?: config.tracks
                .firstOrNull { it.type == CompositeVideoComposer.TrackType.VIDEO }
                ?.clips?.firstOrNull()?.uri
            ?: return

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, Uri.parse(audioUri))
                prepare()
                isLooping = false
            }
            mediaPlayer = mp
        } catch (e: Exception) {
            Log.w(TAG, "Audio setup skipped: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun computeDuration(cfg: CompositeVideoComposer.CompositionConfig): Long =
        ((cfg.tracks.flatMap { it.clips }.maxOfOrNull { it.startTime + it.duration } ?: 0.0) * 1_000_000L).toLong()
}
