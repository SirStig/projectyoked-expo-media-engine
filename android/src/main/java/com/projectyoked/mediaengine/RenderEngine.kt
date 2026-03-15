package com.projectyoked.mediaengine

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.io.File

/**
 * RenderEngine
 *
 * Core logic for rendering a composite timeline at a specific timestamp. Decoupled from MediaCodec
 * encoding to allow for Live Previews.
 */
class RenderEngine(val context: Context, val config: CompositeVideoComposer.CompositionConfig) {

    private var textureRenderer: TextureRenderer? = null
    private val extractors = mutableMapOf<String, MediaExtractor>()
    private val decoders = mutableMapOf<String, MediaCodec>()
    private val surfaces = mutableMapOf<String, android.view.Surface>()
    private val surfaceTextures = mutableMapOf<String, SurfaceTexture>()
    private val lastRenderedTime = mutableMapOf<String, Long>()
    private val videoTextureIds = mutableMapOf<String, Int>()
    private val bitmapTextures = mutableMapOf<String, Int>()

    // Cached video dimensions per URI to avoid re-querying track format every frame
    private data class VideoSize(val width: Int, val height: Int, val rotation: Int)
    private val videoMetadata = mutableMapOf<String, VideoSize>()

    // Transform matrix for texture
    private val transformMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Cache for overlay bitmaps/textures
    private val overlayTextureCache = mutableMapOf<String, Int>()

    fun prepare() {
        textureRenderer = TextureRenderer()
        textureRenderer?.surfaceCreated()

        // Pre-initialize extractors for video clips
        // In a real app we might load lazily, but for now load all unique video URIs
        config.tracks
                .filter { it.type == CompositeVideoComposer.TrackType.VIDEO }
                .flatMap { it.clips }
                .map { it.uri }
                .distinct()
                .forEach { uri ->
                    val mime = getMimeType(uri)
                    if (mime.startsWith("video/")) {
                        if (!extractors.containsKey(uri)) {
                            Log.d("RenderEngine", "Initializing extractor for: $uri")
                            val extractor = MediaExtractor()
                            try {
                                extractor.setDataSource(context, Uri.parse(uri), null)
                                extractors[uri] = extractor
                            } catch (e: Exception) {
                                Log.e("RenderEngine", "Failed to set data source for $uri", e)
                            }
                        }
                    } else if (mime.startsWith("image/")) {
                        // Lazy Load: Do nothing here
                    }
                }

        if (extractors.isEmpty()) {
            throw RuntimeException(
                    "No extractors initialized! Input URIs: ${config.tracks.flatMap { it.clips }.map { it.uri }}"
            )
        }
    }

    /**
     * Renders the composition at the given timestamp (in microseconds) to the currently bound
     * framebuffer.
     */
    fun render(currentTimeUs: Long) {
        val currentTimeSec = currentTimeUs / 1_000_000.0

        // Clear Screen
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 1. Render Video + Image Tracks (Bottom to Top)
        val visualTrackTypes = setOf(CompositeVideoComposer.TrackType.VIDEO, CompositeVideoComposer.TrackType.IMAGE)
        val visualTracks = config.tracks.filter { it.type in visualTrackTypes }

        visualTracks.forEach { track ->
            val activeClip = track.clips.find {
                currentTimeSec >= it.startTime && currentTimeSec < (it.startTime + it.duration)
            }
            if (activeClip != null) {
                renderClip(activeClip, currentTimeUs)
            }
        }

        // 2. Render Text/Overlay Tracks (Top)
        val textTracks = config.tracks.filter { it.type == CompositeVideoComposer.TrackType.TEXT }
        textTracks.forEach { track ->
            track.clips.forEach { overlay ->
                if (currentTimeSec >= overlay.startTime &&
                                currentTimeSec < (overlay.startTime + overlay.duration)
                ) {
                    renderOverlay(overlay)
                }
            }
        }

        // 3. Resource Management
        manageResources(currentTimeUs)
    }

