package com.projectyoked.mediaengine

import android.util.Log
import com.coremedia.iso.boxes.Container
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.googlecode.mp4parser.authoring.tracks.AppendTrack
import java.io.FileOutputStream
import java.util.LinkedList

object VideoStitcher {
    private const val TAG = "VideoStitcher"

    fun stitch(videoPaths: List<String>, outputUri: String): String {
        Log.d(TAG, "Stitching ${videoPaths.size} videos to $outputUri")

        if (videoPaths.isEmpty()) throw IllegalArgumentException("No videos to stitch")
        if (videoPaths.size == 1) {
            // Optimization: If only 1 video, just copy/move it?
            // Better to let the caller handle that or do a File copy.
            // For now we run it through the stitcher to ensure consistent container structure.
        }

        val movies = ArrayList<Movie>()
        videoPaths.forEach { path ->
            val cleanPath = if (path.startsWith("file://")) path.substring(7) else path
            movies.add(MovieCreator.build(cleanPath))
        }

        val videoTracks = LinkedList<Track>()
        val audioTracks = LinkedList<Track>()

        // Collect tracks
        for (m in movies) {
            for (t in m.tracks) {
                if (t.handler == "vide") {
                    videoTracks.add(t)
                }
                if (t.handler == "soun") {
                    audioTracks.add(t)
                }
            }
        }

        val result = Movie()
        if (videoTracks.isNotEmpty()) {
            result.addTrack(AppendTrack(*videoTracks.toTypedArray()))
        }
        if (audioTracks.isNotEmpty()) {
            result.addTrack(AppendTrack(*audioTracks.toTypedArray()))
        }

        val container: Container = DefaultMp4Builder().build(result)

        val cleanOutputPath =
                if (outputUri.startsWith("file://")) outputUri.substring(7) else outputUri
        val fos = FileOutputStream(cleanOutputPath)
        val fc = fos.channel
        container.writeContainer(fc)
        fc.close()
        fos.close()

        return outputUri
    }
}
