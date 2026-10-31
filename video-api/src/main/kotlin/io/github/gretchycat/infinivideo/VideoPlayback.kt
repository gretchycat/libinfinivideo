package io.github.gretchycat.infinivideo

import io.github.gretchycat.infinistream.StreamMetadata

/** Backend-neutral playback controls. Platform surfaces belong to the platform adapter. */
interface VideoPlayback : AutoCloseable {
    val inputMetadata: StreamMetadata
    val outputMetadata: StreamMetadata
    val positionMs: Long
    val durationMs: Long?
    val isPlayRequested: Boolean
    val isPlaying: Boolean
    val isSeekable: Boolean
    var listener: Listener?
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun setAspect(mode: VideoScaleMode)
    /** Release the current decoder before its borrowed source lease is closed. */
    fun stop()

    interface Listener {
        fun onReady(metadata: StreamMetadata) {}
        fun onMetadataChanged(metadata: StreamMetadata) {}
        fun onBuffering(percent: Float) {}
        fun onPlayingChanged(playing: Boolean) {}
        fun onEnded() {}
        fun onError(message: String) {}
    }
}

enum class VideoScaleMode { FIT, FILL, RATIO_16_9, RATIO_4_3 }

object VideoControls {
    fun checkedSpeed(speed: Float): Float {
        require(speed.isFinite() && speed in .5f..2f) { "Playback speed must be between 0.5 and 2" }
        return speed
    }
    fun seekPosition(positionMs: Long, durationMs: Long?): Long =
        positionMs.coerceAtLeast(0).let { if (durationMs != null && durationMs >= 0) it.coerceAtMost(durationMs) else it }
}
