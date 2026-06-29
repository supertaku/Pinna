package com.pinna.app.library

/**
 * Validates and classifies direct audio URLs for the license-clean URL importer (podcasts, Internet
 * Archive files, Creative-Commons hosts, the user's own cloud). HTTPS only — local cleartext is for the
 * room transport, not arbitrary remote downloads. Pure and unit tested.
 */
object AudioUrlValidator {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mp4", "webm")
    private val contentTypeToExtension = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/webm" to "webm",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
    )

    fun isHttpsUrl(raw: String): Boolean {
        val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return false
        return uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank()
    }

    fun hasAudioExtension(raw: String): Boolean {
        val path = runCatching { java.net.URI(raw.trim()).path }.getOrNull().orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    fun extensionForContentType(contentType: String?): String? {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return contentTypeToExtension[normalized]
    }

    fun looksLikeAudio(contentType: String?, url: String): Boolean {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (normalized != null && normalized.startsWith("audio/")) return true
        if (hasAudioExtension(url)) return true
        return false
    }

    /** Best-effort file extension for a downloaded resource. */
    fun resolveExtension(url: String, contentType: String?): String {
        extensionForContentType(contentType)?.let { return it }
        val path = runCatching { java.net.URI(url.trim()).path }.getOrNull().orEmpty()
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in audioExtensions) ext else "audio"
    }
}
