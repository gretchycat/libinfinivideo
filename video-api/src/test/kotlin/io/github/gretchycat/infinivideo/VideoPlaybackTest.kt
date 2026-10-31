package io.github.gretchycat.infinivideo

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackTest {
    @Test fun libVlcDescriptorExposesEncodedInputAndSurfaceOutput() {
        val d = VideoMetadata.libVlcDescriptor
        assertEquals("video.android.libvlc", d.id)
        assertTrue(d.options.containsAll(setOf("speed", "aspect")))
    }
    @Test fun stagedInputUsesActualFileCapabilitiesAndLength() {
        val file = StreamMetadata(length = 123, capabilities = setOf(Capability.READ, Capability.POSITIONAL_READ),
            provenance = Provenance(source = "file:/cache/video"))
        val source = StreamMetadata(name = "movie.mkv", length = 999,
            content = ContentType(mimeType = "video/x-matroska"),
            provenance = Provenance(source = "archive://movie", transformations = listOf("zip.entry")),
            warnings = listOf("source warning"))
        val actual = VideoMetadata.stagedInput(file, source)
        assertEquals(123L, actual.length)
        assertEquals(file.capabilities, actual.capabilities)
        assertEquals(source.content, actual.content)
        assertEquals(source.name, actual.name)
        assertEquals("archive://movie", actual.provenance.source)
        assertEquals(listOf("zip.entry", "file.adapt"), actual.provenance.transformations)
        assertEquals("file:/cache/video", actual.attributes["localFile"])
        assertEquals(source.warnings, actual.warnings)
    }
    @Test fun surfaceMetadataDoesNotClaimEncodedByteCapabilities() {
        val input = StreamMetadata(length = 999, capabilities = setOf(Capability.READ), warnings = listOf("retained warning"),
            provenance = Provenance(source = "sftp://movie"))
        val output = VideoMetadata.libVlcPresented(input, 320, 180, 900_000, true)
        assertEquals(DataKind.VIDEO_FRAME, output.content.kind)
        assertEquals(input.warnings, output.warnings)
        assertEquals("libvlc", output.attributes["backend"])
        assertEquals("true", output.attributes["seekable"])
        assertEquals("320", output.attributes["width"])
        assertEquals("900000", output.attributes["durationMs"])
        assertEquals("sftp://movie", output.provenance.source)
        assertEquals(listOf("video.libvlc.decode"), output.provenance.transformations)
        assertNull(output.length); assertTrue(output.capabilities.isEmpty())
    }
    @Test fun unknownDurationAndDimensionsStayUnknown() {
        val output = VideoMetadata.libVlcPresented(StreamMetadata(), 0, -1, null, false)
        assertFalse("width" in output.attributes); assertFalse("height" in output.attributes)
        assertFalse("durationMs" in output.attributes)
        assertEquals("false", output.attributes["seekable"])
    }
    @Test fun speedPreservesAllPickerValues() {
        for (speed in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) assertEquals(speed, VideoControls.checkedSpeed(speed))
    }
    @Test fun invalidSpeedsAreRejected() {
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -.5f, 3f)) {
            try { VideoControls.checkedSpeed(speed); fail("accepted $speed") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun seeksUseLongOffsetsAndKnownBounds() {
        assertEquals(0L, VideoControls.seekPosition(-1, 100))
        assertEquals(100L, VideoControls.seekPosition(999, 100))
        assertEquals(4_000_000_000L, VideoControls.seekPosition(4_000_000_000L, null))
        assertEquals(0L, VideoControls.seekPosition(1, 0))
    }
    @Test fun longDurationLabelsDoNotOverflowInt() {
        assertEquals("1111:06:40", VideoMetadata.formatTime(4_000_000_000L))
        assertEquals("00:00", VideoMetadata.formatTime(-1L))
    }
}
