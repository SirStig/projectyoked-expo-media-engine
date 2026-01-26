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
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val EGL_RECORDABLE_ANDROID = 0x3142

/**
 * CompositeVideoComposer
 * 
 * Uses OpenGL ES 2.0 to compose multiple video clips with transitions and overlays.
 * 
 * Pipeline:
 * [MediaExtractor] -> [MediaCodec (Decoder)] -> [SurfaceTexture] -> [OES Texture]
 * -> [OpenGL Framebuffer (Composition/Effects)] -> [EGLSurface (WindowSurface)]
 * -> [MediaCodec (Encoder)] -> [MediaMuxer]
 */
class CompositeVideoComposer(
    private val context: Context,
    private val config: CompositionConfig
) {
    private val TAG = "CompositeVideoComposer"
    
    // Config Data Classes
    data class CompositionConfig(
        val outputUri: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val bitrate: Int,
        val tracks: List<Track>
    )

    data class Track(
        val type: TrackType,
        val clips: List<Clip>
    )
    
    enum class TrackType { VIDEO, AUDIO, TEXT }

    data class Clip(
        val uri: String,
        val startTime: Double,
        val duration: Double,
        val filter: String? = null,
        val transition: String? = null,
        // Transformations
        val resizeMode: String = "cover", // cover, contain, stretch
        val x: Float = 0f, // Normalized center -1..1
        val y: Float = 0f, // Normalized center -1..1
        val scale: Float = 1f,
        val rotation: Float = 0f // Degrees
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
        try {
            prepare()
            
            val renderEngine = RenderEngine(context, config)
            renderEngine.prepare()
            
            // Loop through timeline
            // We drive by output frames
            val durationSec = config.tracks.flatMap { it.clips }.maxOfOrNull { it.startTime + it.duration } ?: 0.0
            val durationUs = (durationSec * 1_000_000).toLong()
            val frameIntervalUs = (1_000_000 / config.frameRate).toLong()
            
            var currentTimeUs = 0L
            
            while (currentTimeUs <= durationUs) {
                // 1. Render To Texture/FBO
                // We need to render to the EGL Surface, but RenderEngine draws to Current Framebuffer.
                // Since we are ON the encoder thread (or main thread here), EGL surface is bound.
                // Wait, RenderEngine needs to draw to the ENCODER SURFACE's input.
                // EGLSurface attached to Encoder Surface is current.
                
                // Render content for this time
                renderEngine.render(currentTimeUs)
                
                // Set Presentation Time
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, currentTimeUs * 1000) // nanoseconds
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                
                // 2. Drain Encoder
                drainEncoder(false)
                
                currentTimeUs += frameIntervalUs
            }
            
            drainEncoder(true) // EOS
            renderEngine.release()
            
        } catch (e: Exception) {
            Log.e(TAG, "Composition Failed", e)
        } finally {
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
                audioExtractor!!.setDataSource(audioMixedFilePath!!)
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
        }
        
        // 2. Setup Video Encoder
       // ... (rest is same)
       val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height)
       format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
       Log.d(TAG, "Configuring Encoder: ${config.width}x${config.height} @ ${config.bitrate}bps")
       format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
       format.setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
       format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

       encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
       encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
                if (muxerStarted) throw RuntimeException("format changed twice")
                videoTrackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                
                // Add Audio Track if available
                if (audioExtractor != null && audioTrackSourceIndex != -1) {
                    val format = audioExtractor!!.getTrackFormat(audioTrackSourceIndex)
                    audioTrackIndex = muxer!!.addTrack(format)
                    Log.d(TAG, "Added Audio Track to Muxer: Index $audioTrackIndex")
                } else {
                    Log.w(TAG, "No Audio Track to add (Extractor=$audioExtractor, Index=$audioTrackSourceIndex)")
                }
                
                muxer!!.start()
                muxerStarted = true
            } else if (encoderStatus >= 0) {
                val encodedData = encoder!!.getOutputBuffer(encoderStatus)!!
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                
                if (bufferInfo.size != 0) {
                    if (!muxerStarted) throw RuntimeException("muxer hasn't started")
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    
                    // Write Audio Interleaved (Up to current video time)
                    writeAudioUpTo(bufferInfo.presentationTimeUs)
                    
                    muxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
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
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("unable to get EGL14 display")
        
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }
        
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, // Important for MediaCodec
            EGL14.EGL_NONE
        )
        
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0)
        
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    // Overlay generation moved to RenderEngine

    private fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
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

    // --- Inner Helper Classes ---
    // TextureRenderer moved to RenderEngine.kt
}
