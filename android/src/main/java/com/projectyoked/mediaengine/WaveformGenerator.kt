package com.projectyoked.mediaengine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

object WaveformGenerator {
    private const val TAG = "WaveformGenerator"
    private const val TIMEOUT_US = 10000L

    fun generate(uriString: String, targetSamples: Int): List<Float> {
        val extractor = MediaExtractor()
        // Strip file:// prefix if present
        val path = if (uriString.startsWith("file://")) uriString.substring(7) else uriString
        
        try {
            extractor.setDataSource(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set data source: $uriString", e)
            extractor.release()
            return emptyList()
        }

        var audioTrackIndex = -1
        var durationUs = 0L
        var sampleRate = 44100
        var channelCount = 2
        var mimeType = ""

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                durationUs = format.getLong(MediaFormat.KEY_DURATION)
                sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
                channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                mimeType = mime
                break
            }
        }

        if (audioTrackIndex == -1) {
            extractor.release()
            return emptyList()
        }

        extractor.selectTrack(audioTrackIndex)
        
        val decoder = MediaCodec.createDecoderByType(mimeType)
        val format = extractor.getTrackFormat(audioTrackIndex)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val outputBufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        
        // We accumulate RMS values
        // Total expected decoded samples: (durationSec * sampleRate)
        // We want 'targetSamples' data points.
        // So we average every (Total / Target) samples.
        
        val totalExpectedSamples = (durationUs / 1_000_000.0 * sampleRate).toLong()
        val samplesPerPoint = (totalExpectedSamples / targetSamples).coerceAtLeast(1)
        
        val result = FloatArray(targetSamples)
        var currentPointIndex = 0
        var currentAccumulated = 0.0
        var currentSampleCount = 0
        
        // Safety limit to prevent infinite loops
        var loops = 0
        
        try {
            while (!outputDone && loops < 100000) {
                loops++
                
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(outputBufferInfo, TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (outputBufferInfo.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            // Process PCM data
                            // PCM is usually 16-bit signed short (Little Endian)
                            outputBuffer.position(outputBufferInfo.offset)
                            outputBuffer.limit(outputBufferInfo.offset + outputBufferInfo.size)
                            
                            while (outputBuffer.remaining() >= 2) { // 16-bit sample
                                val sample = outputBuffer.short.toFloat() / 32768f // Normalize -1.0 to 1.0
                                
                                // Simple RMS calculation
                                currentAccumulated += (sample * sample)
                                currentSampleCount++
                                
                                // If stereo, skip the next sample or average it?
                                // Let's just process all samples as mono stream for waveform
                                // (If stereo, we essentially average L and R over the window)
                                
                                if (currentSampleCount >= samplesPerPoint) {
                                    if (currentPointIndex < targetSamples) {
                                        val rms = sqrt(currentAccumulated / currentSampleCount).toFloat()
                                        // Amplify a bit for visual clarity, clamp to 1.0
                                        result[currentPointIndex] = (rms * 5.0f).coerceAtMost(1.0f)
                                        currentPointIndex++
                                    }
                                    currentAccumulated = 0.0
                                    currentSampleCount = 0
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    
                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // if input is done and we are timed out, maybe we are done?
                     if (inputDone) loops++ // speed up exit?
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating waveform", e)
        } finally {
            try { decoder.stop(); decoder.release() } catch (e: Exception) {}
            try { extractor.release() } catch (e: Exception) {}
        }
        
        // Fill remaining if any (due to estimation errors)
        return result.toList()
    }
}
