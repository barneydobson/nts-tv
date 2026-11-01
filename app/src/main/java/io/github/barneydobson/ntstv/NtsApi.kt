package io.github.barneydobson.ntstv

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Minimal client for the public NTS API that powers nts.live. Parsing is deliberately lenient. */
object NtsApi {
    private const val LIVE_URL = "https://www.nts.live/api/v2/live"
    private const val MIXTAPES_URL = "https://www.nts.live/api/v2/mixtapes"

    val USER_AGENT = "NtsTv/${BuildConfig.VERSION_NAME} (Android TV; unofficial)"

    suspend fun fetchLive(): List<LiveChannel> = withContext(Dispatchers.IO) {
        parseLive(JSONObject(get(LIVE_URL)))
    }

    suspend fun fetchMixtapes(): List<Mixtape> = withContext(Dispatchers.IO) {
        parseMixtapes(JSONObject(get(MIXTAPES_URL)))
    }

    private fun parseLive(root: JSONObject): List<LiveChannel> =
        root.optJSONArray("results").objects().mapNotNull { channel ->
            val name = channel.str("channel_name") ?: return@mapNotNull null
            LiveChannel(
                channel = name,
                now = parseShow(channel.optJSONObject("now")),
                next = parseShow(channel.optJSONObject("next")),
            )
        }

    private fun parseMixtapes(root: JSONObject): List<Mixtape> =
        root.optJSONArray("results").objects().mapNotNull { mixtape ->
            val alias = mixtape.str("mixtape_alias") ?: return@mapNotNull null
            val stream = mixtape.str("audio_stream_endpoint") ?: return@mapNotNull null
            Mixtape(
                alias = alias,
                title = unescape(mixtape.str("title") ?: alias),
                subtitle = mixtape.str("subtitle")?.let(::unescape),
                streamUrl = stream,
                imageUrl = pickImage(mixtape.optJSONObject("media")),
            )
        }

    private fun parseShow(broadcast: JSONObject?): Show? {
        if (broadcast == null) return null
        val details = broadcast.optJSONObject("embeds")?.optJSONObject("details")
        val title = broadcast.str("broadcast_title") ?: details?.str("name") ?: return null
        return Show(
            title = unescape(title),
            location = details?.str("location_long"),
            start = instant(broadcast.str("start_timestamp")),
            end = instant(broadcast.str("end_timestamp")),
            genres = details?.optJSONArray("genres").objects()
                .mapNotNull { it.str("value")?.let(::unescape) },
            imageUrl = pickImage(details?.optJSONObject("media")),
        )
    }

    // 800px images are plenty for a TV card and much lighter than the 1600px originals.
    private val IMAGE_KEYS = listOf(
        "picture_medium_large",
        "background_medium_large",
        "picture_large",
        "background_large",
        "picture_medium",
        "background_medium",
    )

    private fun pickImage(media: JSONObject?): String? =
        media?.let { m -> IMAGE_KEYS.firstNotNullOfOrNull { m.str(it) } }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code from $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** Like optString, but treats missing, JSON null and blank values as null. */
    private fun JSONObject.str(name: String): String? =
        if (isNull(name)) null else optString(name).trim().ifEmpty { null }

    private fun instant(value: String?): Instant? {
        if (value == null) return null
        return try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    private fun unescape(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}
