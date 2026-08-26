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
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

class MainActivity : AppCompatActivity() {

    private companion object {
        const val OPEN_FREE_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val INITIAL_LATITUDE = 51.5074
        const val INITIAL_LONGITUDE = -0.1278
    }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        MapLibre.getInstance(this)
        setContent {
            MaterialTheme(
                colors = darkColors(
                    primary = Color(0xFF7B2CFF),
                    primaryVariant = Color(0xFF5516B8),
                    secondary = Color(0xFF03DAC5),
                    background = Color(0xFF101010),
                    surface = Color(0xFF1A1A1A),
                    onPrimary = Color.White,
                    onSecondary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    OpenImageryApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    @Composable
    private fun OpenImageryApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var selectionMode by remember { mutableStateOf(SelectionMode.POINT) }
        var selectedPoint by remember { mutableStateOf(LocationPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE)) }
        var areaCorners by remember { mutableStateOf<List<LocationPoint>>(emptyList()) }
        var selectedProvider by remember { mutableStateOf(ImageryProvider.KARTAVIEW) }
        var mapillaryClientToken by remember { mutableStateOf(BuildConfig.MAPILLARY_TOKEN) }
        var streetImages by remember { mutableStateOf<List<StreetImage>>(emptyList()) }
        var selectedImageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var cityName by remember { mutableStateOf("") }
        var statusText by remember { mutableStateOf("Tap Select Area, then choose an imagery provider.") }
        var isSearching by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        val selectedBounds = remember(areaCorners) {
            if (areaCorners.size == 2) boundsFrom(areaCorners[0], areaCorners[1]) else null
        }
        val selectedImages = remember(streetImages, selectedImageIds) {
            streetImages.filter { it.stableId in selectedImageIds }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (selectionMode == SelectionMode.AREA) {
                            selectionMode = SelectionMode.POINT
                            areaCorners = emptyList()
                            statusText = "Area selection cancelled. Tap Select Area to begin again."
                        } else {
                            selectionMode = SelectionMode.AREA
                            areaCorners = emptyList()
                            streetImages = emptyList()
                            selectedImageIds = emptySet()
                            statusText = "Step 1 of 2: tap the first corner of the area on the map."
                        }
                    }
                ) {
                    Text(if (selectionMode == SelectionMode.AREA) "Cancel Area" else "Select Area")
                }
                if (selectionMode == SelectionMode.AREA && areaCorners.isNotEmpty()) {
                    TextButton(
                        enabled = !isSearching && !isDownloading,
                        onClick = {
                            areaCorners = emptyList()
                            streetImages = emptyList()
                            selectedImageIds = emptySet()
                            statusText = "Step 1 of 2: tap the first corner of the area on the map."
                        }
                    ) { Text("Clear corners") }
                }
            }

            ProviderSelector(
                selectedProvider = selectedProvider,
                mapillaryClientToken = mapillaryClientToken,
                onProviderSelected = {
                    selectedProvider = it
                    streetImages = emptyList()
                    selectedImageIds = emptySet()
                },
                onMapillaryTokenChanged = { mapillaryClientToken = it }
            )

