package com.example.scraper

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
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
        const val INITIAL_LATITUDE = 53.5809 // Scunthorpe, UK
        const val INITIAL_LONGITUDE = -0.6502 // Scunthorpe, UK
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
    enum class ViewMode { GRID, LIST }

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

    @Composable
    private fun OpenImageryApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var activeTab by remember { mutableStateOf(0) } // 0 = Map & Search, 1 = Images Gallery
        var viewMode by remember { mutableStateOf(ViewMode.GRID) }
        var selectionMode by remember { mutableStateOf(SelectionMode.POINT) }
        var selectedPoint by remember { mutableStateOf(LocationPoint(INITIAL_LATITUDE, INITIAL_LONGITUDE)) }
        var areaCorners by remember { mutableStateOf<List<LocationPoint>>(emptyList()) }
        var selectedProvider by remember { mutableStateOf(ImageryProvider.KARTAVIEW) }
        var mapillaryClientToken by remember { mutableStateOf(BuildConfig.MAPILLARY_TOKEN) }
        var streetViewApiKey by remember { mutableStateOf(BuildConfig.GOOGLE_STREETVIEW_KEY) }
        var streetViewSpacingText by remember { mutableStateOf("50") }
        var streetImages by remember { mutableStateOf<List<StreetImage>>(emptyList()) }
        var selectedImageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var savedImageFileNames by remember { mutableStateOf<Set<String>>(emptySet()) }
        var previewImageIndex by remember { mutableStateOf<Int?>(null) }
        var cityName by remember { mutableStateOf("") }
        var statusText by remember { mutableStateOf("Tap Select Area, then choose an imagery provider.") }
        var isSearching by remember { mutableStateOf(false) }
        var isDownloading by remember { mutableStateOf(false) }
        var downloadCurrent by remember { mutableIntStateOf(0) }
        var downloadTotal by remember { mutableIntStateOf(0) }

        // Automatically scan previously exported files when new search results arrive or tab opens
        LaunchedEffect(streetImages, activeTab) {
            if (streetImages.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val saved = StreetImageExporter.getSavedImageFileNames(context)
                    withContext(Dispatchers.Main) {
                        savedImageFileNames = saved
                        // Auto-select ONLY unsaved images by default
                        if (selectedImageIds.isEmpty()) {
                            selectedImageIds = streetImages
                                .filter { it.fileName !in saved }
                                .mapTo(linkedSetOf()) { it.stableId }
                        }
                    }
                }
            }
        }

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
            // Main Navigation Tabs
            TabRow(
                selectedTabIndex = activeTab,
                backgroundColor = MaterialTheme.colors.surface,
                contentColor = MaterialTheme.colors.primary
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = {
                        Text(
                            text = "Map & Search",
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Images Gallery",
                                fontWeight = FontWeight.Bold
                            )
                            if (streetImages.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colors.primary
                                ) {
                                    Text(
                                        text = "${streetImages.size}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.caption,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                )
            }

            // Tab 0: Map & Search controls
            if (activeTab == 0) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        streetViewApiKey = streetViewApiKey,
                        streetViewSpacingText = streetViewSpacingText,
                        onProviderSelected = {
                            selectedProvider = it
                            streetImages = emptyList()
                            selectedImageIds = emptySet()
                        },
                        onMapillaryTokenChanged = { mapillaryClientToken = it },
                        onStreetViewKeyChanged = { streetViewApiKey = it },
                        onStreetViewSpacingChanged = { streetViewSpacingText = it }
                    )

                    if (selectedBounds != null) {
                        Button(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
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
                                                ImageryProvider.GOOGLE_STREETVIEW -> GoogleStreetViewClient.searchArea(
                                                    apiKey = streetViewApiKey.trim(),
                                                    minLatitude = selectedBounds.minLatitude,
                                                    maxLatitude = selectedBounds.maxLatitude,
                                                    minLongitude = selectedBounds.minLongitude,
                                                    maxLongitude = selectedBounds.maxLongitude,
                                                    spacingMeters = streetViewSpacingText.toDoubleOrNull() ?: 50.0
                                                )
                                            }
                                        }
                                        streetImages = results
                                        if (results.isEmpty()) {
                                            statusText = "No ${providerForSearch.displayName} frames were returned in this area. Try the other provider or a different area."
                                        } else {
                                            statusText = "Found ${results.size} ${providerForSearch.displayName} images! Switch to Images tab to view and select."
                                            activeTab = 1 // Auto-switch to Gallery view!
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
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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

                        // Floating banner if images are ready
                        if (streetImages.isNotEmpty()) {
                            Card(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(12.dp)
                                    .clickable { activeTab = 1 },
                                backgroundColor = MaterialTheme.colors.primaryVariant,
                                elevation = 6.dp,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "${streetImages.size} images found (${selectedImageIds.size} selected)",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Tap to view full gallery and select images",
                                            style = MaterialTheme.typography.caption,
                                            color = Color.White.copy(alpha = 0.8f)
                                        )
                                    }
                                    Button(
                                        onClick = { activeTab = 1 },
                                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary)
                                    ) {
                                        Text("View Images", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Tab 1: Images Gallery Tab
                ImagesGalleryTab(
                    provider = selectedProvider,
                    images = streetImages,
                    selectedImageIds = selectedImageIds,
                    savedImageFileNames = savedImageFileNames,
                    cityName = cityName,
                    isDownloading = isDownloading,
                    downloadCurrent = downloadCurrent,
                    downloadTotal = downloadTotal,
                    viewMode = viewMode,
                    onViewModeChanged = { viewMode = it },
                    onCityNameChanged = { cityName = it },
                    onToggle = { imageId, isSelected ->
                        selectedImageIds = selectedImageIds.toMutableSet().apply {
                            if (isSelected) add(imageId) else remove(imageId)
                        }
                    },
                    onSelectUnsaved = {
                        selectedImageIds = streetImages
                            .filter { it.fileName !in savedImageFileNames }
                            .mapTo(linkedSetOf()) { it.stableId }
                    },
                    onSelectAll = { selectedImageIds = streetImages.mapTo(linkedSetOf()) { it.stableId } },
                    onClearSelection = { selectedImageIds = emptySet() },
                    onPreviewRequested = { image ->
                        val index = streetImages.indexOf(image)
                        if (index >= 0) previewImageIndex = index
                    },
                    onDownloadSelected = {
                        scope.launch {
                            isDownloading = true
                            downloadCurrent = 0
                            downloadTotal = selectedImages.size
                            try {
                                val exportResult = StreetImageExporter.exportSelected(
                                    context = context,
                                    images = selectedImages,
                                    cityName = cityName,
                                    onProgress = { current, total, progressText ->
                                        downloadCurrent = current
                                        downloadTotal = total
                                        statusText = progressText
                                    }
                                )
                                val saved = withContext(Dispatchers.IO) { StreetImageExporter.getSavedImageFileNames(context) }
                                savedImageFileNames = saved
                                // Deselect images that were just saved
                                selectedImageIds = selectedImageIds.filterTo(linkedSetOf()) { id ->
                                    val img = streetImages.find { it.stableId == id }
                                    img != null && img.fileName !in saved
                                }
                                statusText = buildString {
                                    append("Saved ${exportResult.downloadedCount} images")
                                    if (exportResult.failedCount > 0) append("; ${exportResult.failedCount} failed")
                                    append(". Files and metadata.json for GeoSpy are in ${exportResult.exportPath}.")
                                    if (selectedProvider == ImageryProvider.GOOGLE_STREETVIEW) {
                                        append(" Each saved Google image counts toward the Street View API free quota.")
                                    }
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

            previewImageIndex?.let { index ->
                ImagePreviewDialog(
                    images = streetImages,
                    currentIndex = index,
                    selectedImageIds = selectedImageIds,
                    onToggle = { id, checked ->
                        selectedImageIds = selectedImageIds.toMutableSet().apply {
                            if (checked) add(id) else remove(id)
                        }
                    },
                    onIndexChanged = { newIdx -> previewImageIndex = newIdx },
                    onDismiss = { previewImageIndex = null }
                )
            }
        }
    }

    @Composable
    private fun ImagesGalleryTab(
        provider: ImageryProvider,
        images: List<StreetImage>,
        selectedImageIds: Set<String>,
        savedImageFileNames: Set<String>,
        cityName: String,
        isDownloading: Boolean,
        downloadCurrent: Int,
        downloadTotal: Int,
        viewMode: ViewMode,
        onViewModeChanged: (ViewMode) -> Unit,
        onCityNameChanged: (String) -> Unit,
        onToggle: (String, Boolean) -> Unit,
        onSelectUnsaved: () -> Unit,
        onSelectAll: () -> Unit,
        onClearSelection: () -> Unit,
        onPreviewRequested: (StreetImage) -> Unit,
        onDownloadSelected: () -> Unit
    ) {
        if (images.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No images found yet",
                        style = MaterialTheme.typography.h6,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Go to the 'Map & Search' tab, select an area on the map, and tap Find Images to locate available street imagery.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            return
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar in Gallery tab
            Surface(
                elevation = 3.dp,
                color = MaterialTheme.colors.surface
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${provider.displayName} Imagery",
                                style = MaterialTheme.typography.subtitle1,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = "${selectedImageIds.size} of ${images.size} selected",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.secondary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // View Mode Switcher (Grid / List)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onViewModeChanged(ViewMode.GRID) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    backgroundColor = if (viewMode == ViewMode.GRID) MaterialTheme.colors.primary else Color.Transparent,
                                    contentColor = if (viewMode == ViewMode.GRID) Color.White else MaterialTheme.colors.onSurface
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Grid", style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onViewModeChanged(ViewMode.LIST) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    backgroundColor = if (viewMode == ViewMode.LIST) MaterialTheme.colors.primary else Color.Transparent,
                                    contentColor = if (viewMode == ViewMode.LIST) Color.White else MaterialTheme.colors.onSurface
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("List", style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val unsavedCount = images.count { it.fileName !in savedImageFileNames }
                            if (savedImageFileNames.isNotEmpty() && unsavedCount in 1 until images.size) {
                                TextButton(
                                    enabled = !isDownloading,
                                    onClick = onSelectUnsaved,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Select Unsaved ($unsavedCount)")
                                }
                            }
                            TextButton(
                                enabled = !isDownloading,
                                onClick = onSelectAll,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Select All (${images.size})")
                            }
                            TextButton(
                                enabled = !isDownloading && selectedImageIds.isNotEmpty(),
                                onClick = onClearSelection,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Clear")
                            }
                        }
                        Text(
                            text = provider.license,
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Image Gallery content area
            Box(modifier = Modifier.weight(1f)) {
                if (viewMode == ViewMode.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(images, key = { it.stableId }) { image ->
                            StreetImageGridCard(
                                image = image,
                                isSelected = image.stableId in selectedImageIds,
                                isSaved = image.fileName in savedImageFileNames,
                                isEnabled = !isDownloading,
                                onToggle = { checked -> onToggle(image.stableId, checked) },
                                onPreviewRequested = { onPreviewRequested(image) }
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(images, key = { it.stableId }) { image ->
                            StreetImageListCard(
                                image = image,
                                isSelected = image.stableId in selectedImageIds,
                                isSaved = image.fileName in savedImageFileNames,
                                isEnabled = !isDownloading,
                                onToggle = { checked -> onToggle(image.stableId, checked) },
                                onPreviewRequested = { onPreviewRequested(image) }
                            )
                        }
                    }
                }
            }

            // Bottom Action & Export Bar
            Surface(
                elevation = 8.dp,
                color = MaterialTheme.colors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isDownloading) {
                        val progress = if (downloadTotal > 0) downloadCurrent.toFloat() / downloadTotal else 0f
                        val percentage = (progress * 100).toInt()
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (downloadTotal > 0) "Saving $downloadCurrent of $downloadTotal images…" else "Saving images…",
                                    style = MaterialTheme.typography.body2,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colors.primary
                                )
                                if (downloadTotal > 0) {
                                    Text(
                                        text = "$percentage%",
                                        style = MaterialTheme.typography.body2,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colors.primary
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = MaterialTheme.colors.primary,
                                backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.24f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = cityName,
                            onValueChange = onCityNameChanged,
                            label = { Text("City name (required)") },
                            singleLine = true,
                            enabled = !isDownloading
                        )
                        Button(
                            modifier = Modifier.height(54.dp),
                            enabled = selectedImageIds.isNotEmpty() && cityName.isNotBlank() && !isDownloading,
                            onClick = onDownloadSelected
                        ) {
                            Text(
                                if (isDownloading) "Saving…" else "Save ${selectedImageIds.size} Images",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StreetImageGridCard(
        image: StreetImage,
        isSelected: Boolean,
        isSaved: Boolean,
        isEnabled: Boolean,
        onToggle: (Boolean) -> Unit,
        onPreviewRequested: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = isEnabled) { onPreviewRequested() },
            elevation = 4.dp,
            backgroundColor = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.2f) else MaterialTheme.colors.surface
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                ) {
                    val context = LocalContext.current
                    val previewUrl = image.thumbnailUrl
                    if (!previewUrl.isNullOrBlank()) {
                        val imageRequest = remember(previewUrl) {
                            ImageRequest.Builder(context)
                                .data(previewUrl)
                                .crossfade(true)
                                .build()
                        }
                        SubcomposeAsyncImage(
                            model = imageRequest,
                            contentDescription = "Image Preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        ) {
                            when (painter.state) {
                                is AsyncImagePainter.State.Loading -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                                is AsyncImagePainter.State.Error -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF2A2A2A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Preview\nUnavailable",
                                            style = MaterialTheme.typography.caption,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                else -> {
                                    SubcomposeAsyncImageContent()
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF222222)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text(
                                    text = image.sequenceId ?: image.provider.displayName,
                                    style = MaterialTheme.typography.caption,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colors.primary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Tap to preview",
                                    style = MaterialTheme.typography.caption,
                                    color = Color.LightGray,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    // Checkbox in top right
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.65f)
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = onToggle,
                            enabled = isEnabled
                        )
                    }

                    // Saved badge or Heading degree pill in bottom left
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isSaved) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF2E7D32) // Green badge
                            ) {
                                Text(
                                    text = "✓ Saved",
                                    color = Color.White,
                                    style = MaterialTheme.typography.caption,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        image.headingDegrees?.let { heading ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color.Black.copy(alpha = 0.75f)
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.0f°", heading),
                                    color = Color.White,
                                    style = MaterialTheme.typography.caption,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = String.format(Locale.US, "%.5f, %.5f", image.latitude, image.longitude),
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = image.capturedAt ?: "Unknown capture date",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                }
            }
        }
    }

    @Composable
    private fun StreetImageListCard(
        image: StreetImage,
        isSelected: Boolean,
        isSaved: Boolean,
        isEnabled: Boolean,
        onToggle: (Boolean) -> Unit,
        onPreviewRequested: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = isEnabled) { onPreviewRequested() },
            elevation = 3.dp,
            shape = RoundedCornerShape(10.dp),
            backgroundColor = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.2f) else MaterialTheme.colors.surface
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = onToggle,
                    enabled = isEnabled
                )
                val context = LocalContext.current
                val previewUrl = image.thumbnailUrl
                if (!previewUrl.isNullOrBlank()) {
                    val imageRequest = remember(previewUrl) {
                        ImageRequest.Builder(context)
                            .data(previewUrl)
                            .crossfade(true)
                            .build()
                    }
                    SubcomposeAsyncImage(
                        model = imageRequest,
                        contentDescription = "Image Preview",
                        modifier = Modifier
                            .size(110.dp, 80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPreviewRequested() },
                        contentScale = ContentScale.Crop
                    ) {
                        when (painter.state) {
                            is AsyncImagePainter.State.Loading -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                            is AsyncImagePainter.State.Error -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF2A2A2A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No Image", style = MaterialTheme.typography.caption, color = Color.Gray)
                                }
                            }
                            else -> {
                                SubcomposeAsyncImageContent()
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(110.dp, 80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF222222))
                            .clickable { onPreviewRequested() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = "Street View",
                                style = MaterialTheme.typography.caption,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colors.primary,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Tap to view",
                                style = MaterialTheme.typography.overline,
                                color = Color.LightGray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${image.provider.displayName} · ${image.sequenceId ?: image.id}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.body2,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isSaved) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF2E7D32)
                            ) {
                                Text(
                                    text = "✓ Saved",
                                    color = Color.White,
                                    style = MaterialTheme.typography.caption,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.6f, %.6f",
                            image.latitude,
                            image.longitude
                        ),
                        style = MaterialTheme.typography.caption
                    )
                    Text(
                        text = "Heading: ${image.headingDegrees?.let { String.format(Locale.US, "%.0f°", it) } ?: "N/A"} · ${image.capturedAt ?: "Unknown date"}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    @Composable
    private fun ImagePreviewDialog(
        images: List<StreetImage>,
        currentIndex: Int,
        selectedImageIds: Set<String>,
        onToggle: (String, Boolean) -> Unit,
        onIndexChanged: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        if (currentIndex !in images.indices) return
        val image = images[currentIndex]
        val isSelected = image.stableId in selectedImageIds

        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(16.dp),
                backgroundColor = MaterialTheme.colors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${image.provider.displayName} (${currentIndex + 1}/${images.size})",
                            style = MaterialTheme.typography.subtitle1,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val context = LocalContext.current
                        val imageRequest = remember(image.imageUrl) {
                            ImageRequest.Builder(context)
                                .data(image.imageUrl)
                                .crossfade(true)
                                .build()
                        }
                        SubcomposeAsyncImage(
                            model = imageRequest,
                            contentDescription = "Full Image Preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        ) {
                            when (painter.state) {
                                is AsyncImagePainter.State.Loading -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(36.dp),
                                            strokeWidth = 3.dp
                                        )
                                    }
                                }
                                is AsyncImagePainter.State.Error -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(0xFF2A2A2A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "Full Preview Unavailable",
                                                style = MaterialTheme.typography.body2,
                                                color = Color.Gray
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "Check API key or network connection",
                                                style = MaterialTheme.typography.caption,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    SubcomposeAsyncImageContent()
                                }
                            }
                        }

                        // Previous button
                        if (currentIndex > 0) {
                            Button(
                                onClick = { onIndexChanged(currentIndex - 1) },
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .padding(4.dp)
                                    .size(40.dp),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.65f))
                            ) {
                                Text("<", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Next button
                        if (currentIndex < images.size - 1) {
                            Button(
                                onClick = { onIndexChanged(currentIndex + 1) },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(4.dp)
                                    .size(40.dp),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = PaddingValues(0.dp),
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black.copy(alpha = 0.65f))
                            ) {
                                Text(">", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = String.format(
                            Locale.US,
                            "GPS: %.6f, %.6f | Heading: %.0f°\nCaptured: %s",
                            image.latitude,
                            image.longitude,
                            image.headingDegrees ?: 0.0,
                            image.capturedAt ?: "Unknown"
                        ),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onToggle(image.stableId, !isSelected) },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isSelected) MaterialTheme.colors.primaryVariant else MaterialTheme.colors.primary
                            )
                        ) {
                            Text(if (isSelected) "✓ Selected for Download" else "+ Select for Download")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ProviderSelector(
        selectedProvider: ImageryProvider,
        mapillaryClientToken: String,
        streetViewApiKey: String,
        streetViewSpacingText: String,
        onProviderSelected: (ImageryProvider) -> Unit,
        onMapillaryTokenChanged: (String) -> Unit,
        onStreetViewKeyChanged: (String) -> Unit,
        onStreetViewSpacingChanged: (String) -> Unit
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            Text("Imagery provider", style = MaterialTheme.typography.caption)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ImageryProvider.entries.forEach { provider ->
                    Row(
                        modifier = Modifier.clickable { onProviderSelected(provider) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedProvider == provider,
                            onClick = { onProviderSelected(provider) }
                        )
                        Text(
                            text = provider.displayName,
                            maxLines = 1,
                            softWrap = false
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
                    label = { Text("Mapillary client token") }
                )
            } else if (selectedProvider == ImageryProvider.GOOGLE_STREETVIEW) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = streetViewApiKey,
                        onValueChange = onStreetViewKeyChanged,
                        singleLine = true,
                        label = { Text("Google API Key") }
                    )
                    OutlinedTextField(
                        modifier = Modifier.width(110.dp),
                        value = streetViewSpacingText,
                        onValueChange = onStreetViewSpacingChanged,
                        singleLine = true,
                        label = { Text("Spacing (m)") }
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
                mapView.onDestroy()
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
