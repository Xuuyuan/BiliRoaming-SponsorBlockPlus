package me.iacn.biliroaming.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.utils.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class SponsorBlockSegment(
    val start: Double,
    val end: Double,
    val uuid: String,
    val category: String,
    val actionType: String,
)

object SponsorBlockApi {
    suspend fun getSkipSegments(bvid: String, cid: Long): List<SponsorBlockSegment> = withContext(Dispatchers.IO) {
        runCatching {
            getSkipSegmentsByHash(bvid, cid).ifEmpty {
                getSkipSegmentsByVideoId(bvid, cid)
            }
        }.onFailure {
            Log.e(it)
        }.getOrDefault(emptyList())
    }

    private fun getSkipSegmentsByHash(bvid: String, cid: Long): List<SponsorBlockSegment> {
        val hashPrefix = sha256(bvid).take(4)
        val raw = request("https://bsbsb.top/api/skipSegments/$hashPrefix") ?: return emptyList()
        return parseHashedSegments(raw, bvid, cid)
    }

    private fun getSkipSegmentsByVideoId(bvid: String, cid: Long): List<SponsorBlockSegment> {
        val videoId = URLEncoder.encode(bvid, "UTF-8")
        val url = "https://bsbsb.top/api/skipSegments?videoID=$videoId&cid=$cid&category=sponsor&actionType=skip"
        val raw = request(url) ?: return emptyList()
        return parseSegmentArray(JSONArray(raw), cid)
    }

    private fun request(url: String): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("origin", BuildConfig.APPLICATION_ID)
        connection.setRequestProperty("x-ext-version", BuildConfig.VERSION_NAME)
        connection.setRequestProperty("User-Agent", "BiliRoaming/${BuildConfig.VERSION_NAME}")

        return when (connection.responseCode) {
            HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
            HttpURLConnection.HTTP_NOT_FOUND -> null
            else -> {
                Log.d("SponsorBlock: $url failed ${connection.responseCode}")
                null
            }
        }
    }

    private fun parseHashedSegments(raw: String, bvid: String, cid: Long): List<SponsorBlockSegment> {
        val array = JSONArray(raw)
        return buildList {
            for (i in 0 until array.length()) {
                val video = array.optJSONObject(i) ?: continue
                if (video.optString("videoID") != bvid) continue

                addAll(parseSegmentArray(video.optJSONArray("segments") ?: continue, cid))
            }
        }.sortedBy { it.start }
    }

    private fun parseSegmentArray(segments: JSONArray, cid: Long): List<SponsorBlockSegment> = buildList {
        for (j in 0 until segments.length()) {
            val item = segments.optJSONObject(j) ?: continue
            if (item.optString("category") != "sponsor") continue
            if (item.optString("actionType") != "skip") continue
            if (item.has("cid") && item.optLong("cid") != cid) continue

            val segment = item.optJSONArray("segment") ?: continue
            if (segment.length() < 2) continue

            val start = segment.optDouble(0, -1.0)
            val end = segment.optDouble(1, -1.0)
            if (start < 0 || end <= start) continue

            add(
                SponsorBlockSegment(
                    start = start,
                    end = end,
                    uuid = item.optString("UUID"),
                    category = item.optString("category"),
                    actionType = item.optString("actionType"),
                )
            )
        }
    }.sortedBy { it.start }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
