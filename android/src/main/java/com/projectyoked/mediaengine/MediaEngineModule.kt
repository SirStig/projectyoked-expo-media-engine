package com.projectyoked.mediaengine

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class VolumeKeyframeRecord : Record {
        @Field val time: Double = 0.0
        @Field val volume: Double = 1.0
}

class VolumeEnvelopeRecord : Record {
        @Field val keyframes: List<VolumeKeyframeRecord> = emptyList()
}

class CompositionClipRecord : Record {
        @Field val uri: String = ""
        @Field val startTime: Double = 0.0
        @Field val duration: Double = 0.0
        @Field val filter: String? = null
        @Field val transition: String? = null
        @Field val transitionDuration: Double = 0.0
        @Field val resizeMode: String = "cover"
        @Field val x: Double = 0.0
        @Field val y: Double = 0.0
        @Field val scale: Double = 1.0
        @Field val rotation: Double = 0.0
        @Field val volume: Double = 1.0
        @Field val speed: Double = 1.0
        // Source trimming
        @Field val clipStart: Double = 0.0
        @Field val clipEnd: Double = -1.0
        // Visual
        @Field val opacity: Double = 1.0
        @Field val filterIntensity: Double = 1.0
        // Audio fade
        @Field val fadeInDuration: Double = 0.0
        @Field val fadeOutDuration: Double = 0.0
        // Text/overlay styling
        @Field val text: String? = null
        @Field val color: String = "#FFFFFF"   // flat alias (legacy)
        @Field val textColor: String = "#FFFFFF"
        @Field val fontSize: Double = 64.0     // flat alias (legacy)
        @Field val textFontSize: Double = 64.0
        @Field val textFontBold: Boolean = false
        @Field val textBackgroundColor: String? = null
        @Field val textBackgroundPadding: Double = 8.0
        @Field val textShadowColor: String? = null
        @Field val textShadowRadius: Double = 0.0
        @Field val textShadowOffsetX: Double = 0.0
        @Field val textShadowOffsetY: Double = 0.0
        @Field val textStrokeColor: String? = null
        @Field val textStrokeWidth: Double = 0.0
        @Field val volumeEnvelope: VolumeEnvelopeRecord? = null
}

class CompositionTrackRecord : Record {
        @Field val type: String = "video"
        @Field val clips: List<CompositionClipRecord> = emptyList()
}

class CompositionConfigRecord : Record {
        @Field val outputUri: String = ""
        @Field val width: Int? = null
        @Field val height: Int? = null
        @Field val frameRate: Int? = null
        @Field val bitrate: Int? = null // Legacy support
        @Field val videoBitrate: Int? = null
        @Field val audioBitrate: Int? = null
        @Field val enablePassthrough: Boolean = true
        @Field val videoProfile: String = "baseline" // baseline, main, high
        @Field val tracks: List<CompositionTrackRecord> = emptyList()
}

class CompressVideoRecord : Record {
    @Field val inputUri: String = ""
    @Field val outputUri: String = ""
    @Field val width: Int? = null
    @Field val height: Int? = null
    @Field val maxWidth: Int? = null
    @Field val maxHeight: Int? = null
    @Field val bitrate: Int? = null
    @Field val frameRate: Int? = null
    @Field val audioBitrate: Int? = null
    @Field val codec: String = "h264"
    @Field val quality: String? = null  // "low" | "medium" | "high" — resolved in JS
}

