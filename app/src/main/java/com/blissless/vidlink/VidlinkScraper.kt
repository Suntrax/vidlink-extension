package com.blissless.vidlink

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * VidLink.pro scraper for the Chizuki movie client.
 *
 * VidLink.pro serves content as either:
 *   - An HLS .m3u8 playlist (for some titles), OR
 *   - Direct .mp4 files at multiple qualities (360/480/720, etc.)
 *
 * Flow:
 *   1. Search TMDB → TMDB ID
 *   2. Construct embed URL:
 *      - Movie: https://vidlink.pro/movie/{tmdbId}
 *      - TV:    https://vidlink.pro/tv/{tmdbId}/{season}/{episode}
 *   3. Load in headless WebView with ad-blocking
 *   4. Intercept m3u8 via shouldInterceptRequest
 *   5. Also intercept /api/b/ JSON responses which contain all stream URLs
 *   6. Return the first m3u8 found, or the highest-quality mp4 from the API
 */
object VidlinkScraper {

    private const val TAG = "VidLink/Scraper"
    private const val BASE = "https://vidlink.pro"
    private const val TMDB_BASE = "https://api.themoviedb.org/3"
    private const val TMDB_API_KEY = "a46c50a0ccb1bafe2b15665df7fad7e1"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val blockedDomains = listOf(
        "googletagmanager.com", "google-analytics.com", "mc.yandex.ru",
        "clarity.ms", "c.bing.com", "adsco.re", "pemsrv.com",
        "usrpubtrk.com", "adexchangerapid.com", "intellipopup.com",
        "cloudflareinsights.com"
    )

    fun scrape(context: Context, movieName: String?, anilistId: String?): Any {
        Log.d(TAG, "scrape: movieName=$movieName anilistId=$anilistId")
        if (movieName.isNullOrBlank()) {
            return mapOf("error" to "No movie name provided.")
        }

        val tmdbId = try {
            searchTmdb(movieName)
        } catch (e: Exception) {
            return mapOf("error" to "TMDB search failed: ${e.message}")
        } ?: return mapOf("error" to "No TMDB match for '$movieName'.")

        Log.d(TAG, "scrape: TMDB id = $tmdbId")

        val embedUrl = "$BASE/movie/$tmdbId"
        Log.d(TAG, "scrape: embed URL = $embedUrl")

        val streamUrl = try {
            interceptStream(context, embedUrl)
        } catch (e: Exception) {
            return mapOf("error" to "Failed to resolve stream: ${e.message}")
        }

        if (streamUrl.isNullOrEmpty()) {
            return mapOf("error" to "No stream URL found.")
        }

        Log.d(TAG, "scrape: stream URL = $streamUrl")
        return mapOf("Auto" to listOf(streamUrl))
    }

    fun scrapeTv(context: Context, movieName: String?, season: Int?, episode: Int?): Any {
        Log.d(TAG, "scrapeTv: movieName=$movieName season=$season episode=$episode")
        if (movieName.isNullOrBlank()) {
            return mapOf("error" to "No movie name provided.")
        }

        val tmdbId = try {
            searchTmdbTv(movieName)
        } catch (e: Exception) {
            return mapOf("error" to "TMDB search failed: ${e.message}")
        } ?: return mapOf("error" to "No TMDB match for '$movieName'.")

        val s = season ?: 1
        val e = episode ?: 1
        val embedUrl = "$BASE/tv/$tmdbId/$s/$e"
        Log.d(TAG, "scrapeTv: embed URL = $embedUrl")

        val streamUrl = try {
            interceptStream(context, embedUrl)
        } catch (e: Exception) {
            return mapOf("error" to "Failed to resolve stream: ${e.message}")
        }

        if (streamUrl.isNullOrEmpty()) {
            return mapOf("error" to "No stream URL found.")
        }

        return mapOf("Auto" to listOf(streamUrl))
    }

    // ---------- TMDB ----------

    private fun searchTmdb(query: String): Int? {
        val url = "$TMDB_BASE/search/movie?api_key=$TMDB_API_KEY&query=${URLEncoder.encode(query, "UTF-8")}"
        val data = JSONObject(httpGet(url))
        val results = data.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        return results.getJSONObject(0).optInt("id", -1).takeIf { it > 0 }
    }

    private fun searchTmdbTv(query: String): Int? {
        val url = "$TMDB_BASE/search/tv?api_key=$TMDB_API_KEY&query=${URLEncoder.encode(query, "UTF-8")}"
        val data = JSONObject(httpGet(url))
        val results = data.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        return results.getJSONObject(0).optInt("id", -1).takeIf { it > 0 }
    }

    // ---------- WebView stream interception ----------

