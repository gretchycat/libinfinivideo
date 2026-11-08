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
    override fun handlers():List<CapabilityHandler> = listOf(VideoIdentityHandler(),LibVlcPlaybackHandler())
}

private class VideoIdentityHandler : CapabilityHandler {
    override val descriptor=HandlerDescriptor("video.identity","video","Video container identification",
        listOf(FormatSupport(setOf("video/*","application/vnd.rn-realmedia","application/vnd.rn-realmedia-vbr"),VideoFormats.extensions,
            magic=listOf(MagicSignature("66747970",4,description="ISO Base Media File Type box"),
                MagicSignature("1a45dfa3",description="EBML document header"),MagicSignature("52494646",description="RIFF container"),
                MagicSignature("464c5601",description="Flash Video header"),MagicSignature("2e524d46",description="RealMedia file header"),
                MagicSignature("000001b3",description="MPEG video sequence header"),MagicSignature("47",description="MPEG transport packet sync")))),
        setOf(Operations.IDENTIFY),metadata=setOf("container"),limitations=listOf("Validates container headers only; codec and stream support are decided at playback"))

    override fun probe(context:CapabilityProbeContext):HandlerProbe {
        if(context.resource.source==null)return super.probe(context)
        val b=context.prefix();val length=context.resource.metadata.length ?: b.size.toLong()
        fun u8(i:Int)=b[i].toInt() and 255
        fun ascii(i:Int,n:Int)=if(i>=0&&i+n<=b.size)String(b,i,n,Charsets.ISO_8859_1)else ""
        fun be32(i:Int)=(u8(i).toLong() shl 24)or(u8(i+1).toLong() shl 16)or(u8(i+2).toLong() shl 8)or u8(i+3).toLong()
        fun le32(i:Int)=u8(i).toLong()or(u8(i+1).toLong() shl 8)or(u8(i+2).toLong() shl 16)or(u8(i+3).toLong() shl 24)
        fun id(mime:String,title:String):HandlerProbe=HandlerProbe(MatchConfidence.VERIFIED,setOf(Operations.IDENTIFY),
            "Container header validated",mapOf("mimeType" to mime,"format" to title))
        if(b.size>=12&&ascii(0,4)=="RIFF"&&ascii(8,4)=="AVI "&&le32(4).let { it>=4&&it+8<=length })
            return id("video/x-msvideo","AVI video")
        if(b.size>=13&&ascii(0,3)=="FLV"&&u8(3)==1&&(u8(4)and 0xf8)==0) {
            val dataOffset=be32(5)
            if(dataOffset>=9&&dataOffset+4<=length&&dataOffset+4<=b.size&&be32(dataOffset.toInt())==0L)
                return id("video/x-flv","Flash Video")
        }
        if(b.size>=16&&ascii(4,4)=="ftyp") {
            val size32=be32(0)
            val extended=size32==1L
            val boxSize=when {
                extended && b.size>=24 -> (be32(8) shl 32) or be32(12)
                extended -> -1L
                size32==0L -> length
                else -> size32
            }
            val brandAt=if(extended)16 else 8
            val brand=ascii(brandAt,4)
            if(boxSize>=brandAt+8L&&boxSize<=length&&brand.isNotEmpty()) {
                val mime=when {
                    brand=="qt  " -> "video/quicktime"
                    brand.startsWith("3gp")||brand.startsWith("3gr")||brand.startsWith("3gs") -> "video/3gpp"
                    brand.startsWith("3g2") -> "video/3gpp2"
                    brand in setOf("avif","avis") -> "image/avif"
                    brand in setOf("heic","heix","hevc","hevx","mif1","msf1") -> "image/heif"
                    brand in setOf("M4A ","M4B ") -> "audio/mp4"
                    else -> "video/mp4"
                }
                val stated=context.resource.metadata.content.mimeType?.substringBefore(';')?.lowercase()
                val reported=when {
                    mime.startsWith("image/") -> mime
                    stated?.startsWith("audio/")==true || stated?.startsWith("video/")==true -> stated
                    mime.startsWith("audio/") -> mime
                    else -> mime
                }
                return id(reported,"ISO Base Media container ($brand)")
            }
        }
        if(b.size>=16&&ascii(0,4)==".RMF"&&be32(4)>=18&&be32(4)<=length)
            return id("application/vnd.rn-realmedia","RealMedia container")
        if(b.size>=24&&b.copyOfRange(0,16).contentEquals(byteArrayOf(0x30,0x26,0xb2.toByte(),0x75,0x8e.toByte(),0x66,0xcf.toByte(),0x11,
                0xa6.toByte(),0xd9.toByte(),0,0xaa.toByte(),0,0x62,0xce.toByte(),0x6c))) {
            if(be32(16)<24||be32(16)>length)return HandlerProbe(MatchConfidence.NONE,emptySet())
            val stated=context.resource.metadata.content.mimeType
            return id(stated?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }?:"application/vnd.ms-asf","ASF media container")
        }
        if(b.size>=4&&u8(0)==0x1a&&u8(1)==0x45&&u8(2)==0xdf&&u8(3)==0xa3) {
            val docType=ebmlDocType(b) ?: return HandlerProbe(MatchConfidence.NONE,emptySet())
            val stated=context.resource.metadata.content.mimeType
            return when(docType) {
                "webm" -> id(stated?.takeIf { it.startsWith("audio/") }?:"video/webm","WebM container")
                "matroska" -> id(stated?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }?:"video/x-matroska","Matroska container")
                else -> HandlerProbe(MatchConfidence.NONE,emptySet())
            }
        }
        if(b.size>=7&&be32(0)==0x000001b3L) {
            val width=(u8(4)shl 4)or(u8(5) ushr 4);val height=((u8(5)and 15)shl 8)or u8(6)
            if(width>0&&height>0)return id("video/mpeg","MPEG video elementary stream")
        }
        for ((packet, offset) in listOf(188 to 0, 192 to 4, 204 to 0)) {
            if(b.size>=offset+packet*2+4&&u8(offset)==0x47&&u8(offset+packet)==0x47&&u8(offset+packet*2)==0x47 &&
                (u8(offset+3)and 0x30)!=0)
                return id("video/mp2t","MPEG transport stream")
        }
        return HandlerProbe(MatchConfidence.NONE,emptySet())
    }

    /** Parse the EBML header's DocType element instead of matching arbitrary payload text. */
    private fun ebmlDocType(bytes: ByteArray): String? {
        if (bytes.size < 8) return null
        fun vint(at: Int, forId: Boolean): Pair<Long, Int>? {
            if (at >= bytes.size) return null
            val first = bytes[at].toInt() and 255
            var mask = 0x80
            var width = 1
            while (width <= 8 && (first and mask) == 0) { mask = mask ushr 1; width++ }
            if (width > 8 || at + width > bytes.size) return null
            var value = if (forId) first.toLong() else (first and (mask - 1)).toLong()
            for (index in 1 until width) value = (value shl 8) or (bytes[at + index].toLong() and 255)
            return value to width
        }
        val headerSize = vint(4, false) ?: return null
        val endLong = 4L + headerSize.second + headerSize.first
        if (headerSize.first < 0 || endLong > bytes.size || endLong > 65536) return null
        val end = endLong.toInt()
        var at = 4 + headerSize.second
        while (at < end) {
            val id = vint(at, true) ?: return null
            at += id.second
            val size = vint(at, false) ?: return null
            at += size.second
            if (size.first < 0 || size.first > end.toLong() - at) return null
            if (id.first == 0x4282L) {
                if (size.first !in 1L..32L) return null
                return String(bytes, at, size.first.toInt(), Charsets.US_ASCII).lowercase()
            }
            at += size.first.toInt()
        }
        return null
    }
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