class MediaEngineModule : Module() {
        override fun definition() = ModuleDefinition {
                Name("MediaEngine")

                // MARK: - Preview View
                View(MediaEnginePreviewView::class) {
                        Events("onLoad", "onTimeUpdate", "onPlaybackEnded", "onError")

                        Prop("config") { view: MediaEnginePreviewView, config: Map<String, Any?> ->
                                view.updateConfig(config)
                        }
                        Prop("isPlaying") { view: MediaEnginePreviewView, playing: Boolean ->
                                view.setPlaying(playing)
                        }
                        Prop("muted") { view: MediaEnginePreviewView, muted: Boolean ->
                                view.setMuted(muted)
                        }
                        // Controlled seek — JS sets this to scrub the timeline when paused
                        Prop("currentTime") { view: MediaEnginePreviewView, seconds: Double ->
                                view.setCurrentTime(seconds)
                        }
                }

                // MARK: - Audio Extraction
                AsyncFunction("extractAudio") { videoUri: String, outputUri: String ->
                        val inputPath = if (videoUri.startsWith("file://")) videoUri.substring(7) else videoUri
                        val outputPath = if (outputUri.startsWith("file://")) outputUri.substring(7) else outputUri

                        // Remove stale output file
                        val outFile = java.io.File(outputPath)
                        if (outFile.exists()) outFile.delete()

                        val extractor = android.media.MediaExtractor()
                        try {
                                extractor.setDataSource(inputPath)

                                // Find first audio track
                                var audioTrackIndex = -1
                                for (i in 0 until extractor.trackCount) {
                                        val mime = extractor.getTrackFormat(i)
                                                .getString(android.media.MediaFormat.KEY_MIME) ?: continue
                                        if (mime.startsWith("audio/")) { audioTrackIndex = i; break }
                                }
                                if (audioTrackIndex < 0) throw Exception("No audio track found in: $videoUri")

                                extractor.selectTrack(audioTrackIndex)
                                val audioFormat = extractor.getTrackFormat(audioTrackIndex)

                                val muxer = android.media.MediaMuxer(
                                        outputPath,
                                        android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                                )
                                try {
                                        val muxTrack = muxer.addTrack(audioFormat)
                                        muxer.start()

                                        val buffer = java.nio.ByteBuffer.allocate(512 * 1024)
                                        val info = android.media.MediaCodec.BufferInfo()

                                        while (true) {
                                                val size = extractor.readSampleData(buffer, 0)
                                                if (size < 0) break
                                                info.offset = 0
                                                info.size = size
                                                info.presentationTimeUs = extractor.sampleTime
                                                info.flags = extractor.sampleFlags
                                                muxer.writeSampleData(muxTrack, buffer, info)
                                                extractor.advance()
                                        }

                                        muxer.stop()
                                } finally {
                                        muxer.release()
                                }
                        } finally {
                                extractor.release()
                        }

                        return@AsyncFunction outputUri
                }

                // MARK: - Waveform Generation
                AsyncFunction("getWaveform") { audioUri: String, samples: Int ->
                        return@AsyncFunction WaveformGenerator.generate(audioUri, samples)
                }

                // ... (rest of module)

                // MARK: - Video Composition (Unified Engine)
                AsyncFunction("exportComposition") { config: Map<String, Any?> ->
                        try {
                                val outputPath =
                                        config["outputPath"] as? String
                                                ?: throw Exception("Missing outputPath")
                                val videoPath =
                                        config["videoPath"] as? String
                                                ?: throw Exception("Missing videoPath")
                                val duration =
                                        config["duration"] as? Double
                                                ?: 0.0 // If 0, use video duration

                                // --- 1. Map to Video Track ---
                                val videoClip =
                                        CompositeVideoComposer.Clip(
                                                uri = videoPath,
                                                startTime = 0.0,
                                                duration =
                                                        if (duration > 0) duration
                                                        else 9999.0, // Will be clamped or detected
                                                resizeMode = "cover" // Legacy default
                                        )
                                val videoTrack =
                                        CompositeVideoComposer.Track(
                                                CompositeVideoComposer.TrackType.VIDEO,
                                                listOf(videoClip)
                                        )

                                // --- 2. Map Text Overlays ---
                                val textArray = config["textArray"] as? List<String> ?: emptyList()
                                val textX =
                                        (config["textX"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 0.5
                                        }
                                                ?: emptyList()
                                val textY =
                                        (config["textY"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 0.5
                                        }
                                                ?: emptyList()
                                val textColors =
                                        config["textColors"] as? List<String> ?: emptyList()
                                val textSizes =
                                        (config["textSizes"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 24.0
                                        }
                                                ?: emptyList()
                                val textStarts =
                                        (config["textStarts"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 0.0
                                        }
                                                ?: emptyList()
                                val textDurations =
                                        (config["textDurations"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 999.0
                                        }
                                                ?: emptyList()

                                val textClips =
                                        textArray.indices.map { i ->
                                                val color = textColors.getOrElse(i) { "#FFFFFF" }
                                                val size = textSizes.getOrElse(i) { 24.0 }
                                                val encodedUri = "text:${textArray[i]}|$color|$size"

                                                CompositeVideoComposer.Clip(
                                                        uri = encodedUri,
                                                        startTime = textStarts.getOrElse(i) { 0.0 },
                                                        duration =
                                                                textDurations.getOrElse(i) {
                                                                        999.0
                                                                },
                                                        x = textX.getOrElse(i) { 0.5 }.toFloat(),
                                                        y = textY.getOrElse(i) { 0.5 }.toFloat(),
                                                        scale = 1.0f
                                                )
                                        }
                                val textTrack =
                                        CompositeVideoComposer.Track(
                                                CompositeVideoComposer.TrackType.TEXT,
                                                textClips
                                        )

                                // --- 3. Map Emoji Overlays (Treat as Text) ---
                                val emojiArray =
                                        config["emojiArray"] as? List<String> ?: emptyList()
                                val emojiX =
                                        (config["emojiX"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 0.5
                                        }
                                                ?: emptyList()
                                val emojiY =
                                        (config["emojiY"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 0.5
                                        }
                                                ?: emptyList()
                                val emojiSizes =
                                        (config["emojiSizes"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 48.0
                                        }
                                                ?: emptyList()
                                val emojiStarts =
                                        (config["emojiStarts"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 0.0
                                        }
                                                ?: emptyList()
                                val emojiDurations =
                                        (config["emojiDurations"] as? List<*>)?.map {
                                                (it as? Number)?.toDouble() ?: 999.0
                                        }
                                                ?: emptyList()

                                val emojiClips =
                                        emojiArray.indices.map { i ->
                                                val size = emojiSizes.getOrElse(i) { 48.0 }
                                                val encodedUri =
                                                        "text:${emojiArray[i]}|#FFFFFF|$size" // Emojis ignore color but
                                                // need size

                                                CompositeVideoComposer.Clip(
                                                        uri = encodedUri,
                                                        startTime =
                                                                emojiStarts.getOrElse(i) { 0.0 },
                                                        duration =
                                                                emojiDurations.getOrElse(i) {
                                                                        999.0
                                                                },
                                                        x = emojiX.getOrElse(i) { 0.5 }.toFloat(),
                                                        y = emojiY.getOrElse(i) { 0.5 }.toFloat(),
                                                        scale = 1.0f
                                                )
                                        }
                                val emojiTrack =
                                        CompositeVideoComposer.Track(
                                                CompositeVideoComposer.TrackType.TEXT,
                                                emojiClips
                                        )

                                // --- 4. Map Audio (Music) ---
                                // TODO: Handle original video volume? (CompositeVideoComposer
                                // handles active
                                // tracks)
                                val musicPath = config["musicPath"] as? String
                                val audioTracks = mutableListOf<CompositeVideoComposer.Track>()

                                if (!musicPath.isNullOrEmpty()) {
                                        val musicClip =
                                                CompositeVideoComposer.Clip(
                                                        uri = musicPath,
                                                        startTime = 0.0,
                                                        duration =
                                                                if (duration > 0) duration
                                                                else 9999.0
                                                )
                                        audioTracks.add(
                                                CompositeVideoComposer.Track(
                                                        CompositeVideoComposer.TrackType.AUDIO,
                                                        listOf(musicClip)
                                                )
                                        )
                                }

                                val allTracks = mutableListOf<CompositeVideoComposer.Track>()
                                allTracks.add(videoTrack)
                                if (textClips.isNotEmpty()) allTracks.add(textTrack)
                                if (emojiClips.isNotEmpty()) allTracks.add(emojiTrack)
                                allTracks.addAll(audioTracks)

                                val videoBitrate =
                                        (config["videoBitrate"] as? Number)?.toInt()
                                                ?: (config["bitrate"] as? Number)?.toInt()
                                                        ?: 2000000

                                val compositionConfig =
                                        CompositeVideoComposer.CompositionConfig(
                                                outputUri = outputPath,
                                                width = 1280, // Default for legacy
                                                height = 720,
                                                frameRate = 30,
                                                bitrate = videoBitrate,
                                                videoBitrate = videoBitrate,
                                                audioBitrate =
                                                        (config["audioBitrate"] as? Number)?.toInt()
                                                                ?: 128000,
                                                enablePassthrough =
                                                        (config["enablePassthrough"] as? Boolean)
                                                                ?: true,
                                                videoProfile = (config["videoProfile"] as? String)
                                                                ?: "baseline",
                                                tracks = allTracks
                                        )

                                val reactContext =
                                        appContext.reactContext
                                                ?: throw Exception("React Context unavailable")
                                val composer =
                                        CompositeVideoComposer(reactContext, compositionConfig)
                                composer.start()

                                return@AsyncFunction outputPath
                        } catch (e: Exception) {
                                throw Exception("Video composition failed: ${e.message}")
                        }
                }

                // MARK: - Advanced Composite Video
                AsyncFunction("composeCompositeVideo") { config: CompositionConfigRecord ->
                        try {
                                validateInputs(config.tracks)

                                val tracks =
                                        config.tracks.map { trackRecord ->
                                                val type =
                                                        when (trackRecord.type) {
                                                                "audio" ->
                                                                        CompositeVideoComposer
                                                                                .TrackType.AUDIO
                                                                "text" ->
                                                                        CompositeVideoComposer
                                                                                .TrackType.TEXT
                                                                "image" ->
                                                                        CompositeVideoComposer
                                                                                .TrackType.IMAGE
                                                                else ->
                                                                        CompositeVideoComposer
                                                                                .TrackType.VIDEO
                                                        }

                                                val clips =
                                                        trackRecord.clips.map { clipRecord ->
                                                                CompositeVideoComposer.Clip(
                                                                        uri = clipRecord.uri,
                                                                        startTime = clipRecord.startTime,
                                                                        duration = clipRecord.duration,
                                                                        filter = clipRecord.filter,
                                                                        transition = clipRecord.transition,
                                                                        transitionDuration = clipRecord.transitionDuration,
                                                                        resizeMode = clipRecord.resizeMode,
                                                                        x = clipRecord.x.toFloat(),
                                                                        y = clipRecord.y.toFloat(),
                                                                        scale = clipRecord.scale.toFloat(),
                                                                        rotation = clipRecord.rotation.toFloat(),
                                                                        volume = clipRecord.volume.toFloat(),
                                                                        speed = clipRecord.speed,
                                                                        clipStart = clipRecord.clipStart,
                                                                        clipEnd = clipRecord.clipEnd,
                                                                        opacity = clipRecord.opacity.toFloat(),
                                                                        filterIntensity = clipRecord.filterIntensity.toFloat(),
                                                                        fadeInDuration = clipRecord.fadeInDuration,
                                                                        fadeOutDuration = clipRecord.fadeOutDuration,
                                                                        text = clipRecord.text,
                                                                        // Support both flat alias and textStyle-nested fields
                                                                        textColor = clipRecord.textColor.takeIf { it != "#FFFFFF" }
                                                                                ?: clipRecord.color,
                                                                        textFontSize = (clipRecord.textFontSize.takeIf { it != 64.0 }
                                                                                ?: clipRecord.fontSize).toFloat(),
                                                                        textFontBold = clipRecord.textFontBold,
                                                                        textBackgroundColor = clipRecord.textBackgroundColor,
                                                                        textBackgroundPadding = clipRecord.textBackgroundPadding.toFloat(),
                                                                        textShadowColor = clipRecord.textShadowColor,
                                                                        textShadowRadius = clipRecord.textShadowRadius.toFloat(),
                                                                        textShadowOffsetX = clipRecord.textShadowOffsetX.toFloat(),
                                                                        textShadowOffsetY = clipRecord.textShadowOffsetY.toFloat(),
                                                                        textStrokeColor = clipRecord.textStrokeColor,
                                                                        textStrokeWidth = clipRecord.textStrokeWidth.toFloat(),
                                                                        volumeKeyframes = clipRecord.volumeEnvelope
                                                                                ?.keyframes
                                                                                ?.map { kf -> Pair(kf.time, kf.volume.toFloat()) }
                                                                                ?: emptyList()
                                                                )
                                                        }
                                                CompositeVideoComposer.Track(type, clips)
                                        }

                                // Dynamic Defaults Logic
                                var width = config.width
                                var height = config.height
                                var frameRate = config.frameRate
                                var videoBitrate = config.videoBitrate ?: config.bitrate
                                var audioBitrate = config.audioBitrate

                                // If any crucial config is missing, try to read from source
                                if (width == null ||
                                                height == null ||
                                                frameRate == null ||
                                                videoBitrate == null
                                ) {
                                        val firstVideoTrack =
                                                config.tracks.firstOrNull { it.type == "video" }
                                        val firstClip = firstVideoTrack?.clips?.firstOrNull()

                                        if (firstClip != null) {
                                                val path =
                                                        if (firstClip.uri.startsWith("file://"))
                                                                firstClip.uri.substring(7)
                                                        else firstClip.uri
                                                val metadata = extractMetadata(path)

                                                if (metadata != null) {
                                                        if (width == null) width = metadata.width
                                                        if (height == null) height = metadata.height

                                                        // Metadata rotation is already handled in
                                                        // dimensions by extractMetadata,
                                                        // but if we want to respect original file
                                                        // rotation tag vs composed buffer...
                                                        // Actually, extractMetadata returns
                                                        // query-able w/h.
                                                        // If rotation is 90/270, we should swap.
                                                        // Let's refine extractMetadata to return
                                                        // raw w/h and rotation.

                                                        if ((metadata.rotation == 90 ||
                                                                        metadata.rotation == 270) &&
                                                                        width != null &&
                                                                        height != null
                                                        ) {
                                                                val w = width!!
                                                                width = height
                                                                height = w
                                                        }

                                                        if (frameRate == null)
                                                                frameRate = metadata.frameRate
                                                        if (videoBitrate == null)
                                                                videoBitrate = metadata.bitrate
                                                }
                                        }
                                }

                                // Final Defaults if still null
                                val finalWidth = width ?: 1280
                                val finalHeight = height ?: 720
                                val finalFrameRate = frameRate ?: 30
                                val finalVideoBitrate = videoBitrate ?: 2000000
                                val finalAudioBitrate = audioBitrate ?: 128000

                                val compositionConfig =
                                        CompositeVideoComposer.CompositionConfig(
                                                outputUri = config.outputUri,
                                                width = finalWidth,
                                                height = finalHeight,
                                                frameRate = finalFrameRate,
                                                bitrate = finalVideoBitrate,
                                                videoBitrate = finalVideoBitrate,
                                                audioBitrate = finalAudioBitrate,
                                                enablePassthrough = config.enablePassthrough,
                                                videoProfile = config.videoProfile,
                                                tracks = tracks
                                        )

                                val reactContext =
                                        appContext.reactContext
                                                ?: throw Exception("React Context unavailable")
                                val composer =
                                        CompositeVideoComposer(reactContext, compositionConfig)
                                // Run synchronously as AsyncFunction is already backgrounded
                                composer.start()

                                return@AsyncFunction config.outputUri
                        } catch (e: Exception) {
                                throw Exception("Composition failed: ${e.message}")
                        }
                }

                // MARK: - Utilities
                AsyncFunction("stitchVideos") { videoPaths: List<String>, outputUri: String ->
                        try {
                                return@AsyncFunction VideoStitcher.stitch(videoPaths, outputUri)
                        } catch (e: Exception) {
                                android.util.Log.w(
                                        "MediaEngine",
                                        "Fast stitching failed, falling back to transcoding",
                                        e
                                )

                                if (videoPaths.isEmpty()) throw Exception("No videos to stitch")

                                // Fallback: Transcode
                                val reactContext =
                                        appContext.reactContext
                                                ?: throw Exception("React Context unavailable")

                                // 1. Probe first video for defaults
                                val firstVideo = videoPaths[0]
                                val path =
                                        if (firstVideo.startsWith("file://"))
                                                firstVideo.substring(7)
                                        else firstVideo
                                val metadata =
                                        extractMetadata(path)
                                                ?: VideoMetadata(1280, 720, 30, 2000000, 0)

                                var width = metadata.width
                                var height = metadata.height
                                if (metadata.rotation == 90 || metadata.rotation == 270) {
                                        val temp = width
                                        width = height
                                        height = temp
                                }

                                // 2. Create Tracks
                                val clips =
                                        videoPaths.map {
                                                CompositeVideoComposer.Clip(
                                                        uri = it,
                                                        startTime = 0.0,
                                                        duration =
                                                                9999.0, // Composer will determine
                                                        // from file
                                                        resizeMode =
                                                                "contain" // Safest for stitching
                                                        // different aspects
                                                        )
                                        }
                                val videoTrack =
                                        CompositeVideoComposer.Track(
                                                CompositeVideoComposer.TrackType.VIDEO,
                                                clips
                                        )

                                val config =
                                        CompositeVideoComposer.CompositionConfig(
                                                outputUri = outputUri,
                                                width = width,
                                                height = height,
                                                frameRate = metadata.frameRate,
                                                bitrate = metadata.bitrate,
                                                videoBitrate = metadata.bitrate,
                                                tracks = listOf(videoTrack)
                                        )

                                val composer = CompositeVideoComposer(reactContext, config)
                                composer.start()
                                return@AsyncFunction outputUri
                        }
                }

                // MARK: - Video Compression
                AsyncFunction("compressVideo") { config: CompressVideoRecord ->
                    try {
                        if (config.inputUri.isEmpty()) throw Exception("Missing inputUri")
                        if (config.outputUri.isEmpty()) throw Exception("Missing outputUri")

                        val reactContext = appContext.reactContext
                            ?: throw Exception("React Context unavailable")

                        // Resolve bitrate from explicit value or quality string
                        val bitrate = config.bitrate ?: when (config.quality) {
                            "low" -> 1_000_000
                            "high" -> 8_000_000
                            else -> 4_000_000
                        }
                        val audioBitrate = config.audioBitrate ?: 128_000

                        // Resolve output dimensions, respecting maxWidth/maxHeight constraints
                        var width = config.width
                        var height = config.height

                        if (config.maxWidth != null || config.maxHeight != null) {
                            // Probe source to calculate constrained dimensions
                            val sourceExtractor = android.media.MediaExtractor()
                            sourceExtractor.setDataSource(reactContext, android.net.Uri.parse(config.inputUri), null)
                            var srcW = 1280; var srcH = 720
                            for (i in 0 until sourceExtractor.trackCount) {
                                val f = sourceExtractor.getTrackFormat(i)
                                if (f.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                                    if (f.containsKey(android.media.MediaFormat.KEY_WIDTH)) srcW = f.getInteger(android.media.MediaFormat.KEY_WIDTH)
                                    if (f.containsKey(android.media.MediaFormat.KEY_HEIGHT)) srcH = f.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                                    break
                                }
                            }
                            sourceExtractor.release()

                            if (width == null && height == null) {
                                val maxW = config.maxWidth ?: Int.MAX_VALUE
                                val maxH = config.maxHeight ?: Int.MAX_VALUE
                                if (srcW > maxW || srcH > maxH) {
                                    val scale = minOf(maxW.toFloat() / srcW, maxH.toFloat() / srcH)
                                    width = (srcW * scale).toInt()
                                    height = (srcH * scale).toInt()
                                }
                            }
                        }

                        val compressConfig = VideoCompressor.CompressConfig(
                            inputUri = config.inputUri,
                            outputUri = config.outputUri,
                            width = width,
                            height = height,
                            bitrate = bitrate,
                            frameRate = config.frameRate,
                            audioBitrate = audioBitrate,
                            codec = config.codec,
                            copyAudio = true
                        )

                        VideoCompressor(reactContext, compressConfig).compress()
                        return@AsyncFunction config.outputUri
                    } catch (e: Exception) {
                        throw Exception("Video compression failed: ${e.message}")
                    }
                }
        }

        private fun validateInputs(tracks: List<CompositionTrackRecord>) {
                // Validation relaxed to allow RenderEngine to handle loading
        }

        private data class VideoMetadata(
                val width: Int,
                val height: Int,
                val frameRate: Int,
                val bitrate: Int,
                val rotation: Int
        )

        private fun extractMetadata(path: String): VideoMetadata? {
                try {
                        val extractor = android.media.MediaExtractor()
                        extractor.setDataSource(path)

                        for (i in 0 until extractor.trackCount) {
                                val format = extractor.getTrackFormat(i)
                                val mime = format.getString(android.media.MediaFormat.KEY_MIME)
                                if (mime?.startsWith("video/") == true) {
                                        val width =
                                                if (format.containsKey(
                                                                android.media.MediaFormat.KEY_WIDTH
                                                        )
                                                )
                                                        format.getInteger(
                                                                android.media.MediaFormat.KEY_WIDTH
                                                        )
                                                else 1280
                                        val height =
                                                if (format.containsKey(
                                                                android.media.MediaFormat.KEY_HEIGHT
                                                        )
                                                )
                                                        format.getInteger(
                                                                android.media.MediaFormat.KEY_HEIGHT
                                                        )
                                                else 720

                                        val rotation =
                                                if (format.containsKey(
                                                                android.media.MediaFormat
                                                                        .KEY_ROTATION
                                                        )
                                                )
                                                        format.getInteger(
                                                                android.media.MediaFormat
                                                                        .KEY_ROTATION
                                                        )
                                                else 0

                                        var frameRate = 30
                                        if (format.containsKey(
                                                        android.media.MediaFormat.KEY_FRAME_RATE
                                                )
                                        ) {
                                                frameRate =
                                                        format.getInteger(
                                                                android.media.MediaFormat
                                                                        .KEY_FRAME_RATE
                                                        )
                                        }

                                        var bitrate = 2000000
                                        if (format.containsKey(
                                                        android.media.MediaFormat.KEY_BIT_RATE
                                                )
                                        ) {
                                                bitrate =
                                                        format.getInteger(
                                                                android.media.MediaFormat
                                                                        .KEY_BIT_RATE
                                                        )
                                        }

                                        extractor.release()
                                        return VideoMetadata(
                                                width,
                                                height,
                                                frameRate,
                                                bitrate,
                                                rotation
                                        )
                                }
                        }
                        extractor.release()
                } catch (e: Exception) {
                        // android.util.Log.w("MediaEngine", "Metadata extraction failed", e)
                }
                return null
        }
}