            if (selectedBounds != null) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    enabled = !isSearching && !isDownloading,
                    onClick = {
                        val providerForSearch = selectedProvider
                        val tokenForSearch = mapillaryClientToken.trim()
                        scope.launch {
                            isSearching = true
                            streetImages = emptyList()
                            selectedImageIds = emptySet()
                            statusText = "Searching ${providerForSearch.displayName} coverage inside the selected area…"
                            try {
                                val results = withContext(Dispatchers.IO) {
                                    when (providerForSearch) {
                                        ImageryProvider.KARTAVIEW -> KartaViewClient.searchArea(
                                            minLatitude = selectedBounds.minLatitude,
                                            maxLatitude = selectedBounds.maxLatitude,
                                            minLongitude = selectedBounds.minLongitude,
                                            maxLongitude = selectedBounds.maxLongitude
                                        )
                                        ImageryProvider.MAPILLARY -> MapillaryClient.searchArea(
                                            accessToken = tokenForSearch,
                                            minLatitude = selectedBounds.minLatitude,
                                            maxLatitude = selectedBounds.maxLatitude,
                                            minLongitude = selectedBounds.minLongitude,
                                            maxLongitude = selectedBounds.maxLongitude
                                        )
                                    }
                                }
                                streetImages = results
                                statusText = if (results.isEmpty()) {
                                    "No ${providerForSearch.displayName} frames were returned in this area. Try the other provider or a different area."
                                } else {
                                    "${providerForSearch.displayName} returned ${results.size} frames. Select the images you want to save."
                                }
                            } catch (error: ProviderSearchException) {
                                statusText = error.message ?: "${providerForSearch.displayName} search failed."
                            } catch (error: Exception) {
                                statusText = "${providerForSearch.displayName} search failed: ${error.message ?: "network error"}"
                            } finally {
                                isSearching = false
                            }
                        }
                    }
                ) {
                    Text(if (isSearching) "Searching…" else "Find ${selectedProvider.displayName} Images")
                }
            }

            Text(
                text = statusText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.body2
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
                        streetImages = emptyList()
                        selectedImageIds = emptySet()
                        statusText = when (newCorners.size) {
                            1 -> "Step 2 of 2: tap the opposite corner of the area."
                            2 -> "Area ready: ${boundsFrom(newCorners[0], newCorners[1]).summary()}. Tap Find ${selectedProvider.displayName} Images."
                            else -> "Area mode"
                        }
                    }
                )
            }

            Divider(thickness = 1.dp)
            StreetImagePanel(
                provider = selectedProvider,
                images = streetImages,
                selectedImageIds = selectedImageIds,
                cityName = cityName,
                isDownloading = isDownloading,
                onCityNameChanged = { cityName = it },
                onToggle = { imageId, isSelected ->
                    selectedImageIds = selectedImageIds.toMutableSet().apply {
                        if (isSelected) add(imageId) else remove(imageId)
                    }
                },
                onSelectAll = { selectedImageIds = streetImages.mapTo(linkedSetOf()) { it.stableId } },
                onClearSelection = { selectedImageIds = emptySet() },
                onDownloadSelected = {
                    scope.launch {
                        isDownloading = true
                        try {
                            val exportResult = StreetImageExporter.exportSelected(
                                context = context,
                                images = selectedImages,
                                cityName = cityName,
                                onProgress = { progress -> statusText = progress }
                            )
                            statusText = buildString {
                                append("Saved ${exportResult.downloadedCount} images")
                                if (exportResult.failedCount > 0) append("; ${exportResult.failedCount} failed")
                                append(". Files and metadata.json for GeoSpy are in ${exportResult.exportPath}.")
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
    private fun ProviderSelector(
        selectedProvider: ImageryProvider,
        mapillaryClientToken: String,
        onProviderSelected: (ImageryProvider) -> Unit,
        onMapillaryTokenChanged: (String) -> Unit
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Text("Imagery provider", style = MaterialTheme.typography.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ImageryProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier.clickable { onProviderSelected(provider) },
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        RadioButton(
                            selected = selectedProvider == provider,
                            onClick = { onProviderSelected(provider) }
                        )
                        Text(
                            text = provider.displayName,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            }
            if (selectedProvider == ImageryProvider.MAPILLARY) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = mapillaryClientToken,
                    onValueChange = onMapillaryTokenChanged,
                    singleLine = true,
                    label = { Text("Mapillary client token") },
                    placeholder = { Text("Create a client token in Mapillary's developer dashboard") }
                )
                Text(
                    text = "The token is used only for this session and is not saved in the app.",
                    style = MaterialTheme.typography.caption
                )
            } else {
                Text(
                    text = "KartaView is public and needs no API key. Large areas are automatically split into valid searches.",
                    style = MaterialTheme.typography.caption
                )
            }
        }
    }

    @Composable
    private fun ColumnScope.StreetImagePanel(
        provider: ImageryProvider,
        images: List<StreetImage>,
        selectedImageIds: Set<String>,
        cityName: String,
        isDownloading: Boolean,
        onCityNameChanged: (String) -> Unit,
        onToggle: (String, Boolean) -> Unit,
        onSelectAll: () -> Unit,
        onClearSelection: () -> Unit,
        onDownloadSelected: () -> Unit
    ) {
        Column(
            modifier = (if (images.isEmpty()) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            }).padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Open imagery from ${provider.displayName}",
                style = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${provider.attribution} · ${provider.license}",
                style = MaterialTheme.typography.caption
            )

            if (images.isEmpty()) {
                Text(
                    text = "Draw an area, choose a provider, and tap Find Images. Available frames will appear here for selection and download.",
                    modifier = Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.body2
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    value = cityName,
                    onValueChange = onCityNameChanged,
                    label = { Text("City name (required)") },
                    singleLine = true,
                    enabled = !isDownloading
                )
                Button(
                    modifier = Modifier.padding(top = 8.dp),
                    enabled = selectedImageIds.isNotEmpty() && cityName.isNotBlank() && !isDownloading,
                    onClick = onDownloadSelected
                ) {
                    Text(if (isDownloading) "Saving…" else "Save ${selectedImageIds.size}")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                TextButton(enabled = !isDownloading, onClick = onSelectAll) { Text("Select all") }
                TextButton(enabled = !isDownloading, onClick = onClearSelection) { Text("Clear") }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(images, key = { it.stableId }) { image ->
                    StreetImageRow(
                        image = image,
                        isSelected = image.stableId in selectedImageIds,
                        isEnabled = !isDownloading,
                        onToggle = { checked -> onToggle(image.stableId, checked) }
                    )
                }
            }
        }
    }

    @Composable
    private fun StreetImageRow(
        image: StreetImage,
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
                    Text(
                        text = "${image.provider.displayName} · ${image.id}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.6f, %.6f · %s",
                            image.latitude,
                            image.longitude,
                            image.capturedAt ?: "Unknown capture date"
                        ),
                        style = MaterialTheme.typography.caption
                    )
                    Text(
                        text = "Heading: ${image.headingDegrees?.let { String.format(Locale.US, "%.0f°", it) } ?: "Unknown"} · ${image.projection ?: "Unknown projection"}",
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
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val currentMode by rememberUpdatedState(mode)
        val currentOnPointSelected by rememberUpdatedState(onPointSelected)
        val currentOnAreaCornerSelected by rememberUpdatedState(onAreaCornerSelected)
        var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
        var isStyleReady by remember { mutableStateOf(false) }

        val mapView = remember {
            val options = MapLibreMapOptions.createFromAttributes(context, null)
                .textureMode(true) // Crucial for modern Xiaomi/High-refresh screens
            MapView(context, options)
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
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
