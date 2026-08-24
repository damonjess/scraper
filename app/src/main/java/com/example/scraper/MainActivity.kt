package com.example.scraper

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import android.graphics.Color as AndroidColor

class MainActivity : AppCompatActivity() {

    data class PanoResult(val lat: Double, val lng: Double, val panoId: String)
    
    enum class SelectionMode { POINT, SQUARE }

    override fun onCreate(savedInstanceState: Bundle?) {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("osmdroid", MODE_PRIVATE)
        Configuration.getInstance().load(ctx, prefs)
        
        // Set User-Agent AFTER loading configuration to ensure it's not overwritten
        val uniqueUserAgent = "StreetViewScraper/1.0 (Android; contact@example.com) " + System.currentTimeMillis()
        Configuration.getInstance().userAgentValue = uniqueUserAgent
        
        super.onCreate(savedInstanceState)
        
        setContent {
            ScraperApp()
        }
    }

    @Composable
    fun ScraperApp() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        
        var selectionMode by remember { mutableStateOf(SelectionMode.POINT) }
        var selectedPoint by remember { mutableStateOf(GeoPoint(51.5074, -0.1278)) }
        var squarePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
        var indexedResults by remember { mutableStateOf<List<PanoResult>>(emptyList()) }
        
        var streetViewUrl by remember { mutableStateOf("") }
        var statusText by remember { mutableStateOf("Ready") }

        LaunchedEffect(selectedPoint) {
            if (selectionMode == SelectionMode.POINT) {
                streetViewUrl = "https://www.google.com/maps/@${selectedPoint.latitude},${selectedPoint.longitude},3a,75y,90t/data=!3m6!1e1"
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Control Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { 
                    selectionMode = if (selectionMode == SelectionMode.POINT) SelectionMode.SQUARE else SelectionMode.POINT 
                    squarePoints = emptyList()
                    indexedResults = emptyList()
                    statusText = "Mode: $selectionMode"
                }) {
                    Text(if (selectionMode == SelectionMode.POINT) "Switch to Square" else "Switch to Point")
                }
                
                if ((selectionMode == SelectionMode.SQUARE) && (squarePoints.size == 2)) {
                    Button(onClick = {
                        scope.launch(Dispatchers.IO) {
                            indexedResults = indexArea(squarePoints[0], squarePoints[1]) { progress ->
                                statusText = progress
                            }
                        }
                    }) {
                        Text("Index Area")
                    }
                }

                if (indexedResults.isNotEmpty()) {
                    Button(onClick = { savePointsToCSV(context, indexedResults) }) {
                        Text("Save CSV (${indexedResults.size})")
                    }
                }
            }

            Text(text = statusText, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.caption)

            // Map Section
            Box(modifier = Modifier.weight(1f)) {
                OSMMapView(
                    mode = selectionMode,
                    squarePoints = squarePoints,
                    onPointSelected = { selectedPoint = it },
                    onSquarePointAdded = { p ->
                        squarePoints = if (squarePoints.size >= 2) listOf(p) else squarePoints + p
                    }
                )
            }

            Divider(thickness = 2.dp)

