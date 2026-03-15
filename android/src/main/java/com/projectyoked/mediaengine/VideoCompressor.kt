package com.projectyoked.mediaengine

import android.content.Context
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
import android.opengl.GLES20
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * VideoCompressor
 *
 * Standalone video compression utility. Decodes video frames, renders through OpenGL
 * (for resolution scaling), re-encodes at the target bitrate, and copies the audio
 * track directly without re-encoding to preserve quality.
 *
 * Usage: VideoCompressor(context, config).compress()
 */
class VideoCompressor(private val context: Context, private val config: CompressConfig) {

    private val TAG = "VideoCompressor"

    data class CompressConfig(
        val inputUri: String,
        val outputUri: String,
        val width: Int? = null,          // null = source width
        val height: Int? = null,         // null = source height
        val bitrate: Int = 4_000_000,    // video bitrate in bps
        val frameRate: Int? = null,      // null = source frame rate
        val audioBitrate: Int = 128_000,
        val codec: String = "h264",      // "h264" or "h265"
        val copyAudio: Boolean = true    // copy audio without re-encoding (preserves quality)
    )

    private data class VideoMeta(
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val rotation: Int,
        val durationUs: Long,
        val trackIndex: Int,
        val mime: String
    )

    fun compress() {
        val outputPath = Uri.parse(config.outputUri).path!!
        File(outputPath).parentFile?.mkdirs()

        // 1. Probe source
        val extractor = MediaExtractor()
        extractor.setDataSource(context, Uri.parse(config.inputUri), null)
        val meta = probeVideo(extractor) ?: throw RuntimeException("No video track in ${config.inputUri}")

        // Resolve output dimensions (must be even for H.264)
        val outW = roundEven(config.width ?: meta.width)
        val outH = roundEven(config.height ?: meta.height)
        val outFps = config.frameRate ?: meta.frameRate
        val frameIntervalUs = 1_000_000L / outFps

        Log.d(TAG, "Compressing ${meta.width}x${meta.height} → ${outW}x${outH} @ ${config.bitrate}bps codec=${config.codec}")

        // 2. Set up encoder
        val encoderMime = if (config.codec == "h265" || config.codec == "hevc")
            MediaFormat.MIMETYPE_VIDEO_HEVC
        else
            MediaFormat.MIMETYPE_VIDEO_AVC

        val encoderFormat = MediaFormat.createVideoFormat(encoderMime, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, outFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = MediaCodec.createEncoderByType(encoderMime)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        // 3. Set up EGL
        val egl = setupEGL(encoderSurface)
        GLES20.glViewport(0, 0, outW, outH)

        // 4. Set up decoder → SurfaceTexture → TextureRenderer → encoder surface
        val renderer = TextureRenderer()
        renderer.surfaceCreated()

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]
        android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        android.opengl.GLES20.glTexParameterf(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        android.opengl.GLES20.glTexParameterf(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val surfaceTexture = SurfaceTexture(texId)
        val decoderSurface = Surface(surfaceTexture)

        val decoderFormat = extractor.getTrackFormat(meta.trackIndex)
        val decoder = MediaCodec.createDecoderByType(meta.mime)
        decoder.configure(decoderFormat, decoderSurface, null, 0)
        decoder.start()
        extractor.selectTrack(meta.trackIndex)

        // 5. Set up muxer
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1

        // 6. Probe and add audio track (copy without decode)
        var audioExtractor: MediaExtractor? = null
        var audioSourceTrackIndex = -1
        if (config.copyAudio) {
            val ae = MediaExtractor()
            ae.setDataSource(context, Uri.parse(config.inputUri), null)
            for (i in 0 until ae.trackCount) {
                val f = ae.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioSourceTrackIndex = i
                    ae.selectTrack(i)
                    audioExtractor = ae
                    break
                }
            }
            if (audioExtractor == null) ae.release()
        }

        // 7. Feed/drain loop
        val bufInfo = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        val stMatrix = FloatArray(16)

        // Identity MVP for full-screen render
        val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

        try {
            while (!outputEos) {
                // Feed decoder
                if (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain decoder → render → feed encoder
                val outIdx = decoder.dequeueOutputBuffer(bufInfo, 10_000L)
                if (outIdx >= 0) {
                    val render = bufInfo.size > 0
                    decoder.releaseOutputBuffer(outIdx, render)

                    if (render) {
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(stMatrix)

                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        renderer.draw(texId, stMatrix, identity, 0, 1.0f)

                        EGLExt.eglPresentationTimeANDROID(egl.display, egl.surface, bufInfo.presentationTimeUs * 1000L)
                        EGL14.eglSwapBuffers(egl.display, egl.surface)
                    }

                    if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoder.signalEndOfInputStream()
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Decoder format changed: ${decoder.outputFormat}")
                }

                // Drain encoder → muxer
                var encoderStatus = encoder.dequeueOutputBuffer(bufInfo, 0)
                while (encoderStatus >= 0 || encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        if (audioExtractor != null && audioSourceTrackIndex >= 0) {
                            audioTrackIndex = muxer.addTrack(audioExtractor!!.getTrackFormat(audioSourceTrackIndex))
                        }
                        muxer.start()
                        muxerStarted = true
                    } else if (encoderStatus >= 0) {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufInfo.offset)
                            encodedData.limit(bufInfo.offset + bufInfo.size)
                            // Interleave audio
                            if (audioExtractor != null && audioTrackIndex >= 0) {
                                writeAudioUpTo(audioExtractor!!, audioTrackIndex, muxer, bufInfo.presentationTimeUs)
                            }
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufInfo)
                        }
                        encoder.releaseOutputBuffer(encoderStatus, false)
                        if ((bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                            break
                        }
                    }
                    encoderStatus = encoder.dequeueOutputBuffer(bufInfo, 0)
                }
            }

            // Flush remaining audio
            if (audioExtractor != null && audioTrackIndex >= 0 && muxerStarted) {
                writeAudioUpTo(audioExtractor!!, audioTrackIndex, muxer, Long.MAX_VALUE)
            }

        } finally {
            try { decoder.stop(); decoder.release() } catch (_: Exception) {}
            try { encoder.stop(); encoder.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
            try { decoderSurface.release() } catch (_: Exception) {}
            try { surfaceTexture.release() } catch (_: Exception) {}
            try { encoderSurface.release() } catch (_: Exception) {}
            teardownEGL(egl)
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}
        }

