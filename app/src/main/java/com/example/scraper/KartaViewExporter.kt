package com.example.scraper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

data class KartaExportResult(
    val downloadedCount: Int,
    val failedCount: Int,
    val jsonManifestUri: Uri?,
    val csvManifestUri: Uri?
)

private data class SavedKartaPhoto(
    val photo: KartaPhoto,
    val localUri: Uri
)

object KartaViewExporter {
    private const val EXPORT_ROOT = "KartaView"

    /**
     * Downloads only the user-selected CC BY-SA images and writes JSON and CSV
     * location manifests containing their source, licence and attribution.
     */
    suspend fun exportSelected(
        context: Context,
        photos: List<KartaPhoto>,
        onProgress: (String) -> Unit
    ): KartaExportResult = withContext(Dispatchers.IO) {
        val exportId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_ROOT/$exportId"
        val savedPhotos = mutableListOf<SavedKartaPhoto>()
        var failedCount = 0

        photos.forEachIndexed { index, photo ->
            withContext(Dispatchers.Main) {
                onProgress("Downloading ${index + 1} of ${photos.size} open images…")
            }
            val savedUri = downloadImage(context, photo, relativePath)
            if (savedUri == null) {
                failedCount++
            } else {
                savedPhotos += SavedKartaPhoto(photo, savedUri)
            }
        }

        val jsonManifest = JSONObject().apply {
            put("provider", "KartaView")
            put("license", "CC BY-SA 4.0")
            put("attribution", "© Grab and KartaView Contributors")
            put("createdAt", exportId)
            put("itemCount", savedPhotos.size)
            put("items", JSONArray().apply {
                savedPhotos.forEach { saved -> put(saved.toJson()) }
            })
        }
        val jsonManifestUri = writeTextFile(
            context = context,
            displayName = "kartaview_manifest_$exportId.json",
            relativePath = relativePath,
            mimeType = "application/json",
            content = jsonManifest.toString(2)
        )
        val csvManifestUri = writeTextFile(
            context = context,
            displayName = "kartaview_manifest_$exportId.csv",
            relativePath = relativePath,
            mimeType = "text/csv",
            content = savedPhotos.toCsv()
        )

        KartaExportResult(
            downloadedCount = savedPhotos.size,
            failedCount = failedCount,
            jsonManifestUri = jsonManifestUri,
            csvManifestUri = csvManifestUri
        )
    }

    private fun SavedKartaPhoto.toJson(): JSONObject = JSONObject().apply {
        put("provider", "KartaView")
        put("providerImageId", photo.id)
        put("sequenceId", photo.sequenceId)
        put("latitude", photo.latitude)
        put("longitude", photo.longitude)
        put("headingDegrees", photo.headingDegrees)
        put("capturedAt", photo.capturedAt)
        put("projection", photo.projection)
        put("fieldOfView", photo.fieldOfView)
        put("sourceUrl", photo.imageUrl)
        put("localUri", localUri.toString())
        put("license", photo.license)
        put("attribution", photo.attribution)
    }

    private fun List<SavedKartaPhoto>.toCsv(): String = buildString {
        appendLine("provider,provider_image_id,sequence_id,latitude,longitude,heading_degrees,captured_at,projection,field_of_view,source_url,local_uri,license,attribution")
        this@toCsv.forEach { saved ->
            val photo = saved.photo
            appendLine(
                listOf(
                    "KartaView",
                    photo.id,
                    photo.sequenceId.orEmpty(),
                    photo.latitude.toString(),
                    photo.longitude.toString(),
                    photo.headingDegrees?.toString().orEmpty(),
                    photo.capturedAt.orEmpty(),
                    photo.projection.orEmpty(),
                    photo.fieldOfView?.toString().orEmpty(),
                    photo.imageUrl,
                    saved.localUri.toString(),
                    photo.license,
                    photo.attribution
                ).joinToString(",") { it.csvQuoted() }
            )
        }
    }

    private fun String.csvQuoted(): String = "\"${replace("\"", "\"\"")}\""

    private fun downloadImage(context: Context, photo: KartaPhoto, relativePath: String): Uri? {
        val displayName = "kartaview_${photo.id}.jpg"
        val uri = createDownloadFile(context, displayName, relativePath, "image/jpeg") ?: return null

        return try {
            val response = Jsoup.connect(photo.imageUrl)
                .ignoreContentType(true)
                .maxBodySize(0)
                .timeout(30_000)
                .execute()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                response.bodyStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open the destination file")
            completeDownload(context, uri)
            uri
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun writeTextFile(
        context: Context,
        displayName: String,
        relativePath: String,
        mimeType: String,
        content: String
    ): Uri? {
        val uri = createDownloadFile(context, displayName, relativePath, mimeType) ?: return null
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output: OutputStream ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Unable to open the destination file")
            completeDownload(context, uri)
            uri
        } catch (_: Exception) {
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun createDownloadFile(
        context: Context,
        displayName: String,
        relativePath: String,
        mimeType: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun completeDownload(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }
}
