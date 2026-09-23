package com.example.scraper

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class GoogleStreetViewImageFetchTest {

    @Test
    fun testSearchAreaSnappingMargin() {
        val minLat = 51.5070
        val maxLat = 51.5090
        val minLng = -0.1290
        val maxLng = -0.1270
        val spacingMeters = 50.0

        val latMargin = (spacingMeters * 2) / 111_320.0
        val centerLat = (minLat + maxLat) / 2.0
        val lngMargin = (spacingMeters * 2) / (111_320.0 * cos(Math.toRadians(centerLat)).coerceIn(0.01, 1.0))

        val snappedPanoLat = 51.50700016595088
        val snappedPanoLng = -0.1291743993833856

        val isInsideWithMargin = (snappedPanoLat in (minLat - latMargin)..(maxLat + latMargin)) &&
                (snappedPanoLng in (minLng - lngMargin)..(maxLng + lngMargin))

        assertTrue("Snapped panorama should be accepted within snapping margin", isInsideWithMargin)
    }
}
