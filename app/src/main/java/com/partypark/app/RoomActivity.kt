package com.partypark.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.UUID

class RoomActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }
    private val db = Firebase.database.reference

    private lateinit var lobbyLayout: LinearLayout
    private lateinit var roomLayout: LinearLayout
    private lateinit var roomCodeText: TextView
    private lateinit var chatList: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var playerWebView: WebView
    private lateinit var playerPlaceholder: TextView
    private lateinit var playerContainer: FrameLayout

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var currentRoomCode: String? = null
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room)

        lobbyLayout = findViewById(R.id.lobby_layout)
        roomLayout = findViewById(R.id.room_layout)
        roomCodeText = findViewById(R.id.room_code_text)
        chatList = findViewById(R.id.chat_list)
        messageInput = findViewById(R.id.message_input)
        playerWebView = findViewById(R.id.player_webview)
        playerPlaceholder = findViewById(R.id.player_placeholder)
        playerContainer = findViewById(R.id.player_container)

        chatAdapter = ChatAdapter(messages)
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = chatAdapter

        setupWebView()
        setupExoPlayer()

        findViewById<MaterialButton>(R.id.create_room_btn).setOnClickListener { showCreateDialog() }
        findViewById<MaterialButton>(R.id.join_room_btn).setOnClickListener { showJoinDialog() }
        findViewById<MaterialButton>(R.id.share_btn).setOnClickListener { shareRoom() }
        findViewById<MaterialButton>(R.id.leave_btn).setOnClickListener { leaveRoom() }
        findViewById<MaterialButton>(R.id.load_btn).setOnClickListener { showLoadDialog() }
        findViewById<MaterialButton>(R.id.send_btn).setOnClickListener { sendMessage() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = playerWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // Android WebView adds "wv". YouTube rejects that and shows Error 153.
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(playerWebView, true)
        playerWebView.webChromeClient = WebChromeClient()
        playerWebView.webViewClient = WebViewClient()
    }

    private fun setupExoPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        playerView = PlayerView(this).apply {
            player = exoPlayer
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        playerContainer.addView(playerView, 0)
    }

    private fun showCreateDialog() {
        val input = EditText(this)
        input.hint = "Room name"
        AlertDialog.Builder(this)
            .setTitle("Create Room")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Room" }
                createRoom(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showJoinDialog() {
        val input = EditText(this)
        input.hint = "Room code"
        AlertDialog.Builder(this)
            .setTitle("Join Room")
            .setView(input)
            .setPositiveButton("Join") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.isNotEmpty()) joinRoom(code)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createRoom(name: String) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
        val myId = prefs.getString("deviceId", UUID.randomUUID().toString())!!

        val roomData = mapOf(
            "name" to name,
            "createdAt" to System.currentTimeMillis(),
            "members" to mapOf(myId to mapOf("name" to myName, "online" to true))
        )

        db.child("rooms").child(code).setValue(roomData).addOnSuccessListener {
            enterRoom(code)
        }.addOnFailureListener {
            Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun joinRoom(code: String) {
        db.child("rooms").child(code).get().addOnSuccessListener { snap ->
            if (snap.exists()) {
                val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
                val myId = prefs.getString("deviceId", UUID.randomUUID().toString())!!
                db.child("rooms").child(code).child("members").child(myId)
                    .setValue(mapOf("name" to myName, "online" to true))
                enterRoom(code)
            } else {
                Toast.makeText(this, "Room not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enterRoom(code: String) {
        currentRoomCode = code
        roomCodeText.text = "Room: $code"
        lobbyLayout.visibility = View.GONE
        roomLayout.visibility = View.VISIBLE
        listenToMessages(code)
        listenToPlayer(code)
    }

    private fun listenToMessages(code: String) {
        db.child("rooms").child(code).child("messages").addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val name = snapshot.child("name").getValue(String::class.java) ?: ""
                val text = snapshot.child("text").getValue(String::class.java) ?: ""
                messages.add(ChatMessage(name, text))
                chatAdapter.notifyItemInserted(messages.size - 1)
                chatList.scrollToPosition(messages.size - 1)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenToPlayer(code: String) {
        db.child("rooms").child(code).child("player").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val url = snapshot.child("url").getValue(String::class.java) ?: return
                loadVideo(url)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        val code = currentRoomCode ?: return
        if (text.isEmpty()) return
        val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
        val msg = mapOf("name" to myName, "text" to text, "ts" to System.currentTimeMillis())
        db.child("rooms").child(code).child("messages").push().setValue(msg)
        messageInput.text.clear()
    }

    private fun showLoadDialog() {
        val input = EditText(this)
        input.hint = "YouTube, Drive, Vimeo, or a direct .mp4 / .m3u8 link"
        AlertDialog.Builder(this)
            .setTitle("Load Video")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) loadAndSync(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAndSync(url: String) {
        val code = currentRoomCode ?: return
        db.child("rooms").child(code).child("player").setValue(mapOf("url" to url, "ts" to System.currentTimeMillis()))
    }

    private fun loadVideo(url: String) {
        playerPlaceholder.visibility = View.GONE
        val yt = youtubeId(url)
        val vimeo = vimeoId(url)
        val daily = dailymotionId(url)
        val drive = driveId(url)
        when {
            isDirectVideo(url) -> playWithExoPlayer(url)
            yt != null -> playYoutube(yt)
            vimeo != null -> playEmbed("https://player.vimeo.com/video/$vimeo?autoplay=1&playsinline=1")
            daily != null -> playEmbed("https://www.dailymotion.com/embed/video/$daily?autoplay=1")
            drive != null -> playEmbed("https://drive.google.com/file/d/$drive/preview")
            else -> playPage(url)
        }
    }

    private fun isDirectVideo(url: String): Boolean {
        val path = url.lowercase().substringBefore('#')
        return Regex("\\.(mp4|webm|mkv|mov|m4v|ogv|m3u8|mpd)($|\\?)").containsMatchIn(path)
    }

    private fun youtubeId(url: String): String? {
        val patterns = listOf(
            Regex("youtu\\.be/([\\w-]{11})"),
            Regex("[?&]v=([\\w-]{11})"),
            Regex("youtube\\.com/embed/([\\w-]{11})"),
            Regex("youtube\\.com/shorts/([\\w-]{11})"),
            Regex("youtube\\.com/live/([\\w-]{11})")
        )
        return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    private fun vimeoId(url: String): String? {
        return Regex("vimeo\\.com/(?:video/)?(\\d+)").find(url)?.groupValues?.get(1)
    }

    private fun dailymotionId(url: String): String? {
        return Regex("(?:dailymotion\\.com/(?:embed/)?video|dai\\.ly)/([a-zA-Z0-9]+)").find(url)?.groupValues?.get(1)
    }

    private fun driveId(url: String): String? {
        if (!url.contains("drive.google.com") && !url.contains("docs.google.com")) return null
        return Regex("/d/([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: Regex("[?&]id=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
    }

    private fun showWeb() {
        exoPlayer?.pause()
        playerView?.visibility = View.GONE
        playerWebView.visibility = View.VISIBLE
    }

    private fun playWithExoPlayer(url: String) {
        playerWebView.visibility = View.GONE
        playerWebView.loadUrl("about:blank")
        playerView?.visibility = View.VISIBLE
        exoPlayer?.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun playYoutube(id: String) {
        showWeb()
        // Same method Kosmi uses: a real page origin, then the YouTube IFrame API.
        // Opening the embed URL directly is what triggers Error 153.
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <style>html,body,#player{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}</style>
            </head>
            <body>
              <div id="player"></div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                var player;
                function onYouTubeIframeAPIReady() {
                  player = new YT.Player('player', {
                    width: '100%',
                    height: '100%',
                    videoId: '$id',
                    playerVars: {
                      autoplay: 1,
                      playsinline: 1,
                      rel: 0,
                      modestbranding: 1,
                      origin: '$APP_ORIGIN'
                    }
                  });
                }
              </script>
            </body>
            </html>
        """.trimIndent()
        playerWebView.loadDataWithBaseURL(APP_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun playEmbed(embedUrl: String) {
        showWeb()
        val safe = embedUrl.replace("\"", "")
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>html,body{margin:0;background:#000;height:100%}iframe{border:0;width:100%;height:100%}</style>
            </head>
            <body>
              <iframe src="$safe" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>
            </body>
            </html>
        """.trimIndent()
        playerWebView.loadDataWithBaseURL(APP_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun playPage(url: String) {
        showWeb()
        playerWebView.loadUrl(url)
    }

    private fun shareRoom() {
        val code = currentRoomCode ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join my Watch Together room: $code")
        }
        startActivity(Intent.createChooser(intent, "Share room"))
    }

    private fun leaveRoom() {
        currentRoomCode = null
        messages.clear()
        chatAdapter.notifyDataSetChanged()
        roomLayout.visibility = View.GONE
        lobbyLayout.visibility = View.VISIBLE
        exoPlayer?.stop()
        playerWebView.loadUrl("about:blank")
        playerPlaceholder.visibility = View.VISIBLE
        playerView?.visibility = View.GONE
        playerWebView.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }

    companion object {
        private const val APP_ORIGIN = "https://partypark.app"
    }
}

data class ChatMessage(val name: String, val text: String)

class ChatAdapter(private val items: List<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.VH>() {
    class VH(val view: TextView) : RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context).apply {
            setPadding(16, 8, 16, 8)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
        }
        return VH(tv)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.view.text = "${m.name}: ${m.text}"
    }
    override fun getItemCount() = items.size
}
