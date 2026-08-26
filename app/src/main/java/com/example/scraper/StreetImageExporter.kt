package com.example.scraper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

data class StreetImageExportResult(
    val downloadedCount: Int,
    val failedCount: Int,
    val exportPath: String
)

private data class SavedStreetImage(
    val image: StreetImage,
    val fileName: String,
    val uri: Uri
)

object StreetImageExporter {
    private const val EXPORT_ROOT = "OpenStreetImages"

    suspend fun exportSelected(
        context: Context,
        images: List<StreetImage>,
        cityName: String,
        onProgress: (String) -> Unit
    ): StreetImageExportResult = withContext(Dispatchers.IO) {
        val exportId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedCity = cityName.trim()
            .replace(" ", "_")
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifEmpty { "Export" }
        // Save to Documents/OpenStreetImages/CityName/exportId
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$EXPORT_ROOT/$sanitizedCity/$exportId/"
        
        // Ensure .nomedia exists in the root folder to hide from gallery
        ensureNoMediaFile(context)

        val savedImages = mutableListOf<SavedStreetImage>()
        var failedCount = 0

        images.forEachIndexed { index, image ->
            withContext(Dispatchers.Main) {
                onProgress("Downloading ${index + 1} of ${images.size} selected images…")
            }
            val fileName = "${image.provider.shortName}_${image.id}.jpg"
            val savedUri = downloadImage(context, image, fileName, relativePath)
            if (savedUri == null) {
                failedCount++
            } else {
                savedImages += SavedStreetImage(image, fileName, savedUri)
            }
        }

        // 1. Generate standard manifest
        val jsonManifest = JSONObject().apply {
            put("createdAt", exportId)
            put("city", cityName)
            put("itemCount", savedImages.size)
            put("items", JSONArray().apply {
                savedImages.forEach { saved -> put(saved.toFullJson()) }
            })
        }
        writeTextFile(context, "manifest.json", relativePath, "application/json", jsonManifest.toString(2))

        // 2. Generate GeoSpy metadata.json (The specific format you requested)
        val geoSpyMetadata = JSONArray().apply {
            savedImages.forEach { saved -> put(saved.toGeoSpyJson()) }
        }
        writeTextFile(context, "metadata.json", relativePath, "application/json", geoSpyMetadata.toString(2))
        
        // 3. Generate CSV
        writeTextFile(context, "manifest.csv", relativePath, "text/csv", savedImages.toCsv())

        StreetImageExportResult(
            downloadedCount = savedImages.size,
            failedCount = failedCount,
            exportPath = "Documents/$EXPORT_ROOT/$sanitizedCity/$exportId"
        )
    }

    private fun SavedStreetImage.toFullJson(): JSONObject = JSONObject().apply {
        put("provider", image.provider.displayName)
        put("providerImageId", image.id)
        put("latitude", image.latitude.sanitize())
        put("longitude", image.longitude.sanitize())
        put("heading", image.headingDegrees.sanitize())
        put("imageUrl", image.imageUrl)
        put("fileName", fileName)
    }

    private fun SavedStreetImage.toGeoSpyJson(): JSONObject = JSONObject().apply {
        put("id", "${image.provider.shortName}_${image.id}")
        put("lat", image.latitude.sanitize())
        put("lng", image.longitude.sanitize())
        put("heading", image.headingDegrees.sanitize())
        put("image_path", fileName) 
        put("sequence_id", image.sequenceId ?: image.provider.shortName)
    }

    private fun Double?.sanitize(): Double = if (this == null || this.isNaN() || this.isInfinite()) 0.0 else this

    private fun List<SavedStreetImage>.toCsv(): String = buildString {
        appendLine("id,lat,lng,heading,image_path,sequence_id")
        this@toCsv.forEach { saved ->
            val img = saved.image
            appendLine("${img.provider.shortName}_${img.id},${img.latitude.sanitize()},${img.longitude.sanitize()},${img.headingDegrees.sanitize()},${saved.fileName},${img.sequenceId ?: ""}")
        }
    }

    private fun downloadImage(context: Context, image: StreetImage, fileName: String, relativePath: String): Uri? {
        val uri = createMediaFile(context, fileName, relativePath, "image/jpeg") ?: return null

        return try {
            val response = Jsoup.connect(image.imageUrl)
                .ignoreContentType(true)
                .maxBodySize(0)
                .timeout(30_000)
                .execute()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                response.bodyStream().use { input -> input.copyTo(output) }
            }
            completeFile(context, uri)
            uri
        } catch (e: Exception) {
            android.util.Log.e("StreetImageExporter", "Failed to download image ${image.id} from ${image.imageUrl}: ${e.message}", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun writeTextFile(context: Context, fileName: String, relativePath: String, mimeType: String, content: String) {
        val uri = createMediaFile(context, fileName, relativePath, mimeType) ?: return
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            completeFile(context, uri)
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
        }
    }

    private fun createMediaFile(context: Context, displayName: String, relativePath: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return context.contentResolver.insert(collection, values)
    }

    private fun completeFile(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun ensureNoMediaFile(context: Context) {
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$EXPORT_ROOT/"
        val displayName = ".nomedia"
        
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf(displayName, relativePath)
        
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val alreadyExists = context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { it.count > 0 } ?: false
        if (alreadyExists) return

        val uri = createMediaFile(context, displayName, relativePath, "application/octet-stream") ?: return
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(ByteArray(0)) }
            completeFile(context, uri)
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
