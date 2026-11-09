package com.zion.gallerysorter

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaItem(
    val id: Long,
    val name: String,
    val path: String,
    val dateTaken: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

object MediaQuery {
    private const val MIME_FILTER =
        "${MediaStore.Images.Media.MIME_TYPE} IN ('image/jpeg','image/png','image/heic','image/heif','image/webp')"

    /** Every photo in the gallery, newest first. Videos are not included. */
    fun list(context: Context): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val out = ArrayList<MediaItem>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            MIME_FILTER,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val relPath = c.getString(pathCol) ?: ""
                if (EXCLUDED.any { relPath.startsWith(it, ignoreCase = true) }) continue
                val taken = if (c.isNull(takenCol)) 0L else c.getLong(takenCol)
                val added = if (c.isNull(addedCol)) 0L else c.getLong(addedCol) * 1000L
                out.add(
                    MediaItem(
                        id = c.getLong(idCol),
                        name = c.getString(nameCol) ?: "",
                        path = relPath,
                        dateTaken = if (taken > 0) taken else added,
                    )
                )
            }
        }
        return out
    }

    /**
     * Working files, not the gallery: downloads (e.g. greeting-video strips, app assets)
     * and documents. They are never sorted or moved.
     */
    private val EXCLUDED = listOf("Download/", "Documents/")

    fun count(context: Context): Int = list(context).size
}
