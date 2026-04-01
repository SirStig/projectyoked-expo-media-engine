package com.projectyoked.mediaengine

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

private const val EGL_RECORDABLE_ANDROID = 0x3142

/**
 * CompositeVideoComposer
 *
 * Uses OpenGL ES 2.0 to compose multiple video clips with transitions and overlays.
 *
 * Pipeline: [MediaExtractor] -> [MediaCodec (Decoder)] -> [SurfaceTexture] -> [OES Texture] ->
 * [OpenGL Framebuffer (Composition/Effects)] -> [EGLSurface (WindowSurface)] -> [MediaCodec
 * (Encoder)] -> [MediaMuxer]
 */
class CompositeVideoComposer(private val context: Context, private val config: CompositionConfig) {
    private val TAG = "CompositeVideoComposer"

    // Config Data Classes
    data class CompositionConfig(
            val outputUri: String,
            val width: Int,
            val height: Int,
            val frameRate: Int,
            val bitrate: Int,
            val videoBitrate: Int = bitrate,
            val audioBitrate: Int = 128000,
            val enablePassthrough: Boolean = true,
            val videoProfile: String = "baseline",
            val tracks: List<Track>
    )

    data class Track(val type: TrackType, val clips: List<Clip>)

    enum class TrackType {
        VIDEO,
        AUDIO,
        TEXT,
        IMAGE
    }

    data class Clip(
            val uri: String,
            val startTime: Double,
            val duration: Double,
            val filter: String? = null,
            val transition: String? = null,
            val transitionDuration: Double = 0.0,
            // Transformations
            val resizeMode: String = "cover", // cover, contain, stretch
            val x: Float = 0f, // Normalized center -1..1
            val y: Float = 0f, // Normalized center -1..1
            val scale: Float = 1f,
            val rotation: Float = 0f, // Degrees
            val volume: Float = 1.0f,
            // Playback speed (1.0 = normal, 2.0 = 2x fast, 0.5 = half-speed)
            val speed: Double = 1.0,
            // Source trimming
            val clipStart: Double = 0.0,
            val clipEnd: Double = -1.0,  // -1 = use full source
            // Visual
            val opacity: Float = 1.0f,
            val filterIntensity: Float = 1.0f,
            // Audio fade envelope
            val fadeInDuration: Double = 0.0,
            val fadeOutDuration: Double = 0.0,
            // Volume keyframes [{time, volume}] — processed by AudioMixer
            val volumeKeyframes: List<Pair<Double, Float>> = emptyList(),
            // Text/overlay content
            val text: String? = null,
            val textColor: String = "#FFFFFF",
            val textFontSize: Float = 64f,
            val textFontBold: Boolean = false,
            val textBackgroundColor: String? = null,
            val textBackgroundPadding: Float = 8f,
            val textShadowColor: String? = null,
            val textShadowRadius: Float = 0f,
            val textShadowOffsetX: Float = 0f,
            val textShadowOffsetY: Float = 0f,
            val textStrokeColor: String? = null,
            val textStrokeWidth: Float = 0f
    )

    // GL Components
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    // textureRenderer moved to RenderEngine

    // Media Components
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1

    // Audio State
    private var audioExtractor: MediaExtractor? = null
    private var audioMixedFilePath: String? = null
    private var audioTrackSourceIndex = -1

    // Threading removed

    // ...

