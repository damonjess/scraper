package com.example.scraper

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {

    private companion object {
        const val OPEN_FREE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val INITIAL_LATITUDE = 51.5074
        const val INITIAL_LONGITUDE = -0.1278
        const val KARTAVIEW_ATTRIBUTION = "© Grab and KartaView Contributors · CC BY-SA 4.0"
    }

    private var mapView: MapView? = null

    data class LocationPoint(val latitude: Double, val longitude: Double)

    data class AreaBounds(
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double
    ) {
        fun summary(): String = String.format(
            Locale.US,
            "%.5f–%.5f, %.5f–%.5f",
            minLatitude,
            maxLatitude,
            minLongitude,
            maxLongitude
        )
    }

    enum class SelectionMode { POINT, AREA }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { OpenImageryApp() }
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroy() {
        mapView?.onDestroy()
        mapView = null
        super.onDestroy()
    }

    @Composable
    private fun OpenImageryApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var selectionMode by remember { mutableStateOf(SelectionMode.POINT) }
        var selectedPoint by remember { mutableStateOf(LocationPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE)) }
        var areaCorners by remember { mutableStateOf<List<LocationPoint>>(emptyList()) }
        var kartaPhotos by remember { mutableStateOf<List<KartaPhoto>>(emptyList()) }
        var selectedPhotoIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var statusText by remember { mutableStateOf("Tap Select Area to search open street imagery.") }
        var isSearching by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        val selectedBounds = remember(areaCorners) {
            if (areaCorners.size == 2) boundsFrom(areaCorners[0], areaCorners[1]) else null
        }
        val selectedPhotos = remember(kartaPhotos, selectedPhotoIds) {
            kartaPhotos.filter { it.id in selectedPhotoIds }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        selectionMode = if (selectionMode == SelectionMode.POINT) {
                            SelectionMode.AREA
                        } else {
                            SelectionMode.POINT
                        }
                        areaCorners = emptyList()
                        kartaPhotos = emptyList()
                        selectedPhotoIds = emptySet()
                        statusText = if (selectionMode == SelectionMode.AREA) {
                            "Area mode: tap the first corner of the search area."
                        } else {
                            "Point mode: tap a location, then choose Select Area to search imagery."
                        }
                    }
                ) {
                    Text(if (selectionMode == SelectionMode.AREA) "Select Point" else "Select Area")
                }

                if (selectedBounds != null) {
                    Button(
                        enabled = !isSearching && !isDownloading,
                        onClick = {
                            scope.launch {
                                isSearching = true
                                kartaPhotos = emptyList()
                                selectedPhotoIds = emptySet()
                                statusText = "Searching KartaView coverage…"
                                try {
                                    val results = withContext(Dispatchers.IO) {
                                        KartaViewClient.searchArea(
                                            minLatitude = selectedBounds.minLatitude,
                                            maxLatitude = selectedBounds.maxLatitude,
                                            minLongitude = selectedBounds.minLongitude,
                                            maxLongitude = selectedBounds.maxLongitude
                                        )
                                    }
                                    kartaPhotos = results
                                    statusText = if (results.isEmpty()) {
                                        "No KartaView frames were returned in this area. Try another area or import imagery you own."
                                    } else {
                                        "KartaView returned ${results.size} frames. Select the images you want to save."
                                    }
                                } catch (error: Exception) {
                                    statusText = "KartaView search failed: ${error.message ?: "network error"}"
                                } finally {
                                    isSearching = false
                                }
                            }
                        }
                    ) {
                        Text(if (isSearching) "Searching…" else "Find Open Images")
                    }
                }
            }

            Text(
                text = if (selectedBounds != null && kartaPhotos.isEmpty() && !isSearching) {
                    "Selected area: ${selectedBounds.summary()}"
                } else {
                    statusText
                },
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.caption
            )

            Box(modifier = Modifier.weight(1f)) {
                OpenMapView(
                    mode = selectionMode,
                    selectedPoint = selectedPoint,
                    areaCorners = areaCorners,
                    onPointSelected = { point ->
                        selectedPoint = point
                        statusText = String.format(
                            Locale.US,
                            "Point selected: %.5f, %.5f",
                            point.latitude,
                            point.longitude
                        )
                    },
                    onAreaCornerSelected = { point ->
                        val newCorners = if (areaCorners.size >= 2) listOf(point) else areaCorners + point
                        areaCorners = newCorners
                        kartaPhotos = emptyList()
                        selectedPhotoIds = emptySet()
                        statusText = when (newCorners.size) {
                            1 -> "First corner selected. Tap the opposite corner."
                            2 -> "Area selected: ${boundsFrom(newCorners[0], newCorners[1]).summary()}. Tap Find Open Images."
                            else -> "Area mode"
                        }
                    }
                )
            }

            Divider(thickness = 1.dp)
            KartaPhotoPanel(
                photos = kartaPhotos,
                selectedPhotoIds = selectedPhotoIds,
                isDownloading = isDownloading,
                onToggle = { photoId, isSelected ->
                    selectedPhotoIds = selectedPhotoIds.toMutableSet().apply {
                        if (isSelected) add(photoId) else remove(photoId)
                    }
                },
                onSelectAll = { selectedPhotoIds = kartaPhotos.mapTo(linkedSetOf()) { it.id } },
                onClearSelection = { selectedPhotoIds = emptySet() },
                onDownloadSelected = {
                    scope.launch {
                        isDownloading = true
                        try {
                            val exportResult = KartaViewExporter.exportSelected(
                                context = context,
                                photos = selectedPhotos,
                                onProgress = { progress -> statusText = progress }
                            )
                            statusText = buildString {
                                append("Saved ${exportResult.downloadedCount} images")
                                if (exportResult.failedCount > 0) append("; ${exportResult.failedCount} failed")
                                append(". JSON and CSV manifests are in Downloads/KartaView.")
                            }
                        } catch (error: Exception) {
                            statusText = "Download failed: ${error.message ?: "network error"}"
                        } finally {
                            isDownloading = false
                        }
                    }
                }
            )
        }
    }

    @Composable
    private fun ColumnScope.KartaPhotoPanel(
        photos: List<KartaPhoto>,
        selectedPhotoIds: Set<String>,
        isDownloading: Boolean,
        onToggle: (String, Boolean) -> Unit,
        onSelectAll: () -> Unit,
        onClearSelection: () -> Unit,
        onDownloadSelected: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Open imagery from KartaView",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = KARTAVIEW_ATTRIBUTION,
                style = MaterialTheme.typography.caption
            )

            if (photos.isEmpty()) {
                Text(
                    text = "Draw an area and tap Find Open Images. Available frames will appear here for selection and download.",
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.body2
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(enabled = !isDownloading, onClick = onSelectAll) { Text("Select all") }
                TextButton(enabled = !isDownloading, onClick = onClearSelection) { Text("Clear") }
                Button(
                    enabled = selectedPhotoIds.isNotEmpty() && !isDownloading,
                    onClick = onDownloadSelected
                ) {
                    Text(if (isDownloading) "Saving…" else "Save ${selectedPhotoIds.size}")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(photos, key = { it.id }) { photo ->
                    KartaPhotoRow(
                        photo = photo,
                        isSelected = photo.id in selectedPhotoIds,
                        isEnabled = !isDownloading,
                        onToggle = { checked -> onToggle(photo.id, checked) }
                    )
                }
            }
        }
    }

    @Composable
    private fun KartaPhotoRow(
        photo: KartaPhoto,
        isSelected: Boolean,
        isEnabled: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clickable(enabled = isEnabled) { onToggle(!isSelected) },
            elevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onToggle,
                    enabled = isEnabled
                )
                Column {
                    Text("Frame ${photo.id}", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.6f, %.6f · %s",
                            photo.latitude,
                            photo.longitude,
                            photo.capturedAt ?: "Unknown capture date"
                        ),
                        style = MaterialTheme.typography.caption
                    )
                    Text(
                        text = "Heading: ${photo.headingDegrees?.let { String.format(Locale.US, "%.0f°", it) } ?: "Unknown"} · ${photo.projection ?: "Unknown projection"}",
                        style = MaterialTheme.typography.caption
                    )
                }
            }
        }
    }

    private fun boundsFrom(firstCorner: LocationPoint, secondCorner: LocationPoint): AreaBounds = AreaBounds(
        minLatitude = minOf(firstCorner.latitude, secondCorner.latitude),
        maxLatitude = maxOf(firstCorner.latitude, secondCorner.latitude),
        minLongitude = minOf(firstCorner.longitude, secondCorner.longitude),
        maxLongitude = maxOf(firstCorner.longitude, secondCorner.longitude)
    )

    @Composable
    private fun OpenMapView(
        mode: SelectionMode,
        selectedPoint: LocationPoint,
        areaCorners: List<LocationPoint>,
        onPointSelected: (LocationPoint) -> Unit,
        onAreaCornerSelected: (LocationPoint) -> Unit
    ) {
        val currentMode by rememberUpdatedState(mode)
        val currentOnPointSelected by rememberUpdatedState(onPointSelected)
        val currentOnAreaCornerSelected by rememberUpdatedState(onAreaCornerSelected)
        var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
        var isStyleReady by remember { mutableStateOf(false) }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    this@MainActivity.mapView = this
                    onStart()
                    onResume()
                    getMapAsync { map ->
                        map.addOnMapClickListener { point ->
                            val location = LocationPoint(point.latitude, point.longitude)
                            if (currentMode == SelectionMode.POINT) {
                                currentOnPointSelected(location)
                            } else {
                                currentOnAreaCornerSelected(location)
                            }
                            true
                        }
                        map.setStyle(OPEN_FREE_MAP_STYLE_URL) {
                            mapLibreMap = map
                            isStyleReady = true
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(INITIAL_LATITUDE, INITIAL_LONGITUDE))
                                .zoom(15.0)
                                .build()
                        }
                    }
                }
            },
            update = {
                if (isStyleReady) {
                    mapLibreMap?.let { map -> renderSelection(map, mode, selectedPoint, areaCorners) }
                }
            }
        )
    }

    private fun renderSelection(
        map: MapLibreMap,
        mode: SelectionMode,
        selectedPoint: LocationPoint,
        areaCorners: List<LocationPoint>
    ) {
        map.clear()
        if (mode == SelectionMode.POINT) {
            map.addMarker(MarkerOptions().position(selectedPoint.toLatLng()))
            return
        }

        areaCorners.forEach { corner ->
            map.addMarker(MarkerOptions().position(corner.toLatLng()))
        }
        if (areaCorners.size == 2) {
            val first = areaCorners[0]
            val second = areaCorners[1]
            map.addPolygon(
                PolygonOptions()
                    .addAll(
                        listOf(
                            LatLng(first.latitude, first.longitude),
                            LatLng(first.latitude, second.longitude),
                            LatLng(second.latitude, second.longitude),
                            LatLng(second.latitude, first.longitude)
                        )
                    )
                    .fillColor(AndroidColor.argb(50, 51, 102, 255))
                    .strokeColor(AndroidColor.rgb(35, 85, 215))
            )
        }
    }

    private fun LocationPoint.toLatLng() = LatLng(latitude, longitude)
}
