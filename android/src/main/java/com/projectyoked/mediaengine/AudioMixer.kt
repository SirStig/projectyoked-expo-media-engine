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
import java.util.ArrayList

class AudioMixer(
        private val context: Context,
        private val config: CompositeVideoComposer.CompositionConfig
) {

    private val TAG = "AudioMixer"

    fun mix(): String? {
        Log.d(TAG, "Starting Audio Mix to File")

        val audioClips = ArrayList<PlaybackClip>()
        config.tracks.forEach { track ->
            if (track.type == CompositeVideoComposer.TrackType.VIDEO ||
                            track.type == CompositeVideoComposer.TrackType.AUDIO
            ) {
                track.clips.forEach { clip -> audioClips.add(PlaybackClip(clip)) }
            }
        }

        if (audioClips.isEmpty()) return null

        // --- Optimization: Single Track Bypass ---
        if (audioClips.size == 1) {
            val clip = audioClips[0].clip
            if (clip.volume == 1.0f && clip.startTime == 0.0) {
                // Check if duration matches source?
                // If not trimming, we can just return source.
                // Even if trimming, CompositeVideoComposer handles start/duration of the track.
                // But AudioMixer returns a file that is expected to be STARTING at 0.
                // If the clip starts at 5.0s in the timeline, AudioMixer should produce silence for
                // 5s then audio?
                // Wait. `AudioMixer` produces a file that REPLACES the audio track.
                // `CompositeVideoComposer` then muxes it.
                // If the track starts at 5s, the muxer expects the audio stream to start at 0 but
                // be empty?
                // No, `writeAudioUpTo` reads from the audio file.
                // If I return the source file, it has audio starting at 0.
                // If the Composition says "Audio starts at 5s", and I return a file...
                // The `CompositeVideoComposer` logic (lines 194-211 of original, now modified in
                // Passthrough)
                // uses `audioMixerExtractor`.
                // In `drainEncoder` logic (transcoding), it writes audio samples.
                // Does it handle offset?
                // `CompositeVideoComposer` expects `AudioMixer` to output a file that matches the
                // TIMELINE.
                // So if clip starts at 5s, the mixed file must have 5s silence.
                // SO: I can ONLY bypass if `startTime == 0.0`.
                Log.d(TAG, "AudioMixer Bypass: Returning source directly")
                return clip.uri
            }
        }

        // Temp File
        val parentDir =
                android.net.Uri.parse(config.outputUri).path?.let { File(it).parent }
                        ?: context.cacheDir.absolutePath
        val outputPath = "$parentDir/temp_audio_mix_${System.currentTimeMillis()}.m4a"
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerAudioTrackIndex = -1
        var muxerStarted = false

        // Setup Encoder (AAC)
        val sampleRate = 44100
        val channelCount = 2
        val bitrate = 128000

        val outputFormat =
                MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC,
                        sampleRate,
                        channelCount
                )
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        outputFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        // Prepare Clips
        audioClips.forEach { it.prepare() }

        val bufferSize = 1024
        val mixBuffer = FloatArray(bufferSize * channelCount)
        val shortBuffer = ShortArray(bufferSize * channelCount)

        val durationUs =
                config.tracks
                        .flatMap { it.clips }
                        .maxOfOrNull { it.startTime + it.duration }
                        ?.times(1_000_000)
                        ?.toLong()
                        ?: 0L
        var currentTimeUs = 0L
        val usPerSample = 1_000_000.0 / sampleRate.toDouble()

        val encoderBufferInfo = MediaCodec.BufferInfo()
        var outputDone = false

        var eosQueued = false

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

                    var inputIndex = -1
                    var attempts = 0
                    while (inputIndex < 0 && attempts < 50) {
                        inputIndex = encoder.dequeueInputBuffer(10000) // Wait 10ms
                        if (inputIndex < 0) {
                            attempts++
                        }
                    }

                    if (inputIndex >= 0) {
                        val inputBuf = encoder.getInputBuffer(inputIndex)!!

                        val bytesNeeded = shortBuffer.size * 2
                        if (inputBuf.capacity() < bytesNeeded) {
                            Log.w(
                                    TAG,
                                    "Input buffer too small! Capacity: ${inputBuf.capacity()}, Needed: $bytesNeeded"
                            )
                        }

                        inputBuf.clear()
                        val byteBuf = ByteBuffer.allocate(shortBuffer.size * 2)
                        byteBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        byteBuf.asShortBuffer().put(shortBuffer)
                        val audioBytes = byteBuf.array()
                        val bytesToWrite = minOf(audioBytes.size, inputBuf.remaining())
                        inputBuf.put(audioBytes, 0, bytesToWrite)
                        encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                bytesToWrite,
                                currentTimeUs,
                                0
                        )
                    } else {
                        Log.e(
                                TAG,
                                "Audio Encoder Input full after retries, dropping chunk at $currentTimeUs"
                        )
                    }

                    currentTimeUs += (bufferSize * usPerSample * channelCount / 2).toLong()
                } else {
                    if (!eosQueued) {
                        val inputIndex = encoder.dequeueInputBuffer(1000)
                        if (inputIndex >= 0) {
                            Log.d(TAG, "Queueing Audio EOS")
                            encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosQueued = true
                        }
                    }
                }

                // B. Draining to Muxer
                var encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, 1000)
                while (encoderStatus >= 0 ||
                        encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxerAudioTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)
                        continue
                    }

                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0)
                            encoderBufferInfo.size = 0

                    if (encoderBufferInfo.size != 0 && muxerStarted) {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)!!
                        encodedData.position(encoderBufferInfo.offset)
                        encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                        muxer.writeSampleData(muxerAudioTrackIndex, encodedData, encoderBufferInfo)
                    }

                    encoder.releaseOutputBuffer(encoderStatus, false)
                    if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                            outputDone = true
                    encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, 0)
                }
            }
        } finally {
            try { audioClips.forEach { it.release() } } catch (e: Exception) { Log.w(TAG, "Error releasing audio clips", e) }
            try { encoder.stop(); encoder.release() } catch (e: Exception) { Log.w(TAG, "Error stopping encoder", e) }
            try { if (muxerStarted) muxer.stop(); muxer.release() } catch (e: Exception) { Log.w(TAG, "Error stopping muxer", e) }
        }

        Log.d(TAG, "Mix Complete. File: $outputPath")
        if (!muxerStarted) {
            Log.w(TAG, "Muxer never started. No audio data mixed.")
            // Delete temp file if empty
            try {
                File(outputPath).delete()
            } catch (e: Exception) { Log.w(TAG, "Failed to delete empty temp audio file", e) }
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
    inner class PlaybackClip(val clip: CompositeVideoComposer.Clip) {
        private var extractor: MediaExtractor? = null
        private var decoder: MediaCodec? = null
        private var format: MediaFormat? = null
        private var inputDone = false
        private var outputDone = false

        // Buffers
        // Increased buffer size to ~2 seconds of stereo audio (44.1k * 2ch * 2 bytes ~= 176k)
        private val tempBuffer = ShortArray(48000 * 4)
        private var bufferPos = 0
        private var bufferLimit = 0

        // Resampling State
        private var inputSampleRate = 44100
        private val TARGET_SAMPLE_RATE = 44100
        private var fractionalPos = 0.0

        fun prepare() {
            try {
                // Fix: Skip audio extraction for image files
                val lowerUri = clip.uri.lowercase()
                if (lowerUri.endsWith(".png") ||
                                lowerUri.endsWith(".jpg") ||
                                lowerUri.endsWith(".jpeg") ||
                                lowerUri.endsWith(".bmp") ||
                                lowerUri.endsWith(".webp") ||
                                lowerUri.endsWith(".gif")
                ) {
                    return
                }

                val e = MediaExtractor()
                e.setDataSource(context, android.net.Uri.parse(clip.uri), null)

                var trackIndex = -1
                for (i in 0 until e.trackCount) {
                    val f = e.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("audio/") == true) {
                        trackIndex = i
                        format = f
                        break
                    }
                }

                if (trackIndex >= 0) {
                    extractor = e
                    extractor!!.selectTrack(trackIndex)
                    val mime = format!!.getString(MediaFormat.KEY_MIME)!!
                    inputSampleRate = format!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)

                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder!!.configure(format, null, null, 0)
                    decoder!!.start()
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

        private fun computeVolume(timelineTimeUs: Long): Float {
            val pos = timelineTimeUs - (clip.startTime * 1_000_000).toLong()
            val endUs = (clip.duration * 1_000_000).toLong()
            val fadeInUs = (clip.fadeInDuration * 1_000_000).toLong()
            val fadeOutUs = (clip.fadeOutDuration * 1_000_000).toLong()
            val fadeIn = if (fadeInUs > 0) (pos.toFloat() / fadeInUs).coerceIn(0f, 1f) else 1f
            val fadeOut = if (fadeOutUs > 0) ((endUs - pos).toFloat() / fadeOutUs).coerceIn(0f, 1f) else 1f
            return clip.volume * fadeIn * fadeOut
        }

        fun readSamples(count: Int, targetRate: Int, timelineTimeUs: Long): FloatArray {
            if (decoder == null) return FloatArray(count * 2)

            val output = FloatArray(count * 2)
            var outIdx = 0
            val vol = computeVolume(timelineTimeUs)
            val step = inputSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()

            try {
                // Optimization: Fast Path for matching sample rates (No interpolation)
                if (inputSampleRate == TARGET_SAMPLE_RATE) {
                    while (outIdx < output.size) {
                        if (bufferPos + 2 >= bufferLimit) {
                            fillBuffer()
                            if (bufferPos + 2 >= bufferLimit) break
                        }

                        val currentL = tempBuffer[bufferPos++]
                        val currentR = tempBuffer[bufferPos++]

                        output[outIdx++] = (currentL / 32768f) * vol
                        output[outIdx++] = (currentR / 32768f) * vol
                    }
                } else {
                    // Linear Interpolation Path
                    while (outIdx < output.size) {
                        // Check if we have enough data (at least 2 stereo frames for interpolation)
                        if (bufferPos + 4 >= bufferLimit) {
                            fillBuffer()
                            // If still not enough data after fill, we are likely EOS or starved
                            if (bufferPos + 4 >= bufferLimit) break
                        }

                        // Linear Interpolation
                        val currentL = tempBuffer[bufferPos]
                        val currentR = tempBuffer[bufferPos + 1]
                        val nextL = tempBuffer[bufferPos + 2]
                        val nextR = tempBuffer[bufferPos + 3]

                        val frac = fractionalPos.toFloat()
                        val sampleL = currentL + frac * (nextL - currentL)
                        val sampleR = currentR + frac * (nextR - currentR)

                        output[outIdx++] = (sampleL / 32768f) * vol
                        output[outIdx++] = (sampleR / 32768f) * vol

                        fractionalPos += step
                        while (fractionalPos >= 1.0) {
                            fractionalPos -= 1.0
                            bufferPos += 2
                        }
                    }
                }
            } catch (e: Exception) {
                // Log.e("PlaybackClip", "Error reading samples", e)
            }
            return output
        }

        private fun fillBuffer() {
            // 1. Compact Buffer
            val remaining = bufferLimit - bufferPos
            if (remaining > 0 && bufferPos > 0) {
                System.arraycopy(tempBuffer, bufferPos, tempBuffer, 0, remaining)
            }
            bufferPos = 0
            bufferLimit = remaining

            if (outputDone) return

            // 2. Aggressive Fill Loop
            // Try to fill buffer until it's at least half full, or we hit EOS, or timeout
            var loops = 0
            // We give up after 50 loops (~50-100ms) to avoid blocking UI too long,
            // but enough to catch up on decoding
            val minFill = tempBuffer.size / 2

            while (bufferLimit < minFill && loops < 50 && !outputDone) {
                val didWork = doDecodeStep()
                if (!didWork) {
                    loops++
                    if (inputDone && !outputDone) {
                        // Waiting for last output...
                        continue
                    }
                } else {
                    loops = 0 // Reset timeout if we are making progress
                }
            }
        }

        private fun doDecodeStep(): Boolean {
            val dec = decoder ?: return false
            val ext = extractor ?: return false
            var didWork = false

            // Feed Input
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
                    didWork = true
                }
            }

            // Drain Output
            val info = MediaCodec.BufferInfo()
            val outIdx = dec.dequeueOutputBuffer(info, 1000)

            if (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = dec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)

                    // Check for format change/update sample rate if needed
                    // (optional enhancement, but safe to trust flow for now)

                    val samplesRead = info.size / 2
                    val decodedShorts = ShortArray(samplesRead)
                    buf.asShortBuffer().get(decodedShorts)

                    val inputChannels = format?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
                    val spaceRemaining = tempBuffer.size - bufferLimit

                    if (inputChannels == 1) {
                        // Mono -> Stereo
                        var w = bufferLimit
                        var r = 0
                        while (r < samplesRead && w < tempBuffer.size - 1) {
                            val s = decodedShorts[r++]
                            tempBuffer[w++] = s
                            tempBuffer[w++] = s
                        }
                        bufferLimit = w
                    } else {
                        // Stereo
                        val copyLen = kotlin.math.min(samplesRead, spaceRemaining)
                        System.arraycopy(decodedShorts, 0, tempBuffer, bufferLimit, copyLen)
                        bufferLimit += copyLen
                    }
                    didWork = true
                }
                dec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Update format
                val newFormat = dec.outputFormat
                if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    inputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                didWork = true
            }

            return didWork
        }

        fun release() {
            try {
                decoder?.stop()
                decoder?.release()
                extractor?.release()
            } catch (e: Exception) {}
        }
    }
}
