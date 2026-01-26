
package com.projectyoked.mediaengine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.sqrt

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
}

class CompositionTrackRecord : Record {
    @Field val type: String = "video"
    @Field val clips: List<CompositionClipRecord> = emptyList()
}

class CompositionConfigRecord : Record {
    @Field val outputUri: String = ""
    @Field val width: Int = 1280
    @Field val height: Int = 720
    @Field val frameRate: Int = 30
    @Field val bitrate: Int = 2000000
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
        val outputPath = config["outputPath"] as? String ?: throw Exception("Missing outputPath")
        val videoPath = config["videoPath"] as? String ?: throw Exception("Missing videoPath")
        val duration = config["duration"] as? Double ?: 0.0 // If 0, use video duration
        
        // --- 1. Map to Video Track ---
        val videoClip = CompositeVideoComposer.Clip(
            uri = videoPath,
            startTime = 0.0,
            duration = if (duration > 0) duration else 9999.0, // Will be clamped or detected
            resizeMode = "cover" // Legacy default
        )
        val videoTrack = CompositeVideoComposer.Track(
            CompositeVideoComposer.TrackType.VIDEO,
            listOf(videoClip)
        )
        
        // --- 2. Map Text Overlays ---
        val textArray = config["textArray"] as? List<String> ?: emptyList()
        val textX = (config["textX"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.5 } ?: emptyList()
        val textY = (config["textY"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.5 } ?: emptyList()
        val textColors = config["textColors"] as? List<String> ?: emptyList()
        val textSizes = (config["textSizes"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 24.0 } ?: emptyList()
        val textStarts = (config["textStarts"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: emptyList()
        val textDurations = (config["textDurations"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 999.0 } ?: emptyList()
        
        val textClips = textArray.indices.map { i ->
            val color = textColors.getOrElse(i) { "#FFFFFF" }
            val size = textSizes.getOrElse(i) { 24.0 }
            val encodedUri = "text:${textArray[i]}|$color|$size"
            
            CompositeVideoComposer.Clip(
                uri = encodedUri,
                startTime = textStarts.getOrElse(i) { 0.0 },
                duration = textDurations.getOrElse(i) { 999.0 },
                x = textX.getOrElse(i) { 0.5 }.toFloat(),
                y = textY.getOrElse(i) { 0.5 }.toFloat(),
                scale = 1.0f 
            )
        }
        val textTrack = CompositeVideoComposer.Track(CompositeVideoComposer.TrackType.TEXT, textClips)
        
        // --- 3. Map Emoji Overlays (Treat as Text) ---
        val emojiArray = config["emojiArray"] as? List<String> ?: emptyList()
        val emojiX = (config["emojiX"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.5 } ?: emptyList()
        val emojiY = (config["emojiY"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.5 } ?: emptyList()
        val emojiSizes = (config["emojiSizes"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 48.0 } ?: emptyList()
        val emojiStarts = (config["emojiStarts"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 0.0 } ?: emptyList()
        val emojiDurations = (config["emojiDurations"] as? List<*>)?.map { (it as? Number)?.toDouble() ?: 999.0 } ?: emptyList()

        val emojiClips = emojiArray.indices.map { i ->
            val size = emojiSizes.getOrElse(i) { 48.0 }
            val encodedUri = "text:${emojiArray[i]}|#FFFFFF|$size" // Emojis ignore color but need size
            
            CompositeVideoComposer.Clip(
                uri = encodedUri,
                startTime = emojiStarts.getOrElse(i) { 0.0 },
                duration = emojiDurations.getOrElse(i) { 999.0 },
                x = emojiX.getOrElse(i) { 0.5 }.toFloat(),
                y = emojiY.getOrElse(i) { 0.5 }.toFloat(),
                scale = 1.0f
            )
        }
        val emojiTrack = CompositeVideoComposer.Track(CompositeVideoComposer.TrackType.TEXT, emojiClips)
        
        // --- 4. Map Audio (Music) ---
        // TODO: Handle original video volume? (CompositeVideoComposer handles active tracks)
        val musicPath = config["musicPath"] as? String
        val audioTracks = mutableListOf<CompositeVideoComposer.Track>()
        
        if (!musicPath.isNullOrEmpty()) {
             val musicClip = CompositeVideoComposer.Clip(
                 uri = musicPath,
                 startTime = 0.0,
                 duration = if (duration > 0) duration else 9999.0
             )
             audioTracks.add(CompositeVideoComposer.Track(CompositeVideoComposer.TrackType.AUDIO, listOf(musicClip)))
        }

        val allTracks = mutableListOf<CompositeVideoComposer.Track>()
        allTracks.add(videoTrack)
        if (textClips.isNotEmpty()) allTracks.add(textTrack)
        if (emojiClips.isNotEmpty()) allTracks.add(emojiTrack)
        allTracks.addAll(audioTracks)
        
        val compositionConfig = CompositeVideoComposer.CompositionConfig(
            outputUri = outputPath,
            width = 1280, // Default for legacy
            height = 720,
            frameRate = 30,

            bitrate = (config["bitrate"] as? Number)?.toInt() ?: 2000000,
            tracks = allTracks
        )
        
        val reactContext = appContext.reactContext ?: throw Exception("React Context unavailable")
        val composer = CompositeVideoComposer(reactContext, compositionConfig)
        composer.start()
        
        return@AsyncFunction outputPath
      } catch (e: Exception) {
        throw Exception("Video composition failed: ${e.message}")
      }
    }

    // MARK: - Advanced Composite Video
    AsyncFunction("composeCompositeVideo") { config: CompositionConfigRecord ->
      try {
        val tracks = config.tracks.map { trackRecord ->
            val type = when(trackRecord.type) {
                "audio" -> CompositeVideoComposer.TrackType.AUDIO
                "text" -> CompositeVideoComposer.TrackType.TEXT
                else -> CompositeVideoComposer.TrackType.VIDEO
            }
            
            val clips = trackRecord.clips.map { clipRecord ->
                CompositeVideoComposer.Clip(
                    uri = clipRecord.uri,
                    startTime = clipRecord.startTime,
                    duration = clipRecord.duration,
                    filter = clipRecord.filter,
                    transition = clipRecord.transition,
                    resizeMode = clipRecord.resizeMode,
                    x = clipRecord.x.toFloat(),
                    y = clipRecord.y.toFloat(),
                    scale = clipRecord.scale.toFloat(),
                    rotation = clipRecord.rotation.toFloat()
                )
            }
            CompositeVideoComposer.Track(type, clips)
        }

        val compositionConfig = CompositeVideoComposer.CompositionConfig(
            outputUri = config.outputUri,
            width = config.width,
            height = config.height,
            frameRate = config.frameRate,
            bitrate = config.bitrate,
            tracks = tracks
        )
        
        val reactContext = appContext.reactContext ?: throw Exception("React Context unavailable")
        val composer = CompositeVideoComposer(reactContext, compositionConfig)
        // Run synchronously as AsyncFunction is already backgrounded
        composer.start() 
        
        return@AsyncFunction config.outputUri
      } catch (e: Exception) {
        throw Exception("Composition failed: ${e.message}")
      }
    }
  }
}
