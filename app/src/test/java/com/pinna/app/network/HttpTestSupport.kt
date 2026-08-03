package com.pinna.app.network

import java.net.HttpURLConnection

data class HttpResponse(
    val code: Int,
    val body: String,
    val bytes: ByteArray,
    val headers: Map<String, List<String>>,
)

fun HttpURLConnection.toHttpResponse(): HttpResponse {
    val code = responseCode
    val stream = if (code in 200..399) inputStream else errorStream
    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
    return HttpResponse(code = code, body = bytes.decodeToString(), bytes = bytes, headers = headerFields)
}
