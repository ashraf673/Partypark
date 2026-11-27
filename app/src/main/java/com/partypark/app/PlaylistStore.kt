package com.partypark.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PlaylistVideo(val url: String, val title: String)

data class UserPlaylist(
    val id: String,
    val name: String,
    val videos: MutableList<PlaylistVideo>
)

object PlaylistStore {
    private const val KEY = "playlists_json"

    fun load(context: Context): MutableList<UserPlaylist> {
        val raw = context.getSharedPreferences("partypark", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<UserPlaylist>()
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val videos = mutableListOf<PlaylistVideo>()
            val items = obj.optJSONArray("videos") ?: JSONArray()
            for (j in 0 until items.length()) {
                val video = items.getJSONObject(j)
                videos.add(PlaylistVideo(video.optString("url"), video.optString("title")))
            }
            out.add(UserPlaylist(obj.optString("id"), obj.optString("name"), videos))
        }
        return out
    }

    fun save(context: Context, lists: List<UserPlaylist>) {
        val array = JSONArray()
        for (list in lists) {
            val videos = JSONArray()
            for (video in list.videos) {
                videos.put(JSONObject().put("url", video.url).put("title", video.title))
            }
            array.put(JSONObject().put("id", list.id).put("name", list.name).put("videos", videos))
        }
        context.getSharedPreferences("partypark", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }

    fun create(context: Context, name: String): UserPlaylist {
        val lists = load(context)
        val created = UserPlaylist(UUID.randomUUID().toString(), name, mutableListOf())
        lists.add(0, created)
        save(context, lists)
        return created
    }

    fun addVideo(context: Context, playlistId: String, url: String, title: String) {
        val lists = load(context)
        val playlist = lists.find { it.id == playlistId } ?: return
        val existing = playlist.videos.indexOfFirst { it.url == url }
        val clean = title.ifBlank { url }
        if (existing >= 0) {
            val current = playlist.videos[existing].title
            if (VideoTitles.needsLookup(current, url) && !VideoTitles.needsLookup(clean, url)) {
                playlist.videos[existing] = playlist.videos[existing].copy(title = clean)
                save(context, lists)
            }
            return
        }
        playlist.videos.add(PlaylistVideo(url, clean))
        save(context, lists)
    }

    fun updateTitle(context: Context, playlistId: String, url: String, title: String) {
        if (title.isBlank()) return
        val lists = load(context)
        val playlist = lists.find { it.id == playlistId } ?: return
        val index = playlist.videos.indexOfFirst { it.url == url }
        if (index < 0) return
        playlist.videos[index] = playlist.videos[index].copy(title = title)
        save(context, lists)
    }
}
