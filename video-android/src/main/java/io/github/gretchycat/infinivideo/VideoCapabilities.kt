package io.github.gretchycat.infinivideo

import android.content.Context
import android.view.View
import io.github.gretchycat.infinistream.*
import java.io.File

interface VideoSurfacePlayback : VideoPlayback {
    val view: View
    val streamBytesRead: Long
    val replayedBytes: Long
    fun openFile(file: File, sourceMetadata: StreamMetadata = FileSourceResource(file).metadata, autoPlay: Boolean = true)
    fun openResource(resource: SourceResource, autoPlay: Boolean = true)
}
interface VideoCapabilityHandler : CapabilityHandler {
    override fun describe()=descriptor.copy(attributes=descriptor.attributes+("presentation" to "video"))
    fun create(context: Context): VideoSurfacePlayback
}
object VideoCapabilities {
    val registry get() = RuntimeCapabilities.library("video")
    fun create(context:Context,metadata:StreamMetadata):VideoSurfacePlayback {
        val candidates=registry.probe(CapabilityResource(metadata),Operations.PLAY)
        val handler=candidates.firstNotNullOfOrNull { it.handler.service(VideoCapabilityHandler::class.java) }
            ?:registry.query(Operations.PLAY).firstNotNullOfOrNull { it.handler.service(VideoCapabilityHandler::class.java) }
            ?:throw java.io.IOException("No video playback backend is available")
        return handler.create(context)
    }
}
class VideoCapabilityProvider : CapabilityProvider {
    override val library="video"
    override fun handlers():List<CapabilityHandler> = listOf(LibVlcPlaybackHandler())
}
private class LibVlcPlaybackHandler : VideoCapabilityHandler {
    override val descriptor=HandlerDescriptor("video.libvlc","video","libVLC video",
        listOf(FormatSupport(setOf("video/*","application/vnd.rn-realmedia","application/vnd.rn-realmedia-vbr"),VideoFormats.extensions)),
        setOf(Operations.PLAY,Operations.VIEW),metadata=setOf("duration","width","height","seekable"),
        limitations=listOf("libVLC native codecs and per-input demux support required; seeking depends on actual source/session"),attributes=mapOf("presentation" to "video"))
    override fun availability():HandlerAvailability {
        Class.forName("org.videolan.libvlc.LibVLC",true,javaClass.classLoader)
        return HandlerAvailability(AvailabilityState.CONDITIONAL,"Backend present; native initialization and input validation occur on open")
    }
    override fun create(context:Context):VideoSurfacePlayback=LibVlcVideoBackend(context)
}
object VideoFormats {
    val extensions=setOf("mp4","mkv","webm","avi","mov","3gp","3gpp","3g2","ts","m2ts","ogv","wmv","flv","m4v","rm","rmvb")
}
