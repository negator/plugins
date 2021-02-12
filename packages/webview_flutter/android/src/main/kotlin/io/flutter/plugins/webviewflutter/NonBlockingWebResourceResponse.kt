package io.flutter.plugins.webviewflutter

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.*
import org.jsoup.*
import org.jsoup.internal.*
import org.jsoup.nodes.*
import org.jsoup.parser.*
import java.io.*
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.stream.*

internal class NonBlockingWebResourceResponse(val request: WebResourceRequest, val call: Call, val scripts: List<UserScript>, val postData: Map<String, String>) : WebResourceResponse("", "", null) {
    val latch: CountDownLatch = CountDownLatch(1)
    var response: Response
    val uri: String

    init {
        // log("debug Request body:", call.request().toString())
        // log("debug Request headers:", call.request().toString())
        val browserText = "<html></html>"
        uri = call.request().url().uri().toASCIIString().toLowerCase()
        response = Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .request(call.request())
            .code(200)
            .message("")
            .body(
                ResponseBody
                    .create(
                        MediaType.parse(""),
                        browserText
                    )
            )
            .build()

        when (uri) {
            "https://localhost/blank" -> latch.countDown()
            else -> call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    log("error", call.request().url().toString())
                    log(e)
                    latch.countDown()
                }

                override fun onResponse(call: Call?, resp: Response) {
                    response = resp
                    // log("debug Response body:", resp.toString())
                    // log("debug Response headers:", resp.headers().toString())
                    latch.countDown()
                }
            })
        }
    }

    private fun await() {
        try {
            latch.await()
        } catch (e: Exception) {
            log(e)
        }
    }

    override fun getMimeType(): String {
        await()
        var mt = ""
        response.body()?.contentType()?.let { m ->
            mt = m.type().toString() + "/" + m.subtype()
        }
        return mt
    }

    override fun getEncoding(): String {
        await()
        val ct = response.body()?.contentType()?.charset()?.toString()?.toLowerCase()?.trim()
        return ct ?: "utf-8"
    }

    override fun getStatusCode(): Int {

        await()
        return response.code()
    }

    override fun getReasonPhrase(): String {
        await()
        return response.message() ?: ""
    }

    override fun getResponseHeaders(): Map<String, String> {
        val headers = hashMapOf<String, String>()
        await()
        for (entry in response.headers().toMultimap().entries) {
            for (value in entry.value) {
                headers.put(entry.key.trim().toLowerCase(), value)
            }
        }
        val method = call.request().method().toLowerCase()

        if (method != "options") {
            return headers
        }
        var cors = headers.get("access-control-allow-headers") ?: ""
        cors = cors
            .split(",")
            .map { x -> x.trim() }
            .filterNot { x -> x.isEmpty() }
            .plus("x-cense-request-id")
            .distinct()
            .joinToString(", ")

        headers.put("access-control-allow-headers", cors)

        return headers
    }

    override fun getData(): InputStream {
        var result: InputStream = ByteArrayInputStream(byteArrayOf())

        await()

        response.body()?.byteStream()?.let { raw ->
            val input = BufferedInputStream(raw)
            val mime = getMimeType().toLowerCase()
            when {
                request.isForMainFrame() -> log("info", "Intercepting main frame: " + uri)
                sniffHtml(input) -> log("info", "Intercepting sniffed html: " + uri)

                else -> {
                    // log("info", "Not intercepting: " + uri)
                    result = input
                    return result
                }
            }

            val doc = Jsoup.parse(input, "UTF-8", uri)

            doc.select("form").attr("method", "get")

            val head = doc.head()
            for (s in scripts) {
                head.prependElement("script")
                    .attr("type", "text/javascript")
                    .appendChild(DataNode(s.source))
            }

            result = doc.html().byteInputStream()
        }

        return result
    }

    companion object {

        val docTypeRe = Regex("^\\s*<\\!doctype\\s+html", RegexOption.IGNORE_CASE)
        val htmlRe = Regex("^\\s*<\\s*html", RegexOption.IGNORE_CASE)

        val bufsz = 2048

        protected fun log(method: String, msg: Any) {
            System.out.println(method + ": " + msg.toString())
        }

        protected fun log(msg: Exception) {
            msg.printStackTrace()
            log("error", msg.message ?: "")
        }

        protected fun sniffHtml(input: BufferedInputStream): Boolean {
            input.mark(bufsz)
            val buf = CharArray(bufsz)
            val reader = BufferedReader(InputStreamReader(input), bufsz)
            try {
                reader.read(buf, 0, bufsz)
                val str = String(buf)
                // println(str)
                return docTypeRe.containsMatchIn(str) || htmlRe.containsMatchIn(str)
            } finally {
                input.reset()
            }
            return false
        }
    }
}
