package com.pinna.app.library.youtube

/**
 * Recognizes and normalizes YouTube watch URLs without any network calls, so the import flow can
 * validate a pasted link before invoking the (network-bound) extractor. Pure and unit tested.
 */
object YouTubeUrlValidator {
    private val ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val youtubeHosts = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
    )
    private val pathIdPrefixes = listOf("/shorts/", "/embed/", "/live/", "/v/")

    fun extractVideoId(raw: String): String? {
        val trimmed = raw.trim()
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty()

        return when {
            host == "youtu.be" -> path.trim('/').takeWhile { it != '/' }.asValidId()
            host in youtubeHosts && path == "/watch" -> queryParam(uri.query, "v")?.asValidId()
            host in youtubeHosts -> pathIdPrefixes.firstNotNullOfOrNull { prefix ->
                if (path.startsWith(prefix)) path.removePrefix(prefix).trim('/').takeWhile { it != '/' }.asValidId() else null
            }
            else -> null
        }
    }

    fun isYouTubeUrl(raw: String): Boolean = extractVideoId(raw) != null

    fun normalize(raw: String): String? = extractVideoId(raw)?.let { "https://www.youtube.com/watch?v=$it" }

    private fun String.asValidId(): String? = takeIf { ID_REGEX.matches(it) }

    private fun queryParam(query: String?, key: String): String? {
        if (query.isNullOrEmpty()) return null
        return query.split("&").firstNotNullOfOrNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0] == key) parts[1] else null
        }
    }
}
