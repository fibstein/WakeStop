package com.fibstein.wakestop.data.geocoding

import com.fibstein.wakestop.BuildConfig
import com.fibstein.wakestop.tracking.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PlaceResult(val name: String, val detail: String, val point: GeoPoint)

/**
 * Minimal client for the public Photon (komoot) geocoder. It is a fair-use service:
 * callers must debounce and keep requests sparse.
 */
class PhotonClient(private val baseUrl: String = "https://photon.komoot.io") {

    suspend fun search(query: String, near: GeoPoint?, limit: Int = 8): List<PlaceResult> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" } ?: ""
        return get("$baseUrl/api/?q=$q&limit=$limit$bias")
    }

    suspend fun reverse(point: GeoPoint): PlaceResult? =
        get("$baseUrl/reverse?lat=${point.lat}&lon=${point.lon}&limit=1").firstOrNull()

    private suspend fun get(url: String): List<PlaceResult> =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "WakeStop-Android/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) error("Photon HTTP ${conn.responseCode}")
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        fun parse(json: String): List<PlaceResult> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            return (0 until features.length()).mapNotNull { i ->
                val f = features.getJSONObject(i)
                val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
                val p = f.optJSONObject("properties") ?: JSONObject()
                val street = listOfNotNull(p.optStringOrNull("street"), p.optStringOrNull("housenumber"))
                    .joinToString(" ").ifBlank { null }
                val name = p.optStringOrNull("name") ?: street ?: return@mapNotNull null
                val detail = listOfNotNull(
                    street.takeIf { it != name },
                    p.optStringOrNull("district"),
                    p.optStringOrNull("city"),
                    p.optStringOrNull("country"),
                ).distinct().joinToString(", ")
                PlaceResult(name, detail, GeoPoint(lat = coords.getDouble(1), lon = coords.getDouble(0)))
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
    }
}
