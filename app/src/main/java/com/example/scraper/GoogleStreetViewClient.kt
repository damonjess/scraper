package com.example.scraper

import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Searches Google Street View coverage inside a rectangle using the official
 * Street View Static API. Google offers no bounding-box search, so the area is
 * sampled with a grid of points: every sample asks the (free, unmetered)
 * metadata endpoint whether coverage exists nearby, and duplicate pano hits are
 * merged. For each found panorama location, 4 cardinal camera directions (North 0°,
 * East 90°, South 180°, West 270°) are presented so the user can select the best view.
 */
object GoogleStreetViewClient {
    private const val METADATA_ENDPOINT = "https://maps.googleapis.com/maps/api/streetview/metadata"
    private const val IMAGE_ENDPOINT = "https://maps.googleapis.com/maps/api/streetview"
    private const val MAX_SAMPLE_POINTS = 500
    private const val MIN_SPACING_METERS = 10.0
    private const val MAX_SPACING_METERS = 500.0
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    private const val PARALLEL_REQUESTS = 8
    private const val IMAGE_SIZE = "640x640"
    private const val FIELD_OF_VIEW_DEGREES = 90

    private data class PanoMetadata(
        val panoId: String,
        val latitude: Double,
        val longitude: Double,
        val capturedAt: String?
    )

    /**
     * Returns the distinct Street View panoramas whose snapped location lies
     * inside or near the given rectangle. [spacingMeters] controls the grid density;
     * smaller values give denser coverage but more (free) metadata requests.
     */
    suspend fun searchArea(
        apiKey: String,
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
        spacingMeters: Double = 50.0
    ): List<StreetImage> {
        if (apiKey.isBlank()) {
            throw ProviderSearchException("Add a Google Street View API key before searching Google Street View.")
        }
        if (!spacingMeters.isFinite()) {
            throw ProviderSearchException("The Street View sample spacing must be a number.")
        }
        val spacing = spacingMeters.coerceIn(MIN_SPACING_METERS, MAX_SPACING_METERS)

        val latitudeStep = spacing / METERS_PER_DEGREE_LATITUDE
        val centerLatitude = (minLatitude + maxLatitude) / 2.0
        val longitudeStep = spacing /
            (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(centerLatitude)).coerceIn(0.01, 1.0))
        val rows = ceil((maxLatitude - minLatitude) / latitudeStep).toInt().coerceAtLeast(0) + 1
        val columns = ceil((maxLongitude - minLongitude) / longitudeStep).toInt().coerceAtLeast(0) + 1
        val sampleCount = rows * columns
        if (sampleCount > MAX_SAMPLE_POINTS) {
            throw ProviderSearchException(
                "This area needs $sampleCount Street View sample points at ${spacing.roundToInt()} m spacing. " +
                    "Increase the spacing or draw a smaller area and try again."
            )
        }

        val samplePoints = buildList {
            for (row in 0 until rows) {
                val latitude = min(minLatitude + row * latitudeStep, maxLatitude)
                for (column in 0 until columns) {
                    val longitude = min(minLongitude + column * longitudeStep, maxLongitude)
                    add(latitude to longitude)
                }
            }
        }

