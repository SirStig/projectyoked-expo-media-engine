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
    private data class ImageTexture(val texId: Int, val width: Int, val height: Int)
    private val bitmapTextures = mutableMapOf<String, ImageTexture>()

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

        // Only require extractors if there are actual video tracks to render.
        // IMAGE-only or AUDIO-only compositions are valid — images are lazy-loaded in renderClip.
        val hasVideoTracks = config.tracks.any { it.type == CompositeVideoComposer.TrackType.VIDEO }
        if (hasVideoTracks && extractors.isEmpty()) {
            throw RuntimeException(
                    "No video extractors initialized! Input URIs: ${config.tracks.flatMap { it.clips }.map { it.uri }}"
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
            // Collect all clips active at this timestamp (could be 2 during a transition overlap)
            val activeClips = track.clips.filter {
                currentTimeSec >= it.startTime && currentTimeSec < (it.startTime + it.duration)
            }

            when (activeClips.size) {
                0 -> { /* nothing to render */ }
                1 -> renderClip(activeClips[0], currentTimeUs)
                else -> {
                    // Transition: outgoing clip ends first, incoming clip starts later
                    val outgoing = activeClips.minByOrNull { it.startTime }!!
                    val incoming = activeClips.maxByOrNull { it.startTime }!!

                    // Honor transitionDuration if specified; otherwise use clip overlap window
                    val specifiedDur = (incoming.transitionDuration.takeIf { it > 0 }
                        ?: outgoing.transitionDuration.takeIf { it > 0 }
                        ?: 0.0)
                    val overlapEnd   = outgoing.startTime + outgoing.duration
                    val overlapStart = if (specifiedDur > 0)
                        (overlapEnd - specifiedDur).coerceAtLeast(incoming.startTime)
                    else
                        incoming.startTime
                    val overlapDuration = overlapEnd - overlapStart
                    val progress = if (overlapDuration > 0) {
                        ((currentTimeSec - overlapStart) / overlapDuration).toFloat().coerceIn(0f, 1f)
                    } else 1f

                    val transitionType = incoming.transition ?: outgoing.transition ?: "crossfade"
                    when (transitionType) {
                        "crossfade" -> {
                            renderClip(outgoing.copy(opacity = (1f - progress) * outgoing.opacity), currentTimeUs)
                            renderClip(incoming.copy(opacity = progress * incoming.opacity), currentTimeUs)
                        }
                        "fade" -> {
                            // Fade to black in first half, fade from black in second half
                            val outOpacity = if (progress < 0.5f) (1f - progress * 2f) else 0f
                            val inOpacity = if (progress > 0.5f) ((progress - 0.5f) * 2f) else 0f
                            if (outOpacity > 0f) renderClip(outgoing.copy(opacity = outOpacity * outgoing.opacity), currentTimeUs)
                            if (inOpacity > 0f) renderClip(incoming.copy(opacity = inOpacity * incoming.opacity), currentTimeUs)
                        }
                        "slide-left", "slide-right", "slide-up", "slide-down" -> {
                            // Translate clips in/out. Uses the x/y position to slide.
                            val dir = when (transitionType) {
                                "slide-left"  -> Pair(-1f, 0f)
                                "slide-right" -> Pair(1f, 0f)
                                "slide-up"    -> Pair(0f, 1f)
                                else          -> Pair(0f, -1f) // slide-down
                            }
                            val inX  = incoming.x + dir.first  * (1f - progress) * 2f
                            val inY  = incoming.y + dir.second * (1f - progress) * 2f
                            val outX = outgoing.x - dir.first  * progress * 2f
                            val outY = outgoing.y - dir.second * progress * 2f
                            renderClip(outgoing.copy(x = outX, y = outY), currentTimeUs)
                            renderClip(incoming.copy(x = inX, y = inY), currentTimeUs)
                        }
                        "zoom-in" -> {
                            renderClip(outgoing.copy(scale = outgoing.scale * (1f + progress * 0.3f), opacity = (1f - progress) * outgoing.opacity), currentTimeUs)
                            renderClip(incoming.copy(scale = incoming.scale * (0.7f + progress * 0.3f), opacity = progress * incoming.opacity), currentTimeUs)
                        }
                        "zoom-out" -> {
                            renderClip(outgoing.copy(scale = outgoing.scale * (1f - progress * 0.3f), opacity = (1f - progress) * outgoing.opacity), currentTimeUs)
                            renderClip(incoming.copy(scale = incoming.scale * (1.3f - progress * 0.3f), opacity = progress * incoming.opacity), currentTimeUs)
                        }
                        else -> {
                            // Unknown transition: fall back to crossfade
                            renderClip(outgoing.copy(opacity = (1f - progress) * outgoing.opacity), currentTimeUs)
                            renderClip(incoming.copy(opacity = progress * incoming.opacity), currentTimeUs)
                        }
                    }
                }
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
            val img = bitmapTextures[uri]
            if (img != null) {
                GLES20.glDeleteTextures(1, intArrayOf(img.texId), 0)
            }
            bitmapTextures.remove(uri)
        }
    }

    private fun renderClip(clip: CompositeVideoComposer.Clip, timelineTimeUs: Long) {
        val uri = clip.uri

        val mime = getMimeType(uri)
        if (mime.startsWith("image/")) {
            // Lazy Load — dimensions are stored alongside the texture ID on first load
            if (!bitmapTextures.containsKey(uri)) {
                Log.d("RenderEngine", "Lazy Loading image: $uri")
                val path = File(Uri.parse(uri).path ?: uri).absolutePath
                val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    val tId = loadTexture(bitmap)
                    bitmapTextures[uri] = ImageTexture(tId, bitmap.width, bitmap.height)
                    bitmap.recycle()
                } else {
                    return // Failed to load
                }
            }

            val img = bitmapTextures[uri]!!
            renderVisual(clip, img.texId, img.width, img.height, isOES = false)
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
            // Compute source-relative time accounting for clipStart offset and playback speed
            val clipRelativeTimeUs = timelineTimeUs - (clip.startTime * 1_000_000).toLong()
            val sourceTimeUs = (clip.clipStart * 1_000_000).toLong() + (clipRelativeTimeUs * clip.speed).toLong()

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
            // NOTE: bufferInfo.size is commonly 0 for Surface-backed decoders even for
            // valid frames (Android spec does not guarantee non-zero size for Surface output).
            // Use the EOS flag to distinguish valid frames from end-of-stream instead.
            var outputAvailable = true
            while (outputAvailable && !frameRendered) {
                val outBufIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outBufIdx >= 0) {
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outBufIdx, !isEos) // render to surface unless EOS
                    if (!isEos) {
                        st.updateTexImage()
                        val stMatrix = FloatArray(16)
                        st.getTransformMatrix(stMatrix)

                        renderVisual(clip, texId, srcW, srcH, isOES = true, stMatrix = stMatrix)
                        frameRendered = true
                        outputAvailable = false
                    } else {
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
        // Cache key: include text content + styling so changes bust the cache
        val cacheKey = "${overlay.uri}|${overlay.textColor}|${overlay.textFontSize}|${overlay.textFontBold}" +
                "|${overlay.textShadowColor}|${overlay.textShadowRadius}|${overlay.textBackgroundColor}" +
                "|${overlay.textStrokeColor}|${overlay.textStrokeWidth}"

        if (!overlayTextureCache.containsKey(cacheKey)) {
            val bitmap = generateOverlayBitmap(overlay)
            if (bitmap != null) {
                val texId = loadTexture(bitmap)
                overlayTextureCache[cacheKey] = texId
                bitmap.recycle()
            }
        }

        val texId = overlayTextureCache[cacheKey]
        if (texId != null) {
            // Map 0..1 → -1..1 (center-origin NDC)
            val glX = (overlay.x * 2) - 1
            val glY = 1 - (overlay.y * 2)
            val viewAspect = config.width.toFloat() / config.height.toFloat()
            textureRenderer!!.drawOverlay(
                    texId,
                    glX,
                    glY,
                    overlay.scale,
                    overlay.scale * viewAspect,
                    overlay.opacity
            )
        }
    }

    // Helpers
    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun parseColor(hex: String, default: Int = android.graphics.Color.WHITE): Int {
        return try { android.graphics.Color.parseColor(hex) } catch (e: Exception) { default }
    }

    private fun generateOverlayBitmap(clip: CompositeVideoComposer.Clip): android.graphics.Bitmap? {
        // Extract text from either clip.text or the encoded URI "text:<content>|COLOR|SIZE"
        val (textParam, colorParam, sizeParam) = if (clip.text != null) {
            Triple(clip.text, clip.textColor, clip.textFontSize)
        } else if (clip.uri.startsWith("text:")) {
            val parts = clip.uri.substring(5).split("|")
            Triple(
                parts.getOrElse(0) { "" },
                parts.getOrElse(1) { "#FFFFFF" },
                parts.getOrElse(2) { "64" }.toFloatOrNull() ?: 64f
            )
        } else {
            Triple(clip.uri, "#FFFFFF", clip.textFontSize)
        }

        if (textParam.isBlank()) return null

        // High-resolution canvas for crisp text at any display size
        val bitmapSize = 2048
        val bitmap = android.graphics.Bitmap.createBitmap(bitmapSize, bitmapSize, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val scaledSize = sizeParam * 4f // scale to canvas resolution
        val cx = bitmapSize / 2f

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = parseColor(colorParam)
            textSize = scaledSize
            textAlign = android.graphics.Paint.Align.CENTER
            isFilterBitmap = true
            typeface = if (clip.textFontBold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }

        // Shadow
        val shadowColor = clip.textShadowColor
        if (shadowColor != null && clip.textShadowRadius > 0) {
            paint.setShadowLayer(
                clip.textShadowRadius * 4f,
                clip.textShadowOffsetX * 4f,
                clip.textShadowOffsetY * 4f,
                parseColor(shadowColor, android.graphics.Color.TRANSPARENT)
            )
        }

        val textY = (bitmapSize / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        val textBounds = android.graphics.Rect()
        paint.getTextBounds(textParam, 0, textParam.length, textBounds)

        // Background
        val bgColor = clip.textBackgroundColor
        if (bgColor != null) {
            val pad = clip.textBackgroundPadding * 4f
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = parseColor(bgColor, android.graphics.Color.TRANSPARENT)
                style = android.graphics.Paint.Style.FILL
            }
            val bgRect = android.graphics.RectF(
                cx - textBounds.width() / 2f - pad,
                textY + textBounds.top - pad,
                cx + textBounds.width() / 2f + pad,
                textY + textBounds.bottom + pad
            )
            canvas.drawRoundRect(bgRect, pad / 2f, pad / 2f, bgPaint)
        }

        // Stroke (draw before fill so stroke is underneath)
        val strokeColor = clip.textStrokeColor
        if (strokeColor != null && clip.textStrokeWidth > 0) {
            val strokePaint = android.graphics.Paint(paint).apply {
                color = parseColor(strokeColor)
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = clip.textStrokeWidth * 4f
                clearShadowLayer()
            }
            canvas.drawText(textParam, cx, textY, strokePaint)
        }

        // Fill text
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawText(textParam, cx, textY, paint)

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
            "grayscale"  -> 1
            "sepia"      -> 2
            "vignette"   -> 3
            "invert"     -> 4
            "brightness" -> 5
            "contrast"   -> 6
            "saturation" -> 7
            "warm"       -> 8
            "cool"       -> 9
            else         -> 0
        }

        if (isOES) {
            textureRenderer!!.draw(texId, stMatrix, mvpMatrix, filterType, clip.filterIntensity, clip.opacity)
        } else {
            textureRenderer!!.draw2D(texId, mvpMatrix, filterType, clip.opacity)
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
