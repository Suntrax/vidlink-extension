package com.blissless.chizuki_extension_template

import android.content.Context

/**
 * Template scraper — implement your site's scraping logic here.
 *
 * Called by [ScraperProvider] with the three query parameters received from
 * the Chizuki main app:
 *   - `title`   — Movie or TV show title (always sent).
 *   - `season`  — Season number, or `null` for movies.
 *   - `episode` — Episode number, or `null` for movies.
 *
 * Return a `Map<String, *>` (preferred) or a `List<*>` (legacy flat list).
 * [ScraperProvider.serializeResult] handles the JSON conversion either way.
 *
 * Chizuki's player reads the first URL from the `"Auto"` array (it also
 * accepts `auto`/`default`/`urls` arrays, or the single-URL keys `url`/
 * `stream`/`m3u8`/`playlist` as a fallback). On failure, return a map with
 * an `"error"` key.
 *
 * Reference implementation: https://github.com/Suntrax/67movies-extension
 */
object TemplateScraper {

    fun scrape(
        context: Context,
        title: String?,
        season: Int?,
        episode: Int?
    ): Any {
        // TODO: Implement your scraping logic here.

        // Example returning a single HLS stream URL:
        // return mapOf(
        //     "Auto" to listOf("https://example.com/path/to/playlist.m3u8")
        // )

        // Example returning multiple stream variants:
        // return mapOf(
        //     "Auto" to listOf(
        //         "https://example.com/.../1080p.m3u8",
        //         "https://example.com/.../720p.m3u8"
        //     )
        // )

        // Example returning a single-URL fallback:
        // return mapOf("url" to "https://example.com/path/to/playlist.m3u8")

        // Example returning an error:
        // return mapOf("error" to "No title provided.")
        // return mapOf("error" to "No results found for '$title'.")

        return mapOf("error" to "TemplateScraper.scrape() not implemented.")
    }
}
