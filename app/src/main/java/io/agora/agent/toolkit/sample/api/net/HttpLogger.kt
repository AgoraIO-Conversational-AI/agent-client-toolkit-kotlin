package io.agora.agent.toolkit.sample.api.net

import android.util.Log
import io.agora.agent.toolkit.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit

class HttpLogger : Interceptor {
    companion object {
        // Excluded API paths
        private val EXCLUDE_PATHS = setOf(
            "/heartbeat",  // Heartbeat API
            "/ping",       // Ping API
        )

        // Excluded Content-Types
        private val EXCLUDE_CONTENT_TYPES = setOf(
            "multipart/form-data",    // File upload
            "application/octet-stream", // Binary stream
            "image/*",
            "file",
            "audio/*",                // Audio files
            "video/*"                 // Video files
        )
        
        // Paths containing these keywords will also be checked for content type exclusion
        private val SENSITIVE_PATH_KEYWORDS = setOf(
            "upload",
            "file",
            "media"
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!BuildConfig.DEBUG) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val url = request.url
        val requestId = UUID.randomUUID().toString().substring(0, 8)

        // Check if should completely skip logging or only log results
        val shouldSkipCompletely = shouldSkipLoggingCompletely(request)
        val logResultOnly = shouldLogResultOnly(request)
        
        // If not completely skipped and not only logging results, log the request
        if (!shouldSkipCompletely && !logResultOnly) {
            val logContent = buildLogContent(request)
            Log.d("[$requestId]-Request", logContent)
        } else if (logResultOnly) {
            Log.d("[$requestId]-Request", "Large file upload request: ${request.method} ${buildUrlString(request.url)}")
        }

        // Execute request
        val startNs = System.nanoTime()
        val response = chain.proceed(request)
        
        // If not completely skipping logging, log the response
        if (!shouldSkipCompletely) {
            logResponse(response, startNs, url, requestId)
        }

        return response
    }

    private fun buildLogContent(request: Request): String {
        val logContent = StringBuilder()

        // Start request info
        logContent.append("curl -X ${request.method}")

        // Add headers
        val headers = mutableListOf<Pair<String, String>>()
        request.body?.contentType()?.let { contentType ->
            headers.add("Content-Type" to contentType.toString())
        }
        request.headers.forEach { (name, value) ->
            if (name.lowercase() != "content-type") {
                headers.add(name to value)
            }
        }

        if (headers.isNotEmpty()) {
            logContent.append(" -H \"")
            headers.forEachIndexed { index, (name, value) ->
                if (index > 0) {
                    logContent.append(";")
                }
                val safeValue = redactedHeaderValue(name, value)
                logContent.append("$name:$safeValue")
            }
            logContent.append("\"")
        }

        // Add request body
        request.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            val charset = body.contentType()?.charset() ?: Charset.defaultCharset()
            val bodyString = buffer.readString(charset)
            
            // Format JSON body
            val formattedBody = formatJsonString(redactedBodyString(bodyString))
            logContent.append(" -d '${formattedBody}'")
        }

        // Add URL
        val urlString = buildUrlString(request.url)

        logContent.append(" \"$urlString\"")

