package com.pinna.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUrlValidatorTest {
    @Test
    fun acceptsHttpsUrls() {
        assertTrue(AudioUrlValidator.isHttpsUrl("https://archive.org/download/x/song.mp3"))
        assertTrue(AudioUrlValidator.isHttpsUrl("https://example.com/audio"))
    }

    @Test
    fun rejectsNonHttpsAndMalformed() {
        assertFalse(AudioUrlValidator.isHttpsUrl("http://example.com/song.mp3")) // cleartext
        assertFalse(AudioUrlValidator.isHttpsUrl("ftp://example.com/song.mp3"))
        assertFalse(AudioUrlValidator.isHttpsUrl("not a url"))
        assertFalse(AudioUrlValidator.isHttpsUrl(""))
    }

    @Test
    fun detectsAudioExtensions() {
        listOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav").forEach { ext ->
            assertTrue("ext $ext", AudioUrlValidator.hasAudioExtension("https://x.com/track.$ext"))
        }
        assertTrue(AudioUrlValidator.hasAudioExtension("https://x.com/a/b/track.mp3?download=1"))
        assertFalse(AudioUrlValidator.hasAudioExtension("https://x.com/page.html"))
        assertFalse(AudioUrlValidator.hasAudioExtension("https://x.com/novideo"))
    }

    @Test
    fun mapsContentTypeToExtension() {
        assertEquals("mp3", AudioUrlValidator.extensionForContentType("audio/mpeg"))
        assertEquals("m4a", AudioUrlValidator.extensionForContentType("audio/mp4"))
        assertEquals("ogg", AudioUrlValidator.extensionForContentType("audio/ogg"))
        assertEquals("opus", AudioUrlValidator.extensionForContentType("audio/opus"))
        assertEquals(null, AudioUrlValidator.extensionForContentType("text/html"))
    }

    @Test
    fun classifiesAudioResponses() {
        assertTrue(AudioUrlValidator.looksLikeAudio(contentType = "audio/mpeg", url = "https://x/n"))
        assertTrue(AudioUrlValidator.looksLikeAudio(contentType = "application/octet-stream", url = "https://x/n.mp3"))
        assertTrue(AudioUrlValidator.looksLikeAudio(contentType = null, url = "https://x/n.flac"))
        assertFalse(AudioUrlValidator.looksLikeAudio(contentType = "text/html", url = "https://x/page"))
    }
}
