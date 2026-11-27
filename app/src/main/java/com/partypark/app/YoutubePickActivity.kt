package com.partypark.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * In-app YouTube, the same method Rave uses: load the real YouTube site,
 * watch the page address, and when a watch page opens offer to keep that video.
 */
class YoutubePickActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var actions: LinearLayout
    private lateinit var titleView: TextView
    private var mode: String = MODE_ROOM
    private var playlistId: String = ""
    private var currentId: String? = null
    private var currentTitle: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_youtube_pick)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ROOM
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID) ?: ""

        web = findViewById(R.id.yt_web)
        actions = findViewById(R.id.yt_actions)
        titleView = findViewById(R.id.yt_video_title)
        val primary = findViewById<MaterialButton>(R.id.yt_action_primary)
        val secondary = findViewById<MaterialButton>(R.id.yt_action_secondary)

        if (mode == MODE_PLAYLIST) {
            primary.text = "Add this video"
            secondary.visibility = android.view.View.GONE
            primary.setOnClickListener { addCurrent(top = false) }
        } else {
            primary.text = "Top of queue"
            secondary.visibility = android.view.View.VISIBLE
            secondary.text = "Bottom of queue"
            primary.setOnClickListener { addCurrent(top = true) }
            secondary.setOnClickListener { addCurrent(top = false) }
        }

        findViewById<TextView>(R.id.yt_close).setOnClickListener { finish() }

        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "Android")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(WATCH_JS, null)
                if (url != null) onPage(url)
            }
        }
        web.loadUrl("https://m.youtube.com")
    }

    private fun onPage(url: String) {
        val id = videoId(url)
        if (id == null) {
            currentId = null
            actions.visibility = android.view.View.GONE
            return
        }
        if (id == currentId && actions.visibility == android.view.View.VISIBLE) return
        currentId = id
        currentTitle = ""
        titleView.text = "Loading title…"
        Thread {
            val title = VideoTitles.lookup("https://www.youtube.com/watch?v=$id")
            runOnUiThread {
                if (currentId != id || isDestroyed) return@runOnUiThread
                currentTitle = title?.takeIf { !VideoTitles.needsLookup(it, "") } ?: ""
                titleView.text = currentTitle.ifBlank { "YouTube video" }
            }
        }.start()
        actions.visibility = android.view.View.VISIBLE
    }

    private fun addCurrent(top: Boolean) {
        val id = currentId ?: return
        val url = "https://www.youtube.com/watch?v=$id"
        val known = currentTitle.takeIf { it.isNotBlank() && !VideoTitles.needsLookup(it, url) }
        if (known != null) {
            savePick(url, known, top)
            return
        }
        Thread {
            val title = VideoTitles.lookup(url) ?: "YouTube video"
            runOnUiThread {
                if (!isDestroyed) savePick(url, title, top)
            }
        }.start()
    }

    private fun savePick(url: String, title: String, top: Boolean) {
        if (mode == MODE_PLAYLIST) {
            if (playlistId.isBlank()) return
            PlaylistStore.addVideo(this, playlistId, url, title)
            Toast.makeText(this, "Added to playlist", Toast.LENGTH_SHORT).show()
        } else {
            PickBus.onRoomPick?.invoke(url, title, top)
            Toast.makeText(
                this,
                if (top) "Added to the top of the queue" else "Added to the bottom of the queue",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun videoId(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host ?: return null
        if (!host.contains("youtube.com") && !host.contains("youtu.be")) return null
        if (host.contains("youtu.be")) return uri.lastPathSegment
        val path = uri.path ?: return null
        if (path.startsWith("/watch")) return uri.getQueryParameter("v")
        if (path.startsWith("/shorts/")) return path.removePrefix("/shorts/").substringBefore("/")
        if (path.startsWith("/embed/")) return path.removePrefix("/embed/").substringBefore("/")
        return null
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface
        fun pageChanged(url: String) {
            runOnUiThread { onPage(url) }
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val MODE_PLAYLIST = "playlist"
        const val MODE_ROOM = "room"
        private val WATCH_JS = """
            (function(){
              if (window.__ppWatch) return;
              window.__ppWatch = true;
              var current = location.href;
              setInterval(function(){
                if (current !== location.href) {
                  current = location.href;
                  Android.pageChanged(current);
                }
              }, 400);
              Android.pageChanged(current);
            })();
        """.trimIndent()
    }
}
