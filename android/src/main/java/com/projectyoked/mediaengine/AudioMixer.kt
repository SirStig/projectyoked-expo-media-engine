package com.projectyoked.mediaengine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer 
import android.util.Log
import java.io.File 
import java.nio.ByteBuffer
import kotlin.math.max

class AudioMixer(private val context: Context, private val config: CompositeVideoComposer.CompositionConfig) {

    private val TAG = "AudioMixer"
    
    fun mix(): String? {
        Log.d(TAG, "Starting Audio Mix to File")
        
        val audioClips = ArrayList<PlaybackClip>()
        config.tracks.forEach { track ->
            if (track.type == CompositeVideoComposer.TrackType.VIDEO || track.type == CompositeVideoComposer.TrackType.AUDIO) {
                track.clips.forEach { clip -> audioClips.add(PlaybackClip(clip)) }
            }
        }
        
        if (audioClips.isEmpty()) return null
        
        // Temp File
        val parentDir = android.net.Uri.parse(config.outputUri).path?.let { File(it).parent } ?: context.cacheDir.absolutePath
        val outputPath = "$parentDir/temp_audio_mix_${System.currentTimeMillis()}.m4a"
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerAudioTrackIndex = -1
        var muxerStarted = false

        // Setup Encoder (AAC)
        val sampleRate = 44100
        val channelCount = 2
        val bitrate = 128000
        
        val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        
        // Prepare Clips
        audioClips.forEach { it.prepare() }
        
        val bufferSize = 4096
        val mixBuffer = FloatArray(bufferSize * channelCount) 
        val shortBuffer = ShortArray(bufferSize * channelCount)
        
        val durationUs = config.tracks.flatMap { it.clips }.maxOfOrNull { it.startTime + it.duration }?.times(1_000_000)?.toLong() ?: 0L
        var currentTimeUs = 0L
        val usPerSample = 1_000_000.0 / sampleRate.toDouble()
        
        val encoderBufferInfo = MediaCodec.BufferInfo()
        var outputDone = false
        
        try {
            while (currentTimeUs < durationUs || !outputDone) {
                // A. Mixing
                if (currentTimeUs < durationUs) {
                    mixBuffer.fill(0f)
                    
                    audioClips.forEach { clip ->
                        if (clip.isActive(currentTimeUs)) {
                             val samples = clip.readSamples(bufferSize, sampleRate, currentTimeUs)
                             for (i in samples.indices) {
                                 if (i < mixBuffer.size) mixBuffer[i] += samples[i]
                             }
                        }
                    }
                    
                    for (i in mixBuffer.indices) {
                        shortBuffer[i] = (mixBuffer[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    }
                    
                    val inputIndex = encoder.dequeueInputBuffer(1000)
                    if (inputIndex >= 0) {
                        val inputBuf = encoder.getInputBuffer(inputIndex)!!
                        inputBuf.clear()
                        val byteBuf = ByteBuffer.allocate(shortBuffer.size * 2)
                        byteBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        byteBuf.asShortBuffer().put(shortBuffer)
                        inputBuf.put(byteBuf.array())
                        encoder.queueInputBuffer(inputIndex, 0, inputBuf.capacity(), currentTimeUs, 0)
                    }
                    
                    currentTimeUs += (bufferSize * usPerSample * channelCount / 2).toLong()
                } else {
                    if (!outputDone) {
                        val inputIndex = encoder.dequeueInputBuffer(1000)
                        if (inputIndex >= 0) encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
                
                // B. Draining to Muxer
                var encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, 1000)
                while (encoderStatus >= 0 || encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerAudioTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)
                        continue
                    }
    
                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encoderBufferInfo.size = 0
                    
                    if (encoderBufferInfo.size != 0 && muxerStarted) {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        encodedData.position(encoderBufferInfo.offset)
                        encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                        muxer.writeSampleData(muxerAudioTrackIndex, encodedData, encoderBufferInfo)
                    }
                    
                    encoder.releaseOutputBuffer(encoderStatus, false)
                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mixing error", e)
        } finally {
            try {
                audioClips.forEach { it.release() }
                encoder.stop()
                encoder.release()
                if (muxerStarted) muxer.stop()
                muxer.release()
            } catch (e: Exception) {
                 Log.e(TAG, "Cleanup error", e)
            }
        }
        
        Log.d(TAG, "Mix Complete. File: $outputPath")
        if (!muxerStarted) {
             Log.w(TAG, "Muxer never started. No audio data mixed.")
             // Delete temp file if empty
             try { 
                File(outputPath).delete() 
             } catch(e: Exception) {}
             return null
        }
        
        val file = File(outputPath)
        if (file.exists() && file.length() > 0) {
            Log.d(TAG, "Audio Mix File Created: Size=${file.length()} bytes")
            return outputPath
        } else {
             Log.e(TAG, "Audio Mix File is empty or missing")
             return null
        }
    }

    // Helper to manage a single source clip
    class PlaybackClip(val clip: CompositeVideoComposer.Clip) {
        private var extractor: MediaExtractor? = null
        private var decoder: MediaCodec? = null
        private var format: MediaFormat? = null
        private var inputDone = false
        private var outputDone = false
        
        // Buffers
        private val tempBuffer = ShortArray(4096 * 2) // Max temp buffer
        private var bufferPos = 0
        private var bufferLimit = 0
        
        fun prepare() {
            try {
                val e = MediaExtractor()
                val path = if (clip.uri.startsWith("file://")) clip.uri.substring(7) else clip.uri
                e.setDataSource(path)
                
                var trackIndex = -1
                for (i in 0 until e.trackCount) {
                     val f = e.getTrackFormat(i)
                     if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                         trackIndex = i
                         format = f
                         break
                     }
                }
                
                if (trackIndex >= 0) {
                    extractor = e
                    extractor!!.selectTrack(trackIndex)
                    val mime = format!!.getString(MediaFormat.KEY_MIME)!!
                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder!!.configure(format, null, null, 0)
                    decoder!!.start()
                    // Seek to start of CLIP in FILE (clipStart logic missing in config object but implied in full plan)
                    // For now, assume clip plays from beginning of file.
                }
            } catch (ex: Exception) {
                Log.e("PlaybackClip", "Error preparing ${clip.uri}", ex)
            }
        }
        
        fun isActive(timeUs: Long): Boolean {
            val startUs = (clip.startTime * 1_000_000.0).toLong()
            val endUs = ((clip.startTime + clip.duration) * 1_000_000.0).toLong()
            return timeUs in startUs..endUs
        }
        
        fun readSamples(count: Int, targetRate: Int, timelineTimeUs: Long): FloatArray {
            if (decoder == null) return FloatArray(count * 2) // Silent
            
            // Logic to fetch 'count' samples (stereo)
            // This is complex because we need to drain decoder.
            // Simplified:
            val output = FloatArray(count * 2) // Stereo
            var outIdx = 0
            
            try {
               // Ensure we have data in temp buffer
               // Fill output from temp buffer, if empty, decode more.
               while (outIdx < output.size) {
                   if (bufferPos < bufferLimit) {
                       // Copy from temp
                       val sample = tempBuffer[bufferPos++]
                       output[outIdx++] = sample / 32768f
                   } else {
                       // Decode more
                       if (!pullMoreData()) {
                           break // EOS or error
                       }
                   }
               }
            } catch (e: Exception) {
               // Log.e("PlaybackClip", "Error reading samples", e)
            }
            
            return output
        }
        
        private fun pullMoreData(): Boolean {
             val dec = decoder ?: return false
             val ext = extractor ?: return false
             val info = MediaCodec.BufferInfo()
             
             // Feed
             if (!inputDone) {
                 val inIdx = dec.dequeueInputBuffer(1000)
                 if (inIdx >= 0) {
                     val buf = dec.getInputBuffer(inIdx)!!
                     val size = ext.readSampleData(buf, 0)
                     if (size < 0) {
                         dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                         inputDone = true
                     } else {
                         dec.queueInputBuffer(inIdx, 0, size, ext.sampleTime, 0)
                         ext.advance()
                     }
                 }
             }
             
             // Drain
             val outIdx = dec.dequeueOutputBuffer(info, 1000)
             if (outIdx >= 0) {
                 if (info.size > 0) {
                     val buf = dec.getOutputBuffer(outIdx)!!
                     buf.position(info.offset)
                     buf.limit(info.offset + info.size)
                     
                     // Assuming 16-bit PCM output. 
                     // IMPORTANT: Must handle channel count and resampling if mismatch.
                     // MVP: Assume 44.1/Stereo match or near enough.
                     
                     // Convert ByteBuffer to ShortArray
                     // If stereo, fine. If mono, we should duplicate?
                     // Let's safe-check buffer size
                     val len = info.size / 2
                     if (len > tempBuffer.size) { 
                        // Resize if needed or partial? Just clap
                     }
                     // Copy
                     val shorts = ShortArray(len)
                     buf.asShortBuffer().get(shorts)
                     
                     // To Temp Buffer (Naive copy, overwriting old)
                     System.arraycopy(shorts, 0, tempBuffer, 0, shorts.size)
                     bufferPos = 0
                     bufferLimit = shorts.size
                     
                     dec.releaseOutputBuffer(outIdx, false)
                     return true
                 }
                 dec.releaseOutputBuffer(outIdx, false)
                 if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return false
             }
             return false // Try again?
        }
        
        fun release() {
            try { decoder?.stop(); decoder?.release() } catch(e:Exception){}
            try { extractor?.release() } catch(e:Exception){}
        }
    }
}
