package com.pinna.app.library.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * OkHttp-backed [Downloader] for NewPipeExtractor. Mirrors NewPipe's reference implementation: copies
 * the extractor's request (method, headers, body) onto an OkHttp call and maps the response back.
 */
class OkHttpDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) : Downloader() {

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val builder = okhttp3.Request.Builder().url(url)
        val requestBody = dataToSend?.toRequestBody(null, 0, dataToSend.size)
        builder.method(httpMethod, requestBody)

        headers.forEach { (headerName, headerValueList) ->
            builder.removeHeader(headerName)
            headerValueList.forEach { headerValue -> builder.addHeader(headerName, headerValue) }
        }
        if (headers["User-Agent"] == null) {
            builder.header("User-Agent", USER_AGENT)
        }

        val response = client.newCall(builder.build()).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val body = response.body?.string()
        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), body, latestUrl)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0"
    }
}
