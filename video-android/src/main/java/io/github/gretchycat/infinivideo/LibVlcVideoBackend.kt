package io.github.gretchycat.infinivideo

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.gretchycat.infinistream.FileSourceResource
import io.github.gretchycat.infinistream.StreamMetadata
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Main-thread Android presentation adapter. No libVLC types appear in VideoPlayback. */
class LibVlcVideoBackend(context: Context) : VideoPlayback {
    private val layout = VLCVideoLayout(context)
    val view: View get() = layout
    private val engine = LibVlcMedia.engine(context.applicationContext)
    private var player: MediaPlayer? = null
    private var closed = false
    private var ready = false
    private var ended = false
    private var speed = 1f
    private var aspect = VideoScaleMode.FIT
    private var wantsPlayback = false
    private var resumeAfterFocus = false
    private var hasFocus = false
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener({ change ->
            if (!closed) when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    player?.volume = 100
                    if (resumeAfterFocus && wantsPlayback) player?.play()
                    resumeAfterFocus = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.volume = 20
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    hasFocus = false
                    resumeAfterFocus = wantsPlayback
                    player?.pause()
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    resumeAfterFocus = false
                    wantsPlayback = false
                    player?.pause()
                }
            }
        }, Handler(Looper.getMainLooper())).build()

    override var listener: VideoPlayback.Listener? = null
    override var inputMetadata = StreamMetadata()
        private set
    override var outputMetadata = StreamMetadata()
        private set
    override val positionMs get() = player?.time?.coerceAtLeast(0) ?: 0L
    override val durationMs get() = player?.length?.takeIf { it > 0 }
    override val isPlayRequested get() = player != null && wantsPlayback
    override val isPlaying get() = player?.isPlaying == true
    override val isSeekable get() = player?.isSeekable == true

    /** Borrow a local view of a common source; stop/close before releasing its staging lease. */
    fun open(file: File, sourceMetadata: StreamMetadata = FileSourceResource(file).metadata, autoPlay: Boolean = true) {
        check(!closed) { "Video backend is closed" }
        stop()
        inputMetadata = VideoMetadata.stagedInput(FileSourceResource(file).metadata, sourceMetadata)
        ready = false
        ended = false
        outputMetadata = VideoMetadata.libVlcPresented(inputMetadata, null, null, null, false)
        val current = MediaPlayer(engine)
        player = current
        try {
            current.attachViews(layout, null, true, false)
            applyAspect(current)
            current.setEventListener { event ->
                if (!closed && player === current) when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        current.rate = speed
                        updateMetadata(current)
                        if (!ready) { ready = true; listener?.onReady(outputMetadata) }
                        listener?.onPlayingChanged(true)
                    }
                    MediaPlayer.Event.Paused -> listener?.onPlayingChanged(false)
                    MediaPlayer.Event.Buffering -> listener?.onBuffering(event.buffering)
                    MediaPlayer.Event.LengthChanged, MediaPlayer.Event.Vout,
                    MediaPlayer.Event.ESAdded, MediaPlayer.Event.SeekableChanged -> updateMetadata(current)
                    MediaPlayer.Event.EndReached -> if (!ended) {
                        ended = true
                        wantsPlayback = false
                        listener?.onPlayingChanged(false)
                        listener?.onEnded()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        wantsPlayback = false
                        listener?.onError("libVLC could not play this video")
                    }
                }
            }
            val media = LibVlcMedia.open(engine, file)
            try { current.media = media } finally { media.release() }
            if (autoPlay) play()
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    private fun updateMetadata(current: MediaPlayer) {
        val track = current.currentVideoTrack
        val next = VideoMetadata.libVlcPresented(inputMetadata, track?.width, track?.height,
            current.length.takeIf { it > 0 }, current.isSeekable)
        if (next != outputMetadata) {
            outputMetadata = next
            listener?.onMetadataChanged(next)
        }
    }

    override fun play() {
        check(!closed) { "Video backend is closed" }
        val current = player ?: return
        if (ended) {
            ended = false
            current.stop()
        }
        wantsPlayback = true
        resumeAfterFocus = false
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) current.play() else listener?.onError("Audio focus is unavailable")
    }

    override fun pause() {
        wantsPlayback = false
        resumeAfterFocus = false
        player?.pause()
    }

    override fun seekTo(positionMs: Long) {
        player?.let { if (it.isSeekable) it.setTime(VideoControls.seekPosition(positionMs, durationMs), false) }
    }

    override fun setSpeed(speed: Float) {
        this.speed = VideoControls.checkedSpeed(speed)
        player?.rate = speed
    }

    override fun setAspect(mode: VideoScaleMode) {
        aspect = mode
        player?.let(::applyAspect)
    }

    private fun applyAspect(current: MediaPlayer) {
        current.setVideoScale(when (aspect) {
            VideoScaleMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            VideoScaleMode.FILL -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            VideoScaleMode.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            VideoScaleMode.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
        })
    }

    override fun stop() {
        val old = player
        player = null // Reject already queued callbacks before the native stop/release.
        wantsPlayback = false
        resumeAfterFocus = false
        try {
            if (old != null) {
                old.setEventListener(null)
                try { old.stop() } finally { try { old.detachViews() } finally { old.release() } }
            }
        } finally {
            audio.abandonAudioFocusRequest(focus)
            hasFocus = false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        listener = null
        try { stop() } finally { engine.release() }
    }
}
