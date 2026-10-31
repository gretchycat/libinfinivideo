package io.github.gretchycat.infinivideo

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class VideoMetadataTest {
    @Test fun presentationMetadataDescribesActualSurfaceWithoutInventedByteAccess() {
        val meta = VideoMetadata.presented(StreamMetadata(name = "movie"), 1920, 1080, 1234)
        assertEquals(DataKind.VIDEO_FRAME, meta.content.kind); assertEquals("1920", meta.attributes["width"])
        assertTrue(meta.capabilities.isEmpty()); assertNull(meta.length)
    }
    @Test fun unknownFieldsStayUnknown() {
        val meta = VideoMetadata.presented(StreamMetadata(), 0, -1, null)
        assertFalse("width" in meta.attributes); assertFalse("durationMs" in meta.attributes)
    }
    @Test fun timeFormattingPreservesExistingControls() {
        assertEquals("00:00", VideoMetadata.formatTime(-1)); assertEquals("01:01", VideoMetadata.formatTime(61000))
        assertEquals("1:01:01", VideoMetadata.formatTime(3661000))
    }
    @Test fun originalResourceSurvivesFileAdaptationAndDecodeMetadata() {
        val input = VideoMetadata.fileInput(StreamMetadata(provenance = Provenance(source = "file:/cache/video")), "sftp://connection/video")
        val output = VideoMetadata.presented(input, 1920, 1080, 1000)
        assertEquals("sftp://connection/video", output.provenance.source)
        assertEquals("file:/cache/video", input.attributes["localFile"])
        assertEquals(listOf("file.adapt", "video.platform.decode"), output.provenance.transformations)
    }

}