    // Main Entry Point
    fun start() {
        // We let exceptions propagate to the Module for Promise rejection

        // --- Smart Passthrough Check ---
        if (config.enablePassthrough) {
            if (canSmartStitch()) {
                Log.d(TAG, "Smart Stitching Triggered: Delegating to VideoStitcher")
                // Sort clips by startTime so stitching order matches the intended timeline
                val orderedUris = config.tracks
                        .flatMap { it.clips }
                        .sortedBy { it.startTime }
                        .map { it.uri }
                VideoStitcher.stitch(orderedUris, config.outputUri)
                return
            }
            if (canPassthrough()) {
                Log.d(TAG, "Smart Passthrough Triggered: Skipping transcoding")
                runPassthrough()
                return
            }
        }

        prepare()

        val renderEngine = RenderEngine(context, config)
        renderEngine.prepare()

        try {
            // Loop through timeline
            // We drive by output frames
            val durationSec =
                    config.tracks.flatMap { it.clips }.maxOfOrNull { it.startTime + it.duration }
                            ?: 0.0
            val durationUs = (durationSec * 1_000_000).toLong()
            val frameIntervalUs = (1_000_000 / config.frameRate).toLong()

            var currentTimeUs = 0L

            while (currentTimeUs <= durationUs) {
                // 1. Render To Texture/FBO

                // Render content for this time
                renderEngine.render(currentTimeUs)

                // Set Presentation Time
                EGLExt.eglPresentationTimeANDROID(
                        eglDisplay,
                        eglSurface,
                        currentTimeUs * 1000
                ) // nanoseconds
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)

                // 2. Drain Encoder
                drainEncoder(false)

                currentTimeUs += frameIntervalUs
            }

            drainEncoder(true) // EOS
        } finally {
            renderEngine.release()
            release()
        }
    }

    private fun prepare() {
        // 1. Setup MediaMuxer
        val outputPath = Uri.parse(config.outputUri).path!!
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 1.5 Prepare Audio (Pre-mix)
        Log.d(TAG, "Preparing Audio...")
        try {
            val mixer = AudioMixer(context, config)
            audioMixedFilePath = mixer.mix()

            if (audioMixedFilePath != null) {
                audioExtractor = MediaExtractor()
                if (audioMixedFilePath!!.startsWith("file://") ||
                                audioMixedFilePath!!.startsWith("content://")
                ) {
                    audioExtractor!!.setDataSource(context, Uri.parse(audioMixedFilePath!!), null)
                } else {
                    audioExtractor!!.setDataSource(audioMixedFilePath!!)
                }
                for (i in 0 until audioExtractor!!.trackCount) {
                    val format = audioExtractor!!.getTrackFormat(i)
                    Log.d(TAG, "Mixed Audio Track $i: $format")
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioTrackSourceIndex = i
                        audioExtractor!!.selectTrack(i)
                        Log.d(TAG, "Selected Audio Track Index: $i")
                        break
                    }
                }
            } else {
                Log.e(TAG, "AudioMixer returned NULL")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio mix failed", e)
            throw e // Rethrow audio mixing errors too!
        }

        // 2. Setup Video Encoder
        // ... (rest is same)
        val format =
                MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        config.width,
                        config.height
                )
        format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        Log.d(TAG, "Configuring Encoder: ${config.width}x${config.height} @ ${config.bitrate}bps")
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        // Robust Profile Support
        if (config.videoProfile != "baseline") {
            // Map string to constant
            val profile =
                    when (config.videoProfile) {
                        "high" -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                        "main" -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                        else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    }
            // For now, we trust the device or fallback if configure fails (handled below)
            // Note: Setting Profile often requires setting Level too, which is complex to guess.
            // A safer approach is to only set it if explicitly needed, or handle exception.
            if (profile != MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                format.setInteger(MediaFormat.KEY_PROFILE, profile)
                // Level 4.1 covers 1080p@30fps for both Main and High profiles and is broadly
                // supported. Some devices reject KEY_PROFILE without a paired KEY_LEVEL.
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(
                    TAG,
                    "Encoder configuration failed with profile ${config.videoProfile}, retrying with Baseline",
                    e
            )
            format.setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            )
            // Remove level if it was set
            // Retry
            try {
                encoder?.reset()
                encoder?.release()
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e2: Exception) {
                Log.e(TAG, "Encoder fallback failed", e2)
                throw e2
            }
        }

        encoderSurface = encoder!!.createInputSurface()
        encoder!!.start()
        setupEGL()
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            encoder?.signalEndOfInputStream()
        }

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val encoderStatus = encoder!!.dequeueOutputBuffer(bufferInfo, 0) // No wait
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    // Robustness: Don't crash, just log and ignore if possible, though this is rare
                    // spec violation
                    Log.w(TAG, "Format changed twice! Ignoring...")
                } else {
                    videoTrackIndex = muxer!!.addTrack(encoder!!.outputFormat)

                    // Add Audio Track if available
                    if (audioExtractor != null && audioTrackSourceIndex != -1) {
                        val format = audioExtractor!!.getTrackFormat(audioTrackSourceIndex)
                        audioTrackIndex = muxer!!.addTrack(format)
                        Log.d(TAG, "Added Audio Track to Muxer: Index $audioTrackIndex")
                    } else {
                        Log.w(
                                TAG,
                                "No Audio Track to add (Extractor=$audioExtractor, Index=$audioTrackSourceIndex)"
                        )
                    }

                    muxer!!.start()
                    muxerStarted = true
                }
            } else if (encoderStatus >= 0) {
                val encodedData = encoder!!.getOutputBuffer(encoderStatus)!!
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        // Wait for format change... potentially loop/wait here?
                        Log.e(TAG, "Muxer not started but data received. Dropping frame.")
                    } else {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)

                        // Write Audio Interleaved (Up to current video time)
                        writeAudioUpTo(bufferInfo.presentationTimeUs)

                        muxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                }

                encoder!!.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }

        if (endOfStream && muxerStarted) {
            // Write remaining audio
            writeAudioUpTo(Long.MAX_VALUE)
        }
    }

    private fun writeAudioUpTo(timeUs: Long) {
        if (audioTrackIndex == -1 || audioExtractor == null) return

        val buffer = ByteBuffer.allocate(64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (true) {
                val sampleTime = audioExtractor!!.sampleTime

                // Check for EOS or Future
                if (sampleTime == -1L) {
                    // Log.d(TAG, "Audio EOS")
                    break
                }
                if (sampleTime > timeUs) {
                    break
                }

                val size = audioExtractor!!.readSampleData(buffer, 0)
                if (size >= 0) {
                    bufferInfo.set(0, size, sampleTime, audioExtractor!!.sampleFlags)
                    muxer!!.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                    if (!audioExtractor!!.advance()) {
                        // End of stream reached during advance
                        break
                    }
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio samples", e)
        }
    }

    private fun setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY)
                throw RuntimeException("unable to get EGL14 display")

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        val attribList =
                intArrayOf(
                        EGL14.EGL_RED_SIZE,
                        8,
                        EGL14.EGL_GREEN_SIZE,
                        8,
                        EGL14.EGL_BLUE_SIZE,
                        8,
                        EGL14.EGL_ALPHA_SIZE,
                        8,
                        EGL14.EGL_RENDERABLE_TYPE,
                        EGL14.EGL_OPENGL_ES2_BIT,
                        EGL_RECORDABLE_ANDROID,
                        1, // Important for MediaCodec
                        EGL14.EGL_NONE
                )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)

        eglContext =
                EGL14.eglCreateContext(
                        eglDisplay,
                        configs[0],
                        EGL14.EGL_NO_CONTEXT,
                        contextAttribs,
                        0
                )

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface =
                EGL14.eglCreateWindowSurface(
                        eglDisplay,
                        configs[0],
                        encoderSurface,
                        surfaceAttribs,
                        0
                )

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    // Overlay generation moved to RenderEngine

    private fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        encoder?.stop()
        encoder?.release()
        if (muxerStarted) muxer?.stop()
        muxer?.release()
        encoderSurface?.release()

        // Cleanup Audio
        if (audioExtractor != null) {
            audioExtractor!!.release()
            audioExtractor = null
        }

        if (audioMixedFilePath != null) {
            try {
                val file = File(audioMixedFilePath!!)
                if (file.exists()) {
                    val deleted = file.delete()
                    Log.d(TAG, "Temp audio returned: $deleted (${file.absolutePath})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp audio file", e)
            }
        }
    }

    // --- Passthrough Logic ---

    private fun canSmartStitch(): Boolean {
        // 1. Must have only video tracks
        if (config.tracks.any { it.type != TrackType.VIDEO }) return false

        val clips = config.tracks.flatMap { it.clips }
        if (clips.size < 2) return false // 1 clip is handled by standard Passthrough

        // 2. Check for effects or source trimming
        if (clips.any {
                    it.filter != null ||
                            it.resizeMode != "cover" ||
                            it.scale != 1f ||
                            it.rotation != 0f ||
                            it.x != 0f ||
                            it.y != 0f ||
                            it.clipStart > 0.0 ||
                            it.clipEnd > 0.0
                }
        )
                return false

        // 3. Simple sequential check (ensure no complex overlaps/mixing)
        // For now, assume if track structure is simple, it's sequential.
        // We really just want to stitch them.

        return true
    }

    private fun canPassthrough(): Boolean {
        // 1. Check if we have exactly one video track with one clip
        val videoTracks = config.tracks.filter { it.type == TrackType.VIDEO }
        if (videoTracks.size != 1) return false

        val videoTrack = videoTracks[0]
        if (videoTrack.clips.size != 1) return false

        val clip = videoTrack.clips[0]

        // 2. Check for overlays or effects
        if (config.tracks.any { it.type == TrackType.TEXT }) return false // Has text/emojis

        // 3. Check for modifiers on the clip
        if (clip.filter != null) return false
        if (clip.rotation != 0f) return false
        if (clip.scale != 1f) return false
        if (clip.x != 0f || clip.y != 0f) return false
        if (clip.opacity < 1.0f) return false
        if (clip.speed != 1.0) return false
        if (clip.resizeMode != "cover") return false
        // Source-level trimming: clipStart/clipEnd requires re-encoding
        if (clip.clipStart > 0.0 || clip.clipEnd > 0.0) return false

        // 4. Trimming Check
        // If the user requests a start time mismatch or a specific short duration, we must NOT
        // passthrough
        // (unless we implement GOP-aware splitting, which is complex).
        // Safest: Disable passthrough if startTime > 0.
        if (clip.startTime > 0.1) return false // Allow small epsilon?

        // Check Source Duration vs Clip Duration
        val sourceDurationUs = getSourceDurationUs(clip.uri)
        if (sourceDurationUs > 0) {
            val clipDurationUs = (clip.duration * 1_000_000).toLong()
            // If clip duration is significantly shorter than source (e.g. > 0.5s difference), we
            // are trimming.
            // Transcoding is required to trim properly.
            if (sourceDurationUs - clipDurationUs > 500_000) {
                Log.d(
                        TAG,
                        "Passthrough disabled: Clip is trimmed (Source: $sourceDurationUs, Clip: $clipDurationUs)"
                )
                return false
            }
        }

        // 4. Strict Mode: Check if we are adding audio (music) that wasn't there?
        // If we simply want to MUX new audio into existing video without re-encoding video, we CAN
        // do that.
        // But for MVP, let's say "Passthrough" means "Copy Video Stream" + "Copy/Mix Audio".
        // If we are mixing audio, we can still copy the VIDEO stream.

        // Let's verify codecs match?
        // For now, let's assume if it's a single video clip with no effects, we can try to copy the
        // video stream.

        return true
    }

    private fun getSourceDurationUs(uriString: String): Long {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, Uri.parse(uriString), null)

            var duration = -1L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = format.getLong(MediaFormat.KEY_DURATION)
                    }
                    break
                }
            }
            extractor.release()
            duration
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine source duration for passthrough check", e)
            -1L
        }
    }

    private fun runPassthrough() {
        // Single-Pass IO Optimization
        val outputPath = Uri.parse(config.outputUri).path!!
        val passthroughMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val videoClip = config.tracks.first { it.type == TrackType.VIDEO }.clips.first()
        val videoUri = videoClip.uri

        // Handle Audio Mixing if needed
        val hasExtraAudio = config.tracks.any { it.type == TrackType.AUDIO }
        var audioMixerExtractor: MediaExtractor? = null
        var mixedAudioPath: String? = null

        // If we have extra audio, we MUST mix it (or just use it).
        // If we need to mix, we generate a temp file and read from THAT for audio.
        // If not, we read from the ORIGINAL video file (same extractor).

        if (hasExtraAudio) {
            val mixer = AudioMixer(context, config)
            mixedAudioPath = mixer.mix()
            if (mixedAudioPath != null) {
                audioMixerExtractor = MediaExtractor()
                // AudioMixer output is a raw file path usually, so let's stick to
                // setDataSource(path) for mixed audio
                // IF it's a file path. If it's a URI, handle appropriately.
                // mixer.mix() returns a String path.
                if (mixedAudioPath!!.startsWith("file://") ||
                                mixedAudioPath!!.startsWith("content://")
                ) {
                    audioMixerExtractor!!.setDataSource(context, Uri.parse(mixedAudioPath!!), null)
                } else {
                    audioMixerExtractor!!.setDataSource(mixedAudioPath!!)
                }
            }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(videoUri), null)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerVideoIndex = -1
            var muxerAudioIndex = -1

            // 1. Select Video Track
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoTrackIndex = i
                    extractor.selectTrack(i)
                    muxerVideoIndex = passthroughMuxer.addTrack(f)
                    break
                }
            }

            // 2. Select Audio Track (From Mixer OR Source)
            if (audioMixerExtractor != null) {
                // Using Mixed Audio
                for (i in 0 until audioMixerExtractor!!.trackCount) {
                    val f = audioMixerExtractor!!.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioMixerExtractor!!.selectTrack(i)
                        muxerAudioIndex = passthroughMuxer.addTrack(f)
                        break
                    }
                }
            } else {
                // Using Source Audio
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        audioTrackIndex = i
                        extractor.selectTrack(i)
                        muxerAudioIndex = passthroughMuxer.addTrack(f)
                        break
                    }
                }
            }

            passthroughMuxer.start()

            // 3. Single Pass Loop (for Source)
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1MB
            val bufferInfo = MediaCodec.BufferInfo()

            // If we are using source audio, we read everything from `extractor`.
            // If we are using mixed audio, we read video from `extractor` and audio from
            // `audioMixerExtractor`.

            if (audioMixerExtractor == null) {
                // Scenario A: Single Source (Fastest)
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime == -1L) break

                    val trackIndex = extractor.sampleTrackIndex
                    val muxerIndex =
                            if (trackIndex == videoTrackIndex) muxerVideoIndex
                            else if (trackIndex == audioTrackIndex) muxerAudioIndex else -1

                    if (muxerIndex >= 0) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size >= 0) {
                            bufferInfo.set(0, size, sampleTime, extractor.sampleFlags)
                            passthroughMuxer.writeSampleData(muxerIndex, buffer, bufferInfo)
                        }
                    }
                    if (!extractor.advance()) break
                }
            } else {
                // Scenario B: Video from Source, Audio from Mixer (Parallel)
                // We loop until both are done.
                var videoDone = false
                var audioDone = false

                while (!videoDone || !audioDone) {
                    // Read Video
                    if (!videoDone) {
                        val sampleTime = extractor.sampleTime
                        if (sampleTime == -1L) {
                            videoDone = true
                        } else {
                            if (extractor.sampleTrackIndex == videoTrackIndex) {
                                val size = extractor.readSampleData(buffer, 0)
                                if (size >= 0) {
                                    bufferInfo.set(0, size, sampleTime, extractor.sampleFlags)
                                    passthroughMuxer.writeSampleData(
                                            muxerVideoIndex,
                                            buffer,
                                            bufferInfo
                                    )
                                }
                            }
                            if (!extractor.advance()) videoDone = true
                        }
                    }

                    // Read Audio
                    if (!audioDone) {
                        val sampleTime = audioMixerExtractor!!.sampleTime
                        if (sampleTime == -1L) {
                            audioDone = true
                        } else {
                            // Assuming mixer has only 1 track selected
                            val size = audioMixerExtractor!!.readSampleData(buffer, 0)
                            if (size >= 0) {
                                bufferInfo.set(
                                        0,
                                        size,
                                        sampleTime,
                                        audioMixerExtractor!!.sampleFlags
                                )
                                passthroughMuxer.writeSampleData(
                                        muxerAudioIndex,
                                        buffer,
                                        bufferInfo
                                )
                            }
                            if (!audioMixerExtractor!!.advance()) audioDone = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Passthrough failed", e)
            throw e
        } finally {
            try {
                extractor.release()
                audioMixerExtractor?.release()
                passthroughMuxer.stop()
                passthroughMuxer.release()

                if (mixedAudioPath != null) {
                    File(mixedAudioPath).delete()
                }
            } catch (e: Exception) {}
        }
    }

    // --- Inner Helper Classes ---
    // TextureRenderer moved to RenderEngine.kt
}
