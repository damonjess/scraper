package com.example.scraper

import kotlin.math.ceil
import kotlin.math.min
import org.json.JSONObject
import org.jsoup.Jsoup

object KartaViewClient {
    private const val PHOTO_ENDPOINT = "https://api.openstreetcam.org/2.0/photo/"
    private const val MAX_RESULTS_PER_TILE = 150
    // KartaView rejects rectangles near 0.04° wide/high. 0.015° stays below
    // the observed provider limit while keeping a reasonable request count.
    private const val MAX_TILE_SPAN_DEGREES = 0.015
    private const val MAX_TILE_COUNT = 400

    /**
     * Splits a large rectangle into KartaView-compatible bounding boxes, then
     * combines the returned frames. This prevents the provider's HTTP 400 error
     * for large map selections.
     */
    fun searchArea(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double
    ): List<StreetImage> {
        val latitudeTiles = ceil((maxLatitude - minLatitude) / MAX_TILE_SPAN_DEGREES)
            .toInt().coerceAtLeast(1)
        val longitudeTiles = ceil((maxLongitude - minLongitude) / MAX_TILE_SPAN_DEGREES)
            .toInt().coerceAtLeast(1)
        val tileCount = latitudeTiles * longitudeTiles
        if (tileCount > MAX_TILE_COUNT) {
            throw ProviderSearchException(
                "This area needs $tileCount KartaView searches. Draw a smaller area and try again."
            )
        }

        val imagesById = linkedMapOf<String, StreetImage>()
        for (latitudeIndex in 0 until latitudeTiles) {
            val tileMinLatitude = minLatitude + latitudeIndex * MAX_TILE_SPAN_DEGREES
            val tileMaxLatitude = min(maxLatitude, tileMinLatitude + MAX_TILE_SPAN_DEGREES)
            for (longitudeIndex in 0 until longitudeTiles) {
                val tileMinLongitude = minLongitude + longitudeIndex * MAX_TILE_SPAN_DEGREES
                val tileMaxLongitude = min(maxLongitude, tileMinLongitude + MAX_TILE_SPAN_DEGREES)
                val tileImages = searchTile(
                    minLatitude = tileMinLatitude,
                    maxLatitude = tileMaxLatitude,
                    minLongitude = tileMinLongitude,
                    maxLongitude = tileMaxLongitude
                )
                tileImages.forEach { image ->
                    if (
                        image.latitude in minLatitude..maxLatitude &&
                        image.longitude in minLongitude..maxLongitude
                    ) {
                        imagesById.putIfAbsent(image.stableId, image)
                    }
                }
            }
        }
        return imagesById.values.toList()
    }

    private fun searchTile(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double
    ): List<StreetImage> {
        val url = "$PHOTO_ENDPOINT?" +
            "nwLat=$maxLatitude&nwLng=$minLongitude&" +
            "seLat=$minLatitude&seLng=$maxLongitude&" +
            "zoomLevel=18&join=sequence&orderBy=id&orderDirection=desc&itemsPerPage=$MAX_RESULTS_PER_TILE"

        val body = try {
            Jsoup.connect(url)
                .ignoreContentType(true)
                .timeout(60_000)
                .execute()
                .body()
        } catch (error: Exception) {
            throw ProviderSearchException("KartaView request failed: ${error.message ?: "network error"}")
        }
        val response = JSONObject(body)
        val status = response.optJSONObject("status")
        if (status?.optInt("httpCode", 0) != 200) {
            throw ProviderSearchException(
                status?.optString("apiMessage", "KartaView request failed")
                    ?: "KartaView request failed"
            )
        }

        val data = response.optJSONObject("result")?.optJSONArray("data")
            ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toStreetImageOrNull()?.let(::add)
            }
        }
    }

    private fun JSONObject.toStreetImageOrNull(): StreetImage? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val latitude = optString("lat").toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val longitude = optString("lng").toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val imageUrl = optString("fileurlProc").takeIf { it.isNotBlank() } ?: return null

        return StreetImage(
            provider = ImageryProvider.KARTAVIEW,
            id = id,
            sequenceId = optString("sequenceId").takeIf { it.isNotBlank() },
            latitude = latitude,
            longitude = longitude,
            headingDegrees = optString("heading").toDoubleOrNull()?.takeIf { it.isFinite() },
            capturedAt = optString("shotDate").takeIf { it.isNotBlank() }
                ?: optString("dateAdded").takeIf { it.isNotBlank() },
            projection = optString("projection").takeIf { it.isNotBlank() },
            fieldOfView = optString("fieldOfView").toDoubleOrNull(),
            imageUrl = imageUrl,
            thumbnailUrl = optString("fileurlTh").takeIf { it.isNotBlank() }
        )
    }
}
