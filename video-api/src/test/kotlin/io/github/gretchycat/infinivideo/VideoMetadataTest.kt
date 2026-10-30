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
}
