package io.github.gretchycat.infinivideo

import io.github.gretchycat.infinistream.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatContractTest {
    @Test fun videoFormatModulesExposeTheSharedIdentityThumbnailAndMetadataContract() {
        val handlers = VideoCapabilityProvider().handlers()
        val playback = handlers.single { it.descriptor.id == "video.libvlc" }
        val identity = handlers.single { it.descriptor.id == "video.identity" }
        val resource = CapabilityResource(StreamMetadata(
            name = "movie.mp4",
            content = ContentType(mimeType = "video/mp4")
        ))

        assertEquals(setOf("video/*", "application/vnd.rn-realmedia", "application/vnd.rn-realmedia-vbr"), playback.mimeTypes())
        assertTrue(playback.extensions().containsAll(setOf("mp4", "mkv", "webm")))
        assertEquals(ViewerTarget.INFINIMEDIA, playback.viewerTarget())
        assertEquals("video/mp4", playback.normalize(CapabilityProbeContext(resource))?.mimeType)
        assertTrue(playback.supportsThumbnail())
        assertTrue(playback.metadataFields().containsAll(setOf("duration", "width", "height", "artworkAvailable")))
        assertNull(playback.thumbnail(ThumbnailRequest(resource, 256)))

        assertEquals(ViewerTarget.INFINIMEDIA, identity.viewerTarget())
        assertTrue(identity.magicSignatures().isNotEmpty())
    }
}
