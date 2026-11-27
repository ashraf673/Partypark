package com.partypark.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlaylistActivity : AppCompatActivity() {

    private val items = mutableListOf<UserPlaylist>()
    private lateinit var adapter: RowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)
        findViewById<TextView>(R.id.playlist_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.playlist_add).setOnClickListener { askName() }
        val list = findViewById<RecyclerView>(R.id.playlist_list)
        adapter = RowAdapter(items) { open(it) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        items.clear()
        items.addAll(PlaylistStore.load(this))
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.playlist_empty).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun askName() {
        val input = EditText(this)
        input.hint = "Playlist name"
        AlertDialog.Builder(this)
            .setTitle("New playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val created = PlaylistStore.create(this, name)
                    open(created)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(playlist: UserPlaylist) {
        startActivity(Intent(this, PlaylistDetailActivity::class.java).putExtra("playlist_id", playlist.id))
    }
}

class PlaylistDetailActivity : AppCompatActivity() {

    private var playlistId: String = ""
    private val videos = mutableListOf<PlaylistVideo>()
    private lateinit var adapter: VideoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlistId = intent.getStringExtra("playlist_id") ?: ""
        setContentView(R.layout.activity_playlists)
        findViewById<TextView>(R.id.playlist_back).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.playlist_add).setOnClickListener { showAdd() }
        val list = findViewById<RecyclerView>(R.id.playlist_list)
        adapter = VideoAdapter(videos)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        val search = findViewById<EditText>(R.id.playlist_search)
        search.visibility = View.VISIBLE
        search.addTextChangedListener(simpleWatcher { reload(it) })
    }

    override fun onResume() {
        super.onResume()
        reload(findViewById<EditText>(R.id.playlist_search).text.toString())
        refreshTitles()
    }

    private fun refreshTitles() {
        val pending = PlaylistStore.load(this).find { it.id == playlistId }?.videos
            ?.filter { VideoTitles.needsLookup(it.title, it.url) }
            ?: return
        pending.forEach { resolveTitle(it.url) }
    }

    private fun resolveTitle(url: String) {
        Thread {
            val title = VideoTitles.lookup(url) ?: return@Thread
            if (isDestroyed) return@Thread
            PlaylistStore.updateTitle(this, playlistId, url, title)
            runOnUiThread {
                if (!isDestroyed) reload(findViewById<EditText>(R.id.playlist_search).text.toString())
            }
        }.start()
    }

    private fun reload(query: String) {
        val playlist = PlaylistStore.load(this).find { it.id == playlistId }
        findViewById<TextView>(R.id.playlist_title).text = playlist?.name ?: "Playlist"
        videos.clear()
        val all = playlist?.videos ?: emptyList()
        val q = query.trim().lowercase()
        videos.addAll(if (q.isEmpty()) all else all.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        })
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.playlist_empty).text =
            if (all.isEmpty()) "No videos yet. Tap + to add." else "No matches"
        findViewById<TextView>(R.id.playlist_empty).visibility =
            if (videos.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAdd() {
        val wrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val input = EditText(this)
        input.hint = "Paste a video link"
        val search = EditText(this)
        search.hint = "Search videos in this playlist"
        wrap.addView(search)
        wrap.addView(input)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add video")
            .setView(wrap)
            .setPositiveButton("Add link", null)
            .setNegativeButton("YouTube") { _, _ -> openYoutube() }
            .setNeutralButton("Close", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    PlaylistStore.addVideo(this, playlistId, url, "Loading title…")
                    reload(search.text.toString())
                    input.setText("")
                    resolveTitle(url)
                }
                val q = search.text.toString()
                if (q.isNotEmpty()) reload(q)
            }
        }
        search.addTextChangedListener(simpleWatcher { reload(it) })
        dialog.show()
    }

    private fun openYoutube() {
        startActivity(
            Intent(this, YoutubePickActivity::class.java)
                .putExtra(YoutubePickActivity.EXTRA_MODE, YoutubePickActivity.MODE_PLAYLIST)
                .putExtra(YoutubePickActivity.EXTRA_PLAYLIST_ID, playlistId)
        )
    }
}

private class RowAdapter(
    private val items: List<UserPlaylist>,
    private val onClick: (UserPlaylist) -> Unit
) : RecyclerView.Adapter<RowAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlist_name)
        val count: TextView = view.findViewById(R.id.playlist_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.count.text = "${item.videos.size} videos"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size
}

private class VideoAdapter(private val items: List<PlaylistVideo>) : RecyclerView.Adapter<VideoAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.playlist_name)
        val count: TextView = view.findViewById(R.id.playlist_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.title
        holder.count.text = item.url
    }

    override fun getItemCount(): Int = items.size
}

private fun simpleWatcher(onText: (String) -> Unit) = object : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        onText(s?.toString().orEmpty())
    }
    override fun afterTextChanged(s: android.text.Editable?) {}
}
