package com.projectyoked.mediaengine

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class CompositionClipRecord : Record {
        @Field val uri: String = ""
        @Field val startTime: Double = 0.0
        @Field val duration: Double = 0.0
        @Field val filter: String? = null
        @Field val transition: String? = null
        @Field val resizeMode: String = "cover"
        @Field val x: Double = 0.0
        @Field val y: Double = 0.0
        @Field val scale: Double = 1.0
        @Field val rotation: Double = 0.0
        @Field val volume: Double = 1.0
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

class MediaEngineModule : Module() {
        override fun definition() = ModuleDefinition {
                Name("MediaEngine")

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
                                                                else ->
                                                                        CompositeVideoComposer
                                                                                .TrackType.VIDEO
                                                        }

                                                val clips =
                                                        trackRecord.clips.map { clipRecord ->
                                                                CompositeVideoComposer.Clip(
                                                                        uri = clipRecord.uri,
                                                                        startTime =
                                                                                clipRecord
                                                                                        .startTime,
                                                                        duration =
                                                                                clipRecord.duration,
                                                                        filter = clipRecord.filter,
                                                                        transition =
                                                                                clipRecord
                                                                                        .transition,
                                                                        resizeMode =
                                                                                clipRecord
                                                                                        .resizeMode,
                                                                        x = clipRecord.x.toFloat(),
                                                                        y = clipRecord.y.toFloat(),
                                                                        scale =
                                                                                clipRecord.scale
                                                                                        .toFloat(),
                                                                        rotation =
                                                                                clipRecord.rotation
                                                                                        .toFloat(),
                                                                        volume =
                                                                                clipRecord.volume
                                                                                        .toFloat()
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
