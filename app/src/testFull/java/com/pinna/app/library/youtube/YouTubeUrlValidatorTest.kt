package com.pinna.app.library.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlValidatorTest {
    @Test
    fun extractsVideoIdFromCommonForms() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=30s",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=abc",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "  https://www.youtube.com/watch?v=$id  ",
        ).forEach { url ->
            assertEquals("failed for $url", id, YouTubeUrlValidator.extractVideoId(url))
        }
    }

    @Test
    fun normalizesToCanonicalWatchUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            YouTubeUrlValidator.normalize("https://youtu.be/dQw4w9WgXcQ?si=x"),
        )
    }

    @Test
    fun rejectsNonYouTubeAndMalformed() {
        listOf(
            "https://vimeo.com/12345",
            "https://example.com/watch?v=dQw4w9WgXcQ",
            "not a url",
            "",
            "https://www.youtube.com/watch?v=short", // too short
            "https://www.youtube.com/feed/subscriptions",
        ).forEach { url ->
            assertNull("expected null for $url", YouTubeUrlValidator.extractVideoId(url))
            assertFalse(YouTubeUrlValidator.isYouTubeUrl(url))
        }
    }

    @Test
    fun isYouTubeUrlAcceptsValid() {
        assertTrue(YouTubeUrlValidator.isYouTubeUrl("https://youtu.be/dQw4w9WgXcQ"))
    }
}
