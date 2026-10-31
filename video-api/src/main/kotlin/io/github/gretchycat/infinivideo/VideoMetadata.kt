package io.github.gretchycat.infinivideo

import io.github.gretchycat.infinistream.*
import java.util.Locale

object VideoMetadata {
    val descriptor = ProcessorDescriptor("video.android.platform",
        listOf(PortDescriptor("encoded", setOf(DataKind.BYTES))),
        listOf(PortDescriptor("frames", setOf(DataKind.VIDEO_FRAME))), options = setOf("speed"))
    /** Host supplies logical origin separately from the borrowed/staged local file view. */
    fun fileInput(input: StreamMetadata, originalSource: String?): StreamMetadata =
        if (originalSource == null) input else input.copy(
            provenance = input.provenance.copy(source = originalSource,
                transformations = input.provenance.transformations + "file.adapt"),
            attributes = input.attributes + input.provenance.source?.let { mapOf("localFile" to it) }.orEmpty())

    /** Negotiated presentation metadata; dimensions/duration remain unknown until the backend reports them. */
    fun presented(input: StreamMetadata, width: Int?, height: Int?, durationMs: Long?): StreamMetadata =
        StreamMetadata(ContentType(DataKind.VIDEO_FRAME, format = "platform-surface"), name = input.name,
            finiteness = Finiteness.UNKNOWN, provenance = input.provenance.copy(transformations = input.provenance.transformations + "video.platform.decode"),
            attributes = buildMap {
                width?.takeIf { it > 0 }?.let { put("width", it.toString()) }
                height?.takeIf { it > 0 }?.let { put("height", it.toString()) }
                durationMs?.takeIf { it >= 0 }?.let { put("durationMs", it.toString()) }
                put("storage", "platform-surface")
            })
    fun formatTime(ms: Int): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600; val mins = (totalSecs % 3600) / 60; val secs = totalSecs % 60
        return if (hours > 0) String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        else String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
