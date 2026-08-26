package com.example.scraper

import kotlin.math.ceil
import kotlin.math.min
import org.json.JSONObject
import org.jsoup.Jsoup

object MapillaryClient {
    private const val IMAGE_ENDPOINT = "https://graph.mapillary.com/images"
    // Mapillary requires bbox searches to be smaller than 0.01° square.
    private const val MAX_TILE_SPAN_DEGREES = 0.009
    private const val MAX_TILE_COUNT = 400
    private const val MAX_RESULTS_PER_TILE = 100

    /**
     * Searches Mapillary's documented Images API. The caller must supply an app
     * client token created in Mapillary's developer dashboard; it is not stored.
     */
    fun searchArea(
        accessToken: String,
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double
    ): List<StreetImage> {
        if (accessToken.isBlank()) {
            throw ProviderSearchException("Add a Mapillary client token before searching Mapillary.")
        }
        val latitudeTiles = ceil((maxLatitude - minLatitude) / MAX_TILE_SPAN_DEGREES)
            .toInt().coerceAtLeast(1)
        val longitudeTiles = ceil((maxLongitude - minLongitude) / MAX_TILE_SPAN_DEGREES)
            .toInt().coerceAtLeast(1)
        val tileCount = latitudeTiles * longitudeTiles
        if (tileCount > MAX_TILE_COUNT) {
            throw ProviderSearchException(
                "This area needs $tileCount Mapillary searches. Draw a smaller area and try again."
            )
        }

        val imagesById = linkedMapOf<String, StreetImage>()
        for (latitudeIndex in 0 until latitudeTiles) {
            val tileMinLatitude = minLatitude + latitudeIndex * MAX_TILE_SPAN_DEGREES
            val tileMaxLatitude = min(maxLatitude, tileMinLatitude + MAX_TILE_SPAN_DEGREES)
            for (longitudeIndex in 0 until longitudeTiles) {
                val tileMinLongitude = minLongitude + longitudeIndex * MAX_TILE_SPAN_DEGREES
                val tileMaxLongitude = min(maxLongitude, tileMinLongitude + MAX_TILE_SPAN_DEGREES)
                searchTile(
                    accessToken = accessToken,
                    minLatitude = tileMinLatitude,
                    maxLatitude = tileMaxLatitude,
                    minLongitude = tileMinLongitude,
                    maxLongitude = tileMaxLongitude
                ).forEach { image ->
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
        accessToken: String,
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double
    ): List<StreetImage> {
        val bbox = "$minLongitude,$minLatitude,$maxLongitude,$maxLatitude"
        val fields = "id,geometry,computed_geometry,compass_angle,captured_at,camera_type,is_pano,thumb_1024_url,thumb_256_url"
        
        // Use access_token as a query parameter for better compatibility with some Mapillary API v4 configurations.
        val response = try {
            Jsoup.connect(IMAGE_ENDPOINT)
                .data("access_token", accessToken)
                .data("bbox", bbox)
                .data("fields", fields)
                .data("limit", MAX_RESULTS_PER_TILE.toString())
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(60_000)
                .execute()
        } catch (error: Exception) {
            throw ProviderSearchException(
                "Mapillary request failed. Check the client token and network connection. (${error.message ?: "network error"})"
            )
        }

        val body = response.body()
        if (body.isNullOrBlank()) {
            throw ProviderSearchException("Mapillary request failed: Empty response from server.")
        }

        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw ProviderSearchException("Mapillary request failed: Invalid JSON response.")
        }

        json.optJSONObject("error")?.let { errorJson ->
            val message = errorJson.optString("message", "request failed")
            val type = errorJson.optString("type", "")
            val code = errorJson.optInt("code", 0)
            throw ProviderSearchException("Mapillary: $message ($type, code $code)")
        }

        if (response.statusCode() != 200) {
            throw ProviderSearchException("Mapillary HTTP ${response.statusCode()}: ${response.statusMessage()}")
        }

        val data = json.optJSONArray("data") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.toStreetImageOrNull()?.let(::add)
            }
        }
    }

    private fun JSONObject.toStreetImageOrNull(): StreetImage? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val coordinates = optJSONObject("computed_geometry")
            ?.optJSONArray("coordinates")
            ?: optJSONObject("geometry")?.optJSONArray("coordinates")
            ?: return null
        if (coordinates.length() < 2) return null
        val longitude = coordinates.optDouble(0, Double.NaN)
        val latitude = coordinates.optDouble(1, Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val imageUrl = optString("thumb_1024_url").takeIf { it.isNotBlank() }
            ?: optString("thumb_256_url").takeIf { it.isNotBlank() }
            ?: return null

        return StreetImage(
            provider = ImageryProvider.MAPILLARY,
            id = id,
            sequenceId = null,
            latitude = latitude,
            longitude = longitude,
            headingDegrees = optDouble("compass_angle", Double.NaN).takeIf { it.isFinite() },
            capturedAt = optLong("captured_at", 0L).takeIf { it > 0L }?.toString(),
            projection = optString("camera_type").takeIf { it.isNotBlank() },
            fieldOfView = null,
            imageUrl = imageUrl,
            thumbnailUrl = optString("thumb_256_url").takeIf { it.isNotBlank() }
        )
    }
}