    private fun manageResources(currentTimeUs: Long) {
        // Lookahead window: 5 seconds. Keep resources if needed soon.
        val lookaheadUs = 5_000_000L
        val activeAndUpcoming =
                config.tracks
                        .flatMap { it.clips }
                        .filter { clip ->
                            val startUs = (clip.startTime * 1_000_000).toLong()
                            val endUs = ((clip.startTime + clip.duration) * 1_000_000).toLong()
                            // Active OR Starts within lookahead
                            (currentTimeUs in startUs..endUs) ||
                                    (startUs > currentTimeUs &&
                                            startUs < currentTimeUs + lookaheadUs)
                        }
                        .map { it.uri }
                        .toSet()

        // Cleanup Decoders
        val decodersToRemove = decoders.keys.filter { !activeAndUpcoming.contains(it) }
        decodersToRemove.forEach { uri ->
            Log.d("RenderEngine", "Releasing disabled decoder: $uri")
            decoders[uri]?.stop()
            decoders[uri]?.release()
            decoders.remove(uri)

            surfaces[uri]?.release()
            surfaces.remove(uri)

            surfaceTextures[uri]?.release()
            // textureRenderer.deleteTexture(videoTextureIds[uri]) // If we had a delete method
            // For now, we leak the texture ID slightly or reuse?
            // In heavy OpenGL, we should delete. `glDeleteTextures`.
            // Let's delete if we have the ID.
            val texId = videoTextureIds[uri]
            if (texId != null) {
                val ids = intArrayOf(texId)
                GLES20.glDeleteTextures(1, ids, 0)
                videoTextureIds.remove(uri)
            }
            surfaceTextures.remove(uri)

            extractors[uri]?.release()
            extractors.remove(uri)
            videoMetadata.remove(uri)
        }

        // Cleanup Bitmaps (Images)
        val imagesToRemove = bitmapTextures.keys.filter { !activeAndUpcoming.contains(it) }
        imagesToRemove.forEach { uri ->
            Log.d("RenderEngine", "Releasing image texture: $uri")
            val texId = bitmapTextures[uri]
            if (texId != null) {
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            }
            bitmapTextures.remove(uri)
        }
    }

    private fun renderClip(clip: CompositeVideoComposer.Clip, timelineTimeUs: Long) {
        val uri = clip.uri

        val mime = getMimeType(uri)
        if (mime.startsWith("image/")) {
            // Lazy Load
            if (!bitmapTextures.containsKey(uri)) {
                Log.d("RenderEngine", "Lazy Loading image: $uri")
                val path = File(Uri.parse(uri).path ?: uri).absolutePath
                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    val tId = loadTexture(bitmap)
                    bitmapTextures[uri] = tId
                    bitmap.recycle() // Texture is loaded, recycle bitmap
                } else {
                    return // Failed to load
                }
            }

            val texId = bitmapTextures[uri]!!

            // Calculate Aspect Ratio for Image
            // We need original dimensions. We didn't store them.
            // Improvement: Store ImageMetaData(width, height, texId)
            // For now, let's assume square or extract from somewhere?
            // Actually `loadTexture` doesn't return dims.
            // Let's reload options just for dims or store it.
            // Re-decoding bounds is cheap.
            val options = android.graphics.BitmapFactory.Options()
            options.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(
                    File(Uri.parse(uri).path ?: uri).absolutePath,
                    options
            )
            val width = options.outWidth
            val height = options.outHeight

            renderVisual(clip, texId, width, height, isOES = false)
            return
        }

        // Video Logic
        // Ensure Extractor Exists logic was removed in manageResources, so we need to add it back
        // here mostly.
        // Or check if extractors[uri] exists.

