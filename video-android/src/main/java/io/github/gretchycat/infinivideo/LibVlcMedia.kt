package io.github.gretchycat.infinivideo

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import java.io.File

/** Native implementation details stay in this Android module. File ownership stays with the caller. */
internal object LibVlcMedia {
    fun engine(context: Context): LibVLC = LibVLC(context, arrayListOf("--no-video-title-show"))
    fun open(engine: LibVLC, file: File): Media {
        require(file.isFile && file.canRead()) { "Video input is not a readable file" }
        return open(engine, Uri.fromFile(file))
    }
    fun open(engine: LibVLC, uri: Uri): Media = Media(engine, uri).apply {
        setHWDecoderEnabled(true, false)
        // Do not discover sibling resources outside the explicitly supplied source.
        addOption(":no-sub-autodetect-file")
        if (uri.scheme == "http") addOption(":network-caching=300")
        if (uri.scheme == "file") addOption(":file-caching=300")
    }
}