        return logContent.toString()
    }

    private fun formatJsonString(input: String): String {
        return try {
            if (input.trim().startsWith("{")) {
                JSONObject(input).toString(4)
            } else if (input.trim().startsWith("[")) {
                JSONArray(input).toString(4)
            } else {
                input
            }
        } catch (e: Exception) {
            e.printStackTrace()
            input
        }
    }

    private fun buildUrlString(url: HttpUrl): String {
        return buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != 80 && url.port != 443) {
                append(":").append(url.port)
            }
            append(redactedPath(url.encodedPath))
            
            if (url.queryParameterNames.isNotEmpty()) {
                append("?")
                url.queryParameterNames.forEachIndexed { index, name ->
                    if (index > 0) append("&")
                    val value = url.queryParameter(name)?.let { redactedValue(name, it) }
                    append("$name=$value")
                }
            }
        }
    }

    private fun shouldFullyRedact(key: String): Boolean {
        val normalizedKey = key.lowercase()
        return normalizedKey.contains("secret") ||
            normalizedKey.contains("certificate") ||
            normalizedKey.contains("cert") ||
            normalizedKey.contains("password")
    }

    private fun shouldPartiallyRedact(key: String): Boolean {
        val normalizedKey = key.lowercase()
        return normalizedKey.contains("authorization") ||
            normalizedKey.contains("app_id") ||
            normalizedKey == "appid" ||
            normalizedKey.contains("token") ||
            normalizedKey.contains("api_key") ||
            normalizedKey.contains("apikey") ||
            normalizedKey == "key" ||
            normalizedKey == "voice_id"
    }

    private fun partialRedactionPrefixLength(key: String): Int {
        val normalizedKey = key.lowercase()
        return if (
            normalizedKey.contains("app_id") ||
            normalizedKey == "appid" ||
            normalizedKey == "voice_id"
        ) {
            2
        } else {
            3
        }
    }

    private fun partiallyRedact(value: String, prefixLength: Int = 3): String {
        val prefix = value.take(prefixLength)
        return if (prefix.isEmpty()) "<redacted>" else "$prefix***"
    }

    private fun redactedHeaderValue(key: String, value: String): String {
        if (shouldFullyRedact(key)) {
            return "<redacted>"
        }
        if (!shouldPartiallyRedact(key)) {
            return value
        }
        if (key.contains("authorization", ignoreCase = true)) {
            val prefix = "agora token="
            if (value.startsWith(prefix, ignoreCase = true)) {
                return prefix + partiallyRedact(value.drop(prefix.length), prefixLength = 3)
            }
        }
        return partiallyRedact(value, prefixLength = partialRedactionPrefixLength(key))
    }

    private fun redactedValue(key: String, value: String): String {
        return when {
            shouldFullyRedact(key) -> "<redacted>"
            shouldPartiallyRedact(key) -> partiallyRedact(value, partialRedactionPrefixLength(key))
            else -> value
        }
    }

    private fun redactedPath(path: String): String {
        return path.replace(Regex("""/projects/([^/]+)""")) { match ->
            "/projects/${partiallyRedact(match.groupValues[1], prefixLength = 2)}"
        }
    }

    private fun redactedBodyString(bodyString: String): String {
        return try {
            when {
                bodyString.trim().startsWith("{") -> redactJsonObject(JSONObject(bodyString)).toString()
                bodyString.trim().startsWith("[") -> redactJsonArray(JSONArray(bodyString)).toString()
                else -> redactStringFields(bodyString)
            }
        } catch (e: Exception) {
            redactStringFields(bodyString)
        }
    }

    private fun redactJsonObject(json: JSONObject): JSONObject {
        val redacted = JSONObject()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            redacted.put(key, redactJsonValue(key, json.opt(key)))
        }
        return redacted
    }

    private fun redactJsonArray(json: JSONArray): JSONArray {
        val redacted = JSONArray()
        for (index in 0 until json.length()) {
            redacted.put(redactJsonValue(null, json.opt(index)))
        }
        return redacted
    }

    private fun redactJsonValue(key: String?, value: Any?): Any? {
        if (key != null && shouldFullyRedact(key)) {
            return "<redacted>"
        }
        if (key != null && shouldPartiallyRedact(key) && value is String) {
            return partiallyRedact(value, partialRedactionPrefixLength(key))
        }
        return when (value) {
            is JSONObject -> redactJsonObject(value)
            is JSONArray -> redactJsonArray(value)
            else -> value
        }
    }

    private fun redactStringFields(bodyString: String): String {
        return Regex(""""([^"]+)"\s*:\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
            .replace(bodyString) { match ->
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                if (shouldFullyRedact(key) || shouldPartiallyRedact(key)) {
                    """"$key":"${redactedValue(key, value)}""""
                } else {
                    match.value
                }
            }
    }

    // Determine if logging should be completely skipped
    private fun shouldSkipLoggingCompletely(request: Request): Boolean {
        return EXCLUDE_PATHS.any { path -> request.url.encodedPath.contains(path) }
    }

    // Determine if only results should be logged without request body
    private fun shouldLogResultOnly(request: Request): Boolean {
        val path = request.url.encodedPath.lowercase()
        if (SENSITIVE_PATH_KEYWORDS.any { keyword -> path.contains(keyword) }) {
            return true
        }
        
        request.body?.contentType()?.let { contentType ->
            val contentTypeString = contentType.toString()
            if (EXCLUDE_CONTENT_TYPES.any { type ->
                    if (type.endsWith("/*")) {
                        contentTypeString.startsWith(type.removeSuffix("/*"))
                    } else {
                        contentTypeString == type
                    }
                }) {
                return true
            }
        }
        
        return false
    }

    private fun logResponse(response: Response, startNs: Long, url: HttpUrl, requestId: String) {
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        val responseBody = response.body
        val contentLength = responseBody.contentLength()
        val bodySize = if (contentLength != -1L) "$contentLength-byte" else "unknown-length"

        val logContent = buildString {
            append("${response.code} ${response.message} for ${buildUrlString(url)}")
            append(" (${tookMs}ms")
            if (response.networkResponse != null && response.networkResponse != response) {
                append(", $bodySize body")
            }
            append(")")

            response.headers.forEach { (name, value) ->
                append("\n$name: ${redactedHeaderValue(name, value)}")
            }

            responseBody.let { body ->
                val contentType = body.contentType()
                if (contentType?.type == "application" &&
                    (contentType.subtype.contains("json") || contentType.subtype.contains("xml"))
                ) {
                    val source = body.source()
                    source.request(Long.MAX_VALUE)
                    val buffer = source.buffer
                    val charset = contentType.charset() ?: Charset.defaultCharset()
                    if (contentLength != 0L) {
                        append("\n\n")
                        val bodyString = buffer.clone().readString(charset)
                        append(formatJsonString(redactedBodyString(bodyString)))
                    }
                }
            }
        }
        Log.d("[$requestId]-Response", logContent)
    }
} 
