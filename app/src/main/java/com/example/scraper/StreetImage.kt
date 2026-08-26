package com.example.scraper

enum class ImageryProvider(
    val displayName: String,
    val shortName: String,
    val attribution: String,
    val license: String
) {
    KARTAVIEW(
        displayName = "KartaView",
        shortName = "kartaview",
        attribution = "© Grab and KartaView Contributors",
        license = "CC BY-SA 4.0"
    ),
    MAPILLARY(
        displayName = "Mapillary",
        shortName = "mapillary",
        attribution = "© Mapillary",
        license = "CC BY-SA 4.0"
    )
}

/** A geolocated image exposed through a provider's documented API. */
data class StreetImage(
    val provider: ImageryProvider,
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
    val license: String = provider.license,
    val attribution: String = provider.attribution
) {
    val stableId: String get() = "${provider.shortName}:$id"
}

class ProviderSearchException(message: String) : IllegalStateException(message)