    /**
     * Captures the stream URL by:
     *   1. Intercepting any request whose URL contains .m3u8
     *   2. Intercepting any media-typed request whose URL contains .mp4
     *   3. Reading the JSON body of /api/b/ calls (contains all stream URLs)
     *
     * Returns the .m3u8 URL if available, otherwise the highest-quality .mp4
     * URL from the API JSON.
     */
    @Suppress("SetJavaScriptEnabled")
    private fun interceptStream(context: Context, embedUrl: String, timeoutMs: Long = 30_000): String? {
        val m3u8Ref = AtomicReference<String?>(null)
        val mp4Ref = AtomicReference<String?>(null)
        val apiResponseRef = AtomicReference<String?>(null)
        val handler = Handler(Looper.getMainLooper())

        val webViewRef = AtomicReference<WebView?>(null)
        val initLatch = CountDownLatch(1)
        val initError = AtomicReference<Throwable?>(null)

        handler.post {
            try {
                webViewRef.set(WebView(context))
            } catch (e: Exception) {
                initError.set(e)
            } finally {
                initLatch.countDown()
            }
        }
        if (!initLatch.await(10, TimeUnit.SECONDS)) {
            throw IOException("Timed out waiting for WebView creation")
        }
        initError.get()?.let { throw IOException("WebView creation failed: ${it.message}", it) }
        val webView = webViewRef.get() ?: throw IOException("WebView init failed")

        handler.post {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = UA
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    // Block ad/analytics domains
                    for (domain in blockedDomains) {
                        if (url.contains(domain, ignoreCase = true)) {
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                    }

                    // Capture m3u8
                    if (url.contains(".m3u8", ignoreCase = true) && m3u8Ref.get() == null) {
                        Log.d(TAG, "intercepted m3u8: $url")
                        m3u8Ref.set(url)
                    }

                    // Capture mp4 (only media resource type, not ad trailers)
                    if (url.contains(".mp4", ignoreCase = true) && mp4Ref.get() == null) {
                        Log.d(TAG, "intercepted mp4: $url")
                        mp4Ref.set(url)
                    }

                    // Capture /api/b/ JSON responses — contains all stream URLs
                    if (url.contains("/api/b/") && apiResponseRef.get() == null) {
                        Log.d(TAG, "intercepted API response: $url")
                        // We can't read the response body in shouldInterceptRequest,
                        // but we can fetch it separately.
                        try {
                            val apiData = httpGet(url)
                            apiResponseRef.set(apiData)
                            Log.d(TAG, "API response (${apiData.length} chars): ${apiData.take(200)}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to fetch API response", e)
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }

            Log.d(TAG, "loading embed URL: $embedUrl")
            webView.loadUrl(embedUrl)
        }

        // Poll for stream URL
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (m3u8Ref.get() != null) break
            if (mp4Ref.get() != null && apiResponseRef.get() != null) break
            if (apiResponseRef.get() != null) {
                // API response arrived — give m3u8/mp4 a brief moment to land
                Thread.sleep(1500)
                break
            }
            Thread.sleep(500)
        }

        handler.post { webView.destroy() }

        // Prefer m3u8 (HLS)
        m3u8Ref.get()?.let { return it }

        // Try extracting best-quality mp4 from API JSON
        apiResponseRef.get()?.let { apiJson ->
            val streamUrl = extractBestStreamFromApi(apiJson)
            if (!streamUrl.isNullOrEmpty()) {
                Log.d(TAG, "extracted stream from API JSON: $streamUrl")
                return streamUrl
            }
        }

        // Fallback to intercepted mp4
        return mp4Ref.get()
    }

    /**
     * Extracts the highest-quality stream URL from the /api/b/ JSON response.
     * The JSON has a structure like:
     *   { "stream": { "qualities": { "1080": {"url": "..."}, "720": {"url": "..."} } } }
     */
    private fun extractBestStreamFromApi(apiJson: String): String? {
        try {
            val data = JSONObject(apiJson)

            // Try stream.qualities
            val stream = data.optJSONObject("stream")
            if (stream != null) {
                val qualities = stream.optJSONObject("qualities")
                if (qualities != null) {
                    var bestKey: String? = null
                    var bestVal = 0
                    val keys = qualities.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val num = key.toIntOrNull() ?: 0
                        if (num > bestVal) {
                            bestVal = num
                            bestKey = key
                        }
                    }
                    if (bestKey != null) {
                        val entry = qualities.optJSONObject(bestKey)
                        val url = entry?.optString("url", "")
                        if (!url.isNullOrEmpty()) return url
                    }
                }
            }

            // Fallback: walk the JSON for any stream URL
            return findStreamUrlInJson(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse API JSON", e)
            return null
        }
    }

    /** Recursively searches a JSONObject for any string that looks like a stream URL. */
    private fun findStreamUrlInJson(obj: JSONObject): String? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = obj.get(key)) {
                is String -> {
                    if (value.contains("m3u8") || value.contains(".mp4")) {
                        return value
                    }
                }
                is JSONObject -> {
                    val found = findStreamUrlInJson(value)
                    if (found != null) return found
                }
                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is String && (item.contains("m3u8") || item.contains(".mp4"))) {
                            return item
                        }
                        if (item is JSONObject) {
                            val found = findStreamUrlInJson(item)
                            if (found != null) return found
                        }
                    }
                }
            }
        }
        return null
    }

    // ---------- HTTP ----------

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, */*;q=0.8")
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code for $urlStr${if (err.isNotBlank()) ": $err" else ""}")
        } finally {
            conn.disconnect()
        }
    }
}