        Log.d(TAG, "Compression complete: $outputPath")
    }

    private fun writeAudioUpTo(extractor: MediaExtractor, trackIndex: Int, muxer: MediaMuxer, upToUs: Long) {
        val buf = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val ts = extractor.sampleTime
            if (ts < 0 || ts > upToUs) break
            val size = extractor.readSampleData(buf, 0)
            if (size < 0) break
            info.set(0, size, ts, extractor.sampleFlags)
            muxer.writeSampleData(trackIndex, buf, info)
            if (!extractor.advance()) break
        }
    }

    private fun probeVideo(extractor: MediaExtractor): VideoMeta? {
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue

            val w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 1280
            val h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 720
            val fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) f.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            val dur = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            return VideoMeta(w, h, fps, rot, dur, i, mime)
        }
        return null
    }

    private fun roundEven(v: Int) = if (v % 2 == 0) v else v + 1

    // ── EGL helpers ──────────────────────────────────────────────────────────

    private data class EGLState(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface
    )

    private fun setupEGL(outputSurface: Surface): EGLState {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(display, attribList, 0, configs, 0, 1, IntArray(1), 0)

        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)

        val surface = EGL14.eglCreateWindowSurface(display, configs[0], outputSurface,
            intArrayOf(EGL14.EGL_NONE), 0)

        EGL14.eglMakeCurrent(display, surface, surface, context)
        return EGLState(display, context, surface)
    }

    private fun teardownEGL(egl: EGLState) {
        try {
            EGL14.eglMakeCurrent(egl.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(egl.display, egl.surface)
            EGL14.eglDestroyContext(egl.display, egl.context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(egl.display)
        } catch (_: Exception) {}
    }
}
