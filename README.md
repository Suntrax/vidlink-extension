# Chizuki: VidLink

A headless background scraper extension for the **Chizuki** movie client.
Resolves a playable m3u8 or mp4 stream URL for any movie or TV show from
[vidlink.pro](https://vidlink.pro).

## How it works

1. **Discovery** — The Chizuki main app finds this extension via the
   `com.blissless.movieclient.EXTENSION_BEACON` broadcast receiver and the
   `"Chizuki: "` label prefix.
2. **Query** — The main app calls this extension's `ContentProvider` with the
   URI `content://com.blissless.vidlink.provider/scrape?title=<name>` (movies)
   or `?title=<name>&season=N&episode=M` (TV).
3. **Scrape** — A two-step pipeline:

   **Step 1 — Resolve TMDB ID.** A single HTTP GET to the TMDB search API
   resolves the movie title to a TMDB ID.

   **Step 2 — Load embed + intercept stream.** The extension constructs the
   VidLink embed URL and loads it in a headless `WebView`:
   ```
   Movies: https://vidlink.pro/movie/{tmdbId}
   TV:     https://vidlink.pro/tv/{tmdbId}/{season}/{episode}
   ```
   The WebView intercepts:
   - Any `.m3u8` request (preferred — HLS is the most flexible format)
   - Any `.mp4` media request
   - The `/api/b/` JSON response which contains all stream URLs and qualities

   Ad/analytics domains are blocked to speed up loading and avoid crashes.

   If an m3u8 is found, it's returned immediately. Otherwise, the
   highest-quality mp4 URL is extracted from the API JSON.

4. **Return** — The stream URL is packaged as JSON:
   ```json
   { "Auto": ["https://...m3u8..."] }
   ```

## Data format returned

### Success
```json
{ "Auto": ["https://example.com/stream.m3u8"] }
```

### Error
```json
{ "error": "No TMDB match for 'Unknown Movie'." }
```

## Technical details

| | |
|---|---|
| **Dependencies** | Zero. Uses only `HttpURLConnection` + `WebView` + `org.json`. |
| **HTTP calls per scrape** | 1 (TMDB search) + 1 WebView load |
| **APK size** | ~40 KB after R8 shrinking |
| **Min Android** | API 26 |
| **Parameters read** | `title` (movie name), `season` (optional), `episode` (optional) |

## Architecture

| File | Purpose |
|------|---------|
| `VidlinkScraper.kt` | All HTTP + WebView logic. TMDB search, embed URL construction, m3u8/mp4 interception, API JSON parsing. |
| `ScraperProvider.kt` | `ContentProvider` entry point. Serializes the scraper result to JSON. |
| `ExtensionBeaconReceiver.kt` | Empty `BroadcastReceiver` for discovery. |

## Building

1. (Optional) Set up release signing in `local.properties`.
2. `./gradlew assembleRelease`
3. `adb install -r app/build/outputs/apk/release/app-release.apk`
