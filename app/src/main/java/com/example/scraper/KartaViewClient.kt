package com.example.scraper

import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Public KartaView imagery record. Images are licensed CC BY-SA 4.0 and must
 * retain the stated attribution when saved or exported.
 */
data class KartaPhoto(
    val id: String,
    val sequenceId: String?,
    val latitude: Double,
    val longitude: Double,
    val headingDegrees: Double?,
    val capturedAt: String?,
    val projection: String?,
    val fieldOfView: Double?,
    val imageUrl: String,
    val thumbnailUrl: String?,
    val license: String = "CC BY-SA 4.0",
    val attribution: String = "© Grab and KartaView Contributors"
)

object KartaViewClient {
    private const val PHOTO_ENDPOINT = "https://api.openstreetcam.org/2.0/photo/"
    private const val MAX_RESULTS = 150

    /**
     * Searches KartaView's public photo API within the supplied WGS84 rectangle.
     * KartaView returns a bounded page of imagery; the UI discloses that result count.
     */
    fun searchArea(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double
    ): List<KartaPhoto> {
        val url = "$PHOTO_ENDPOINT?" +
            "nwLat=$maxLatitude&nwLng=$minLongitude&" +
            "seLat=$minLatitude&seLng=$maxLongitude&" +
            "zoomLevel=18&join=sequence&orderBy=id&orderDirection=desc&itemsPerPage=$MAX_RESULTS"

        val body = Jsoup.connect(url)
            .ignoreContentType(true)
            .timeout(20_000)
            .execute()
            .body()
        val response = JSONObject(body)
        val status = response.optJSONObject("status")
        if (status?.optInt("httpCode", 0) != 200) {
            throw IllegalStateException(status?.optString("apiMessage", "KartaView request failed"))
        }

        val data = response.optJSONObject("result")?.optJSONArray("data")
            ?: return emptyList()
        val photosById = linkedMapOf<String, KartaPhoto>()

        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val photo = item.toKartaPhotoOrNull() ?: continue
            if (
                photo.latitude in minLatitude..maxLatitude &&
                photo.longitude in minLongitude..maxLongitude
            ) {
                photosById.putIfAbsent(photo.id, photo)
            }
        }
        return photosById.values.toList()
    }

    private fun JSONObject.toKartaPhotoOrNull(): KartaPhoto? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val latitude = optString("lat").toDoubleOrNull() ?: return null
        val longitude = optString("lng").toDoubleOrNull() ?: return null
        val processedImageUrl = optString("fileurlProc").takeIf { it.isNotBlank() }
        val imageUrl = processedImageUrl ?: return null

        return KartaPhoto(
            id = id,
            sequenceId = optString("sequenceId").takeIf { it.isNotBlank() },
            latitude = latitude,
            longitude = longitude,
            headingDegrees = optString("heading").toDoubleOrNull(),
            capturedAt = optString("shotDate").takeIf { it.isNotBlank() }
                ?: optString("dateAdded").takeIf { it.isNotBlank() },
            projection = optString("projection").takeIf { it.isNotBlank() },
            fieldOfView = optString("fieldOfView").toDoubleOrNull(),
            imageUrl = imageUrl,
            thumbnailUrl = optString("fileurlTh").takeIf { it.isNotBlank() }
        )
    }
}