        if (!extractors.containsKey(uri)) {
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(context, Uri.parse(uri), null)
                extractors[uri] = extractor
            } catch (e: Exception) {
                Log.e("RenderEngine", "Failed to lazy load extractor for $uri", e)
                return
            }
        }
        val extractor = extractors[uri]!!

        // Ensure Decoder Exists (single canonical init block)
        if (!decoders.containsKey(uri)) {
            val trackIndex = selectVideoTrack(extractor)
            if (trackIndex < 0) {
                Log.e("RenderEngine", "No video track found for $uri")
                return
            }
            extractor.selectTrack(trackIndex)
            Log.d("RenderEngine", "Selected track $trackIndex for $uri")

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return

            // Cache video dimensions so we don't re-query every frame
            val rawW = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 1280
            val rawH = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 720
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            videoMetadata[uri] = VideoSize(rawW, rawH, rotation)

            val decoder = MediaCodec.createDecoderByType(mime)
            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            val texId = texIds[0]

            val st = SurfaceTexture(texId)
            val surface = android.view.Surface(st)

            decoder.configure(format, surface, null, 0)
            decoder.start()

            decoders[uri] = decoder
            surfaces[uri] = surface
            surfaceTextures[uri] = st
            videoTextureIds[uri] = texId

            // Seek to clipStart on first init
            val clipStartUs = (clip.clipStart * 1_000_000).toLong()
            if (clipStartUs > 0) {
                extractor.seekTo(clipStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            // Seed lastRenderedTime so frame 2 doesn't trigger an unnecessary seek
            lastRenderedTime[uri] = clipStartUs
        } else {
            // Compute source-relative time accounting for clipStart offset
            val clipRelativeTimeUs = timelineTimeUs - (clip.startTime * 1_000_000).toLong()
            val sourceTimeUs = (clip.clipStart * 1_000_000).toLong() + clipRelativeTimeUs

            val lastTime = lastRenderedTime[uri] ?: -1L
            val isSequential = lastTime >= 0 && kotlin.math.abs(sourceTimeUs - lastTime) < 200_000L

            if (!isSequential) {
                Log.d("RenderEngine", "Seeking to $sourceTimeUs (Last: $lastTime)")
                extractor.seekTo(sourceTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                decoders[uri]?.flush()
            }
            lastRenderedTime[uri] = sourceTimeUs
        }

        val decoder = decoders[uri]!!
        val st = surfaceTextures[uri]!!
        val texId = videoTextureIds[uri]!!

        // Use cached video metadata (avoid re-querying track format every frame)
        val meta = videoMetadata[uri]
        val srcW = if (meta != null && (meta.rotation == 90 || meta.rotation == 270)) meta.height else meta?.width ?: 1280
        val srcH = if (meta != null && (meta.rotation == 90 || meta.rotation == 270)) meta.width else meta?.height ?: 720

        // clipEnd enforcement: stop feeding frames beyond clipEnd
        val clipEndUs = if (clip.clipEnd > 0) (clip.clipEnd * 1_000_000).toLong() else Long.MAX_VALUE

        // Feed and Drain Loop (Interleaved)
        val maxTimeMs = System.currentTimeMillis() + 2500
        val bufferInfo = MediaCodec.BufferInfo()
        var frameRendered = false

        while (System.currentTimeMillis() < maxTimeMs && !frameRendered) {
            // 1. Feed Input (Burst)
            var inputAvailable = true
            while (inputAvailable) {
                val inputBufIdx = decoder.dequeueInputBuffer(0)
                if (inputBufIdx >= 0) {
                    val buf = decoder.getInputBuffer(inputBufIdx)!!
                    val sampleTime = extractor.sampleTime
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0 || sampleTime > clipEndUs) {
                        Log.d("RenderEngine", "Input EOS at $sampleTime (clipEnd=$clipEndUs)")
                        decoder.queueInputBuffer(inputBufIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputAvailable = false
                    } else {
                        decoder.queueInputBuffer(inputBufIdx, 0, sampleSize, sampleTime, 0)
                        extractor.advance()
                    }
                } else {
                    inputAvailable = false
                }
            }

            // 2. Drain Output (Burst until frame)
            var outputAvailable = true
            while (outputAvailable && !frameRendered) {
                val outBufIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outBufIdx >= 0) {
                    if (bufferInfo.size > 0) {
                        decoder.releaseOutputBuffer(outBufIdx, true)
                        st.updateTexImage()
                        val stMatrix = FloatArray(16)
                        st.getTransformMatrix(stMatrix)

                        renderVisual(clip, texId, srcW, srcH, isOES = true, stMatrix = stMatrix)
                        frameRendered = true
                        outputAvailable = false
                    } else {
                        decoder.releaseOutputBuffer(outBufIdx, false)
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d("RenderEngine", "Output EOS encountered")
                        return
                    }
                } else if (outBufIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    outputAvailable = false
                } else if (outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d("RenderEngine", "Output Format Changed: ${decoder.outputFormat}")
                }
            }
        }

        if (!frameRendered) {
            Log.w("RenderEngine", "Decoder timed out for $uri at $timelineTimeUs (Max wait 2500ms)")
        }
    }

    private fun renderOverlay(overlay: CompositeVideoComposer.Clip) {
        // Generate Bitmap if needed (cache)
        if (!overlayTextureCache.containsKey(overlay.uri)) {
            val bitmap = generateOverlayBitmap(overlay.uri)
            if (bitmap != null) {
                val texId = loadTexture(bitmap)
                overlayTextureCache[overlay.uri] = texId
                // Don't recycle immediately if we want to reload, but here we just cache texture.
                bitmap.recycle()
            }
        }

        val texId = overlayTextureCache[overlay.uri]
        if (texId != null) {
            // Coordinate System:
            // Clip X,Y are normalized -1..1 (or 0..1 depending on our convention).
            // Let's assume the input config uses 0..1 (top-left origin).
            // GL expects normalized device coordinates -1..1 (center origin).

            // Map 0..1 to -1..1
            // x: 0 -> -1, 1 -> 1  => (x * 2) - 1
            // y: 0 -> 1, 1 -> -1 (GL Y is up, Screen Y is down) => 1 - (y * 2)

            val glX = (overlay.x * 2) - 1
            val glY = 1 - (overlay.y * 2)

            // Scale?
            // Overlay Bitmap is 1024x1024 (Square)
            // We render to a quad -1..1 (Square NDC).
            // Aspect Ratio Correction:
            // If we draw a square in NDC on a rectangular screen, it stretches.
            // Screen Aspect = Width / Height (e.g. 720/1280 = ~0.56)
            // We want square pixels.
            // If we keep X scale (Width) as reference, we must scale Y by Aspect Ratio.
            val viewAspect = config.width.toFloat() / config.height.toFloat()

            // Pass separate X/Y scales to maintain squareness of the texture content relative to
            // screen
            // Base scale applies to both.
            // Correction: Scale Y * viewAspect.
            textureRenderer!!.drawOverlay(
                    texId,
                    glX,
                    glY,
                    overlay.scale,
                    overlay.scale * viewAspect
            )
        }
    }

    // ...

    // Helpers
    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun generateOverlayBitmap(content: String): android.graphics.Bitmap? {
        // Format: text:<Text>|#COLOR|SIZE
        val parts =
                if (content.startsWith("text:")) content.substring(5).split("|")
                else listOf(content)
        val textParam = parts.getOrElse(0) { "" }
        val colorParam = parts.getOrElse(1) { "#FFFFFF" }
        val sizeParam = parts.getOrElse(2) { "64" }.toFloatOrNull() ?: 64f

        // High resolution for text crispness (2048x2048)
        val bitmap =
                android.graphics.Bitmap.createBitmap(
                        2048,
                        2048,
                        android.graphics.Bitmap.Config.ARGB_8888
                )
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()

        try {
            paint.color = android.graphics.Color.parseColor(colorParam)
        } catch (e: Exception) {
            paint.color = android.graphics.Color.WHITE
        }

        // Scale font size: Base config is relative to 720p usually.
        // We are drawing to 2048x2048.
        // If config size is ~64 (points), we want it large.
        paint.textSize = sizeParam * 4f
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = true
        paint.textAlign = android.graphics.Paint.Align.CENTER

        // Draw centered
        val x = canvas.width / 2f
        val y = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)

        // Handle Emoji (basic system font support)
        // If it's an emoji, it should just draw.

        canvas.drawText(textParam, x, y, paint)
        return bitmap
    }

    private fun loadTexture(bitmap: android.graphics.Bitmap): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        if (textureHandle[0] != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR_MIPMAP_LINEAR
            )
            GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR
            )
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
        return textureHandle[0]
    }

    // Helper to get mime type
    private fun getMimeType(uri: String): String {
        val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "video/mp4"
    }

    // Generalized render for both Video (OES) and Image (2D)
    private fun renderVisual(
            clip: CompositeVideoComposer.Clip,
            texId: Int,
            width: Int,
            height: Int,
            isOES: Boolean,
            stMatrix: FloatArray? = null
    ) {
        var videoAspect = width.toFloat() / height.toFloat()

        val userRotation = kotlin.math.round(clip.rotation).toInt() % 360
        if (userRotation == 90 || userRotation == 270) {
            videoAspect = 1f / videoAspect
        }
        val viewAspect = config.width.toFloat() / config.height.toFloat()

        Matrix.setIdentityM(mvpMatrix, 0)

        // TRS: Translate → Rotate → Scale
        Matrix.translateM(mvpMatrix, 0, clip.x, clip.y, 0f)
        Matrix.rotateM(mvpMatrix, 0, clip.rotation, 0f, 0f, 1f)
        Matrix.scaleM(mvpMatrix, 0, clip.scale, clip.scale, 1f)

        // Aspect ratio correction based on resizeMode
        var scaleX = 1f
        var scaleY = 1f

        if (clip.resizeMode == "stretch") {
            scaleX = 1f
            scaleY = 1f
        } else if (clip.resizeMode == "cover") {
            if (videoAspect > viewAspect) {
                scaleX = videoAspect / viewAspect
                scaleY = 1f
            } else {
                scaleX = 1f
                scaleY = viewAspect / videoAspect
            }
        } else { // contain
            if (videoAspect > viewAspect) {
                scaleX = 1f
                scaleY = viewAspect / videoAspect
            } else {
                scaleX = videoAspect / viewAspect
                scaleY = 1f
            }
        }

        // Swap scale factors if user rotation is 90/270 (content is rotated relative to screen)
        if (userRotation == 90 || userRotation == 270) {
            val temp = scaleX
            scaleX = scaleY
            scaleY = temp
        }

        Matrix.scaleM(mvpMatrix, 0, scaleX, scaleY, 1f)

        val filterType = when (clip.filter) {
            "grayscale" -> 1
            "sepia" -> 2
            "vignette" -> 3
            "invert" -> 4
            else -> 0
        }

        if (isOES) {
            textureRenderer!!.draw(texId, stMatrix, mvpMatrix, filterType, clip.filterIntensity)
        } else {
            textureRenderer!!.draw2D(texId, mvpMatrix, filterType)
        }
    }

    fun release() {
        extractors.values.forEach { it.release() }
        extractors.clear()

        decoders.values.forEach {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                Log.e("RenderEngine", "Error releasing decoder", e)
            }
        }
        decoders.clear()

        surfaces.values.forEach { it.release() }
        surfaces.clear()

        surfaceTextures.values.forEach { it.release() }
        surfaceTextures.clear()

        videoTextureIds.clear()
        videoMetadata.clear()
        bitmapTextures.clear()
        overlayTextureCache.clear()

        textureRenderer = null
    }
}
