package com.projectyoked.mediaengine

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView

/**
 * MediaEnginePreviewView
 *
 * An Expo native view that renders a CompositionConfig in real-time at ~30 fps via
 * PreviewEngine (EGL + RenderEngine + OpenGL ES 2.0).
 *
 * Architecture:
 *   - Native layer: renders VIDEO + IMAGE tracks (accurate, hardware-accelerated)
 *   - JS layer: renders TEXT/emoji overlays via `useCompositionOverlays` hook so
 *     they remain interactive (tap, drag, resize via Skia or RN gesture handlers)
 *
 * Props (set via Expo Module View definition):
 *   config      CompositionConfig as a raw Map (same shape as composeCompositeVideo)
 *   isPlaying   Boolean — drives play/pause
 *   muted       Boolean — mutes the MediaPlayer audio track
 *   currentTime Double  — controlled seek position (seconds); only honoured when NOT playing
 *
 * Events:
 *   onLoad          { duration: number }            — fired once engine is ready
 *   onTimeUpdate    { currentTime: number }         — ~30 fps while playing
 *   onPlaybackEnded {}                              — fired when timeline reaches end
 *   onError         { message: string }             — fired on fatal errors
 */
class MediaEnginePreviewView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

    // ── Events ────────────────────────────────────────────────────────────────
    val onLoad          by EventDispatcher()
    val onTimeUpdate    by EventDispatcher()
    val onPlaybackEnded by EventDispatcher()
    val onError         by EventDispatcher()

    // ── Internal state ────────────────────────────────────────────────────────
    private val textureView = TextureView(context)
    private var previewEngine: PreviewEngine? = null

    /** Config waiting to be applied once the TextureView surface is ready */
    private var pendingConfig: CompositeVideoComposer.CompositionConfig? = null
    private var surfaceReady = false
    private var isMuted      = false

    // ─────────────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────────────

    init {
        textureView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(textureView)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                pendingConfig?.let { startEngine(it) }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                // Nothing — RenderEngine viewport is config-driven, not view-size-driven
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                teardown()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Props called by the Expo Module View definition
    // ─────────────────────────────────────────────────────────────────────────

    fun updateConfig(configMap: Map<String, Any?>) {
        val parsed = parseConfig(configMap) ?: run {
            onError(mapOf("message" to "Failed to parse preview config"))
            return
        }
        if (surfaceReady) {
            val existing = previewEngine
            if (existing != null) {
                existing.updateConfig(parsed)
            } else {
                startEngine(parsed)
            }
        } else {
            pendingConfig = parsed
        }
    }

    fun setPlaying(playing: Boolean) {
        if (playing) previewEngine?.play() else previewEngine?.pause()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        // MediaPlayer volume control would be wired through PreviewEngine
    }

    /** Seek when NOT playing (controlled scrub from JS timeline). */
    fun setCurrentTime(seconds: Double) {
        previewEngine?.seekTo((seconds * 1000.0).toLong())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        teardown()
    }

    private fun startEngine(config: CompositeVideoComposer.CompositionConfig) {
        val st = textureView.surfaceTexture ?: return
        val surface = Surface(st)
        val engine = PreviewEngine(
            context       = context,
            config        = config,
            surface       = surface,
            onTimeUpdate  = { ms -> onTimeUpdate(mapOf("currentTime" to ms.toDouble() / 1_000.0)) },
            onPlaybackEnded = { onPlaybackEnded(mapOf()) },
            onReady       = {
                val dur = config.tracks.flatMap { it.clips }
                    .maxOfOrNull { it.startTime + it.duration } ?: 0.0
                onLoad(mapOf("duration" to dur))
            },
            onError       = { msg -> onError(mapOf("message" to msg)) }
        )
        previewEngine = engine
        engine.initialize()
    }

    private fun teardown() {
        previewEngine?.release()
        previewEngine = null
        surfaceReady  = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Config parsing (mirrors MediaEngineModule clip mapping)
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseConfig(map: Map<String, Any?>): CompositeVideoComposer.CompositionConfig? {
        return try {
            val width      = (map["width"]     as? Number)?.toInt() ?: 1280
            val height     = (map["height"]    as? Number)?.toInt() ?: 720
            val frameRate  = (map["frameRate"] as? Number)?.toInt() ?: 30
            val bitrate    = (map["bitrate"] as? Number)?.toInt()
                          ?: (map["videoBitrate"] as? Number)?.toInt()
                          ?: 4_000_000

            val tracksRaw = map["tracks"] as? List<*> ?: return null
            val tracks = tracksRaw.mapNotNull { raw ->
                val t = raw as? Map<*, *> ?: return@mapNotNull null
                val type = when (t["type"] as? String) {
                    "audio" -> CompositeVideoComposer.TrackType.AUDIO
                    "text"  -> CompositeVideoComposer.TrackType.TEXT
                    "image" -> CompositeVideoComposer.TrackType.IMAGE
                    else    -> CompositeVideoComposer.TrackType.VIDEO
                }
                val clips = (t["clips"] as? List<*> ?: emptyList<Any>()).mapNotNull { cr ->
                    val c = cr as? Map<*, *> ?: return@mapNotNull null
                    val uri = c["uri"] as? String ?: return@mapNotNull null
                    CompositeVideoComposer.Clip(
                        uri               = uri,
                        startTime         = (c["startTime"]      as? Number)?.toDouble() ?: 0.0,
                        duration          = (c["duration"]       as? Number)?.toDouble() ?: 0.0,
                        filter            = c["filter"]          as? String,
                        filterIntensity   = (c["filterIntensity"]as? Number)?.toFloat()  ?: 1.0f,
                        transition        = c["transition"]      as? String,
                        transitionDuration= (c["transitionDuration"] as? Number)?.toDouble() ?: 0.0,
                        resizeMode        = c["resizeMode"]      as? String ?: "cover",
                        x                 = (c["x"]             as? Number)?.toFloat()  ?: 0f,
                        y                 = (c["y"]             as? Number)?.toFloat()  ?: 0f,
                        scale             = (c["scale"]         as? Number)?.toFloat()  ?: 1f,
                        rotation          = (c["rotation"]      as? Number)?.toFloat()  ?: 0f,
                        opacity           = (c["opacity"]       as? Number)?.toFloat()  ?: 1.0f,
                        volume            = (c["volume"]        as? Number)?.toFloat()  ?: 1.0f,
                        speed             = (c["speed"]         as? Number)?.toDouble() ?: 1.0,
                        clipStart         = (c["clipStart"]     as? Number)?.toDouble() ?: 0.0,
                        clipEnd           = (c["clipEnd"]       as? Number)?.toDouble() ?: -1.0,
                        fadeInDuration    = (c["fadeInDuration"] as? Number)?.toDouble() ?: 0.0,
                        fadeOutDuration   = (c["fadeOutDuration"]as? Number)?.toDouble() ?: 0.0,
                        text              = c["text"]           as? String,
                        textColor         = c["textColor"] as? String ?: c["color"] as? String ?: "#FFFFFF",
                        textFontSize      = (c["textFontSize"] as? Number)?.toFloat()
                                           ?: (c["fontSize"]   as? Number)?.toFloat() ?: 64f,
                        textFontBold      = c["textFontBold"]  as? Boolean ?: false,
                        textBackgroundColor  = c["textBackgroundColor"]  as? String,
                        textBackgroundPadding= (c["textBackgroundPadding"] as? Number)?.toFloat() ?: 8f,
                        textShadowColor   = c["textShadowColor"]  as? String,
                        textShadowRadius  = (c["textShadowRadius"]  as? Number)?.toFloat() ?: 0f,
                        textShadowOffsetX = (c["textShadowOffsetX"] as? Number)?.toFloat() ?: 0f,
                        textShadowOffsetY = (c["textShadowOffsetY"] as? Number)?.toFloat() ?: 0f,
                        textStrokeColor   = c["textStrokeColor"]  as? String,
                        textStrokeWidth   = (c["textStrokeWidth"]  as? Number)?.toFloat() ?: 0f,
                        volumeKeyframes   = parseVolumeKeyframes(c)
                    )
                }
                CompositeVideoComposer.Track(type, clips)
            }

            CompositeVideoComposer.CompositionConfig(
                outputUri    = map["outputUri"] as? String ?: "",
                width        = width,
                height       = height,
                frameRate    = frameRate,
                bitrate      = bitrate,
                videoBitrate = bitrate,
                audioBitrate = (map["audioBitrate"] as? Number)?.toInt() ?: 128_000,
                tracks       = tracks
            )
        } catch (e: Exception) {
            android.util.Log.e("PreviewView", "Config parse error", e)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseVolumeKeyframes(clipMap: Map<*, *>): List<Pair<Double, Float>> {
        val envelope  = clipMap["volumeEnvelope"] as? Map<*, *> ?: return emptyList()
        val keyframes = envelope["keyframes"]     as? List<*>   ?: return emptyList()
        return keyframes.mapNotNull { kf ->
            val m   = kf as? Map<*, *> ?: return@mapNotNull null
            val t   = (m["time"]   as? Number)?.toDouble() ?: return@mapNotNull null
            val vol = (m["volume"] as? Number)?.toFloat()  ?: return@mapNotNull null
            Pair(t, vol)
        }
    }
}
