package com.blissless.vidlink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class ScraperProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.blissless.vidlink.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1
        private const val TAG = "VidLink"
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        when (uriMatcher.match(uri)) {
            CODE_SCRAPES -> {
                val movieName = uri.getQueryParameter("anime")
                    ?: uri.getQueryParameter("movie")
                    ?: uri.getQueryParameter("title")
                val anilistId = uri.getQueryParameter("anilistId")
                val season = uri.getQueryParameter("season")?.toIntOrNull()
                val episode = uri.getQueryParameter("episode")?.toIntOrNull()
                val cursor = MatrixCursor(arrayOf("data"))

                Log.d(TAG, "query: movieName=$movieName season=$season episode=$episode")

                try {
                    val result = if (season != null || episode != null) {
                        VidlinkScraper.scrapeTv(context!!, movieName, season, episode)
                    } else {
                        VidlinkScraper.scrape(context!!, movieName, anilistId)
                    }
                    val json = serializeResult(result)
                    Log.d(TAG, "result (${json.length} chars): ${json.take(200)}")
                    cursor.addRow(arrayOf(json))
                } catch (e: Exception) {
                    Log.e(TAG, "scrape failed", e)
                    val msg = e.message?.replace("\"", "\\\"") ?: "unknown error"
                    cursor.addRow(arrayOf("{\"error\":\"Scraping failed: $msg\"}"))
                }
                return cursor
            }
        }
        return null
    }

    private fun serializeResult(result: Any): String {
        return when (result) {
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((key, value) in result) {
                    when (value) {
                        is List<*> -> {
                            val arr = JSONArray()
                            for (item in value) arr.put(item)
                            obj.put(key.toString(), arr)
                        }
                        null -> obj.put(key.toString(), JSONObject.NULL)
                        else -> obj.put(key.toString(), value)
                    }
                }
                obj.toString()
            }
            else -> result.toString()
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
