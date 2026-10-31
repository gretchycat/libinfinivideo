package io.github.gretchycat.infinivideo

import android.media.MediaPlayer
import android.widget.VideoView
import io.github.gretchycat.infinistream.*
import java.io.File

/** Existing VideoView/MediaPlayer backend. Host retains its surface, listeners and staged-file lease. */
object AndroidVideoBackend {
    fun bind(view: VideoView, file: File, originalSource: String? = null): StreamMetadata {
        val input = VideoMetadata.fileInput(FileSourceResource(file).metadata, originalSource)
        view.setVideoPath(file.absolutePath)
        return input
    }
    fun negotiated(input: StreamMetadata, player: MediaPlayer): StreamMetadata =
        VideoMetadata.presented(input, player.videoWidth, player.videoHeight,
            player.duration.takeIf { it >= 0 }?.toLong())
}