        val panoramas = withContext(Dispatchers.IO) {
            coroutineScope {
                val semaphore = Semaphore(PARALLEL_REQUESTS)
                samplePoints.map { (latitude, longitude) ->
                    async {
                        semaphore.withPermit {
                            try {
                                fetchMetadata(apiKey, latitude, longitude, spacing.roundToInt())
                            } catch (e: ProviderSearchException) {
                                val msg = e.message ?: ""
                                if (msg.contains("denied", ignoreCase = true) ||
                                    msg.contains("quota", ignoreCase = true) ||
                                    msg.contains("API key", ignoreCase = true)
                                ) {
                                    throw e
                                }
                                null
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // Allow a small snapping margin so valid road panoramas near boundary aren't dropped
        val marginMeters = spacing * 2.0
        val latitudeMargin = marginMeters / METERS_PER_DEGREE_LATITUDE
        val longitudeMargin = marginMeters /
            (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(centerLatitude)).coerceIn(0.01, 1.0))

        val panoramasById = linkedMapOf<String, PanoMetadata>()
        panoramas.filterNotNull().forEach { pano ->
            if (
                pano.latitude in (minLatitude - latitudeMargin)..(maxLatitude + latitudeMargin) &&
                pano.longitude in (minLongitude - longitudeMargin)..(maxLongitude + longitudeMargin)
            ) {
                panoramasById.putIfAbsent(pano.panoId, pano)
            }
        }

        val cardinalAngles = listOf(
            0 to "North (0°)",
            90 to "East (90°)",
            180 to "South (180°)",
            270 to "West (270°)"
        )

        val resultImages = mutableListOf<StreetImage>()
        panoramasById.values.forEach { pano ->
            cardinalAngles.forEach { (heading, label) ->
                val imageUrl = buildImageUrl(apiKey, pano.panoId, heading)
                resultImages.add(
                    StreetImage(
                        provider = ImageryProvider.GOOGLE_STREETVIEW,
                        id = "${pano.panoId}_h$heading",
                        sequenceId = label,
                        latitude = pano.latitude,
                        longitude = pano.longitude,
                        headingDegrees = heading.toDouble(),
                        capturedAt = pano.capturedAt,
                        projection = "perspective",
                        fieldOfView = FIELD_OF_VIEW_DEGREES.toDouble(),
                        imageUrl = imageUrl,
                        thumbnailUrl = null // Avoid auto-loading paid Static API thumbnails in UI grids
                    )
                )
            }
        }
        return resultImages
    }

    /** Fetches coverage for one sample point; returns null when there is no pano nearby. */
    private fun fetchMetadata(
        apiKey: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int
    ): PanoMetadata? {
        val response = try {
            Jsoup.connect(METADATA_ENDPOINT)
                .data("location", String.format(Locale.US, "%.6f,%.6f", latitude, longitude))
                .data("radius", radiusMeters.toString())
                .data("source", "outdoor")
                .data("key", apiKey)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .timeout(30_000)
                .execute()
        } catch (error: Exception) {
            throw ProviderSearchException(
                "Google Street View request failed. Check the API key and network connection. " +
                    "(${error.message ?: "network error"})"
            )
        }

        val body = response.body()
        if (body.isNullOrBlank()) {
            throw ProviderSearchException("Google Street View request failed: Empty response from server.")
        }

        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw ProviderSearchException("Google Street View request failed: Invalid JSON response.")
        }

        when (json.optString("status")) {
            "OK" -> Unit
            "ZERO_RESULTS", "NOT_FOUND" -> return null
            "OVER_QUERY_LIMIT" -> throw ProviderSearchException(
                "Google Street View quota exceeded. Increase the sample spacing or check the key's " +
                    "quota in Google Cloud Console."
            )
            "REQUEST_DENIED" -> throw ProviderSearchException(
                "Google Street View request denied: ${json.optString("error_message", "check the API key")}. " +
                    "Verify the key has the Street View Static API enabled and billing set up."
            )
            "INVALID_REQUEST" -> throw ProviderSearchException(
                "Google Street View rejected a sample request: " +
                    "${json.optString("error_message", "invalid request")}."
            )
            else -> throw ProviderSearchException(
                "Google Street View request failed: " +
                    "${json.optString("error_message", response.statusMessage() ?: "unknown error")}."
            )
        }

        if (response.statusCode() != 200) {
            throw ProviderSearchException("Google Street View HTTP ${response.statusCode()}: ${response.statusMessage()}")
        }

        val panoId = json.optString("pano_id").takeIf { it.isNotBlank() } ?: return null
        val location = json.optJSONObject("location") ?: return null
        val panoLatitude = location.optDouble("lat", Double.NaN)
        val panoLongitude = location.optDouble("lng", Double.NaN)
        if (!panoLatitude.isFinite() || !panoLongitude.isFinite()) return null

        return PanoMetadata(
            panoId = panoId,
            latitude = panoLatitude,
            longitude = panoLongitude,
            capturedAt = json.optString("date").takeIf { it.isNotBlank() }
        )
    }

    private fun buildImageUrl(
        apiKey: String,
        panoId: String,
        heading: Int
    ): String = buildString {
        append(IMAGE_ENDPOINT)
        append("?pano=").append(URLEncoder.encode(panoId, "UTF-8"))
        append("&size=").append(IMAGE_SIZE)
        append("&heading=").append(heading)
        append("&fov=").append(FIELD_OF_VIEW_DEGREES)
        append("&key=").append(URLEncoder.encode(apiKey, "UTF-8"))
    }
}