            // Street View Section
            Box(modifier = Modifier.weight(1f)) {
                if (streetViewUrl.isNotEmpty()) {
                    StreetViewWebView(url = streetViewUrl)
                }
            }
        }
    }

    private suspend fun indexArea(p1: GeoPoint, p2: GeoPoint, onProgress: (String) -> Unit): List<PanoResult> {
        val foundPoints = mutableListOf<PanoResult>()
        val minLat = minOf(p1.latitude, p2.latitude)
        val maxLat = maxOf(p1.latitude, p2.latitude)
        val minLng = minOf(p1.longitude, p2.longitude)
        val maxLng = maxOf(p1.longitude, p2.longitude)

        val step = 0.0001 
        var checkedCount = 0

        val latSteps = ((maxLat - minLat) / step).toInt().coerceAtMost(20)
        val lngSteps = ((maxLng - minLng) / step).toInt().coerceAtMost(20)

        for (i in 0..latSteps) {
            for (j in 0..lngSteps) {
                val lat = minLat + (i * step)
                val lng = minLng + (j * step)
                checkedCount++
                
                try {
                    val url = "https://maps.googleapis.com/maps/api/streetview/metadata?location=$lat,$lng&key=" 
                    val response = Jsoup.connect(url).ignoreContentType(true).execute().body()
                    val json = JSONObject(response)
                    if (json.getString("status") == "OK") {
                        val panoId = json.getString("pano_id")
                        foundPoints.add(PanoResult(lat, lng, panoId))
                    }
                } catch (_: Exception) { /* Ignore */ }

                withContext(Dispatchers.Main) {
                    onProgress("Scanning: $checkedCount points... Found: ${foundPoints.size}")
                }
            }
        }
        withContext(Dispatchers.Main) {
            onProgress("Indexing Complete. Found ${foundPoints.size} panoramas.")
        }
        return foundPoints
    }

    private fun savePointsToCSV(context: Context, points: List<PanoResult>) {
        val csvHeader = "latitude,longitude,panorama_id\n"
        val csvContent = points.joinToString("\n") { "${it.lat},${it.lng},${it.panoId}" }
        val finalContent = csvHeader + csvContent
        
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "streetview_index_${System.currentTimeMillis()}.csv")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        try {
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    stream.write(finalContent.toByteArray())
                }
                Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_LONG).show()
            } ?: run {
                Toast.makeText(context, "Error creating file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    fun OSMMapView(
        mode: SelectionMode,
        squarePoints: List<GeoPoint>,
        onPointSelected: (GeoPoint) -> Unit,
        onSquarePointAdded: (GeoPoint) -> Unit
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                MapView(context).apply {
                    // Switching to WIKIMEDIA as it's often more permissive than MAPNIK
                    setTileSource(TileSourceFactory.WIKIMEDIA)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(51.5074, -0.1278))
                    
                    // Force cache clearing more aggressively
                    tileProvider.tileCache.clear()
                    
                    val receiver = object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            if (mode == SelectionMode.POINT) {
                                onPointSelected(p)
                                updateMarkers(this@apply, p)
                            } else {
                                onSquarePointAdded(p)
                            }
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    }
                    overlays.add(MapEventsOverlay(receiver))
                }
            },
            update = { view ->
                if (mode == SelectionMode.SQUARE) {
                    drawSquare(view, squarePoints)
                }
            }
        )
    }

    private fun updateMarkers(mapView: MapView, p: GeoPoint) {
        mapView.overlays.filterIsInstance<Marker>().forEach { mapView.overlays.remove(it) }
        val marker = Marker(mapView)
        marker.position = p
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    private fun drawSquare(mapView: MapView, points: List<GeoPoint>) {
        // Clear previous polygons
        mapView.overlays.filterIsInstance<Polygon>().forEach { mapView.overlays.remove(it) }
        
        if (points.size == 2) {
            val p1 = points[0]
            val p2 = points[1]
            
            val square = Polygon()
            square.fillPaint.color = AndroidColor.argb(50, 0, 0, 255) // Semi-transparent blue
            square.outlinePaint.color = AndroidColor.BLUE
            square.outlinePaint.strokeWidth = 2f
            
            val boxPoints = listOf(
                GeoPoint(p1.latitude, p1.longitude),
                GeoPoint(p1.latitude, p2.longitude),
                GeoPoint(p2.latitude, p2.longitude),
                GeoPoint(p2.latitude, p1.longitude)
            )
            square.points = boxPoints
            mapView.overlays.add(square)
        }
        mapView.invalidate()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun StreetViewWebView(url: String) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Use a standard mobile User-Agent
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                            val urlString = request?.url?.toString() ?: return false
                            // Prevent redirects to external apps (like intent://) which WebView can't handle
                            if (urlString.startsWith("intent://") || urlString.startsWith("market://")) {
                                return true // Block the redirect
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Inject JS to attempt auto-accepting Google's cookie consent and mobile app prompts
                            view?.evaluateJavascript("(function() { " +
                                    "var buttons = document.getElementsByTagName('button');" +
                                    "for (var i = 0; i < buttons.length; i++) {" +
                                    "  var text = buttons[i].innerText;" +
                                    "  if (text.indexOf('Accept all') !== -1 || text.indexOf('Keep using web') !== -1) {" +
                                    "    buttons[i].click();" +
                                    "  }" +
                                    "}" +
                                    "})()", null)
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { webView ->
                if (webView.url != url && !url.startsWith("intent://")) {
                    webView.loadUrl(url)
                }
            }
        )
    }
}
