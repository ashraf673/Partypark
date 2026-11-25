package com.partypark.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
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
    private lateinit var roomsList: RecyclerView
    private lateinit var roomsEmpty: TextView
    private lateinit var messageInput: EditText
    private lateinit var playerWebView: KeepAliveWebView
    private lateinit var playerPlaceholder: TextView
    private lateinit var playerContainer: FrameLayout
    private lateinit var seekHint: TextView
    private lateinit var gestureLayer: View
    private lateinit var fullscreenBtn: MaterialButton
    private lateinit var playerControls: LinearLayout
    private lateinit var playPauseBtn: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var playerSeek: SeekBar
    private lateinit var streamExtractor: StreamExtractor

    private var pendingYoutubeId: String? = null

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null

    private var currentRoomCode: String? = null
    private var loadedUrl: String? = null
    private var usingExo = false
    private var inBackground = false
    private var backgroundPlay = false
    private var youtubeMode = false
    private var fullscreen = false
    private var controlsVisible = false
    private var userSeeking = false
    private var applyingRemote = false
    private var acceptLocalEvents = false
    private var lastAppliedTs = 0L
    private var lastBy: String? = null
    private var driveFallbackId: String? = null
    private var driveRetried = false
    private var driveResourceKey: String? = null

    private var playerListener: ValueEventListener? = null
    private var messageListener: ChildEventListener? = null
    private var listeningCode: String? = null

    private val messages = mutableListOf<ChatMessage>()
    private val roomItems = mutableListOf<RoomEntry>()
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var roomAdapter: RoomListAdapter

    private val hideHint = Runnable { seekHint.visibility = View.GONE }
    private val hideControls = Runnable { hidePlayerControls() }

    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (loadedUrl == null) return false
                val delta = if (e.x < gestureLayer.width / 2f) -10.0 else 10.0
                if (usingExo) seekBy(delta)
                else if (youtubeMode) seekYoutube(delta)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (loadedUrl == null) return false
                togglePlayerControls()
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room)

        lobbyLayout = findViewById(R.id.lobby_layout)
        roomLayout = findViewById(R.id.room_layout)
        roomCodeText = findViewById(R.id.room_code_text)
        chatList = findViewById(R.id.chat_list)
        roomsList = findViewById(R.id.rooms_list)
        roomsEmpty = findViewById(R.id.rooms_empty)
        messageInput = findViewById(R.id.message_input)
        playerWebView = findViewById(R.id.player_webview)
        playerPlaceholder = findViewById(R.id.player_placeholder)
        playerContainer = findViewById(R.id.player_container)
        streamExtractor = StreamExtractor(playerContainer)
        seekHint = findViewById(R.id.seek_hint)
        gestureLayer = findViewById(R.id.gesture_layer)
        fullscreenBtn = findViewById(R.id.fullscreen_btn)
        playerControls = findViewById(R.id.player_controls)
        playPauseBtn = findViewById(R.id.play_pause_btn)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        playerSeek = findViewById(R.id.player_seek)

        chatAdapter = ChatAdapter(messages)
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = chatAdapter

        roomAdapter = RoomListAdapter(roomItems, { entry ->
            joinRoom(entry.code)
        }, { entry ->
            confirmDelete(entry)
        })
        roomsList.layoutManager = LinearLayoutManager(this)
        roomsList.adapter = roomAdapter

        setupWebView()
        setupExoPlayer()
        setupPlayerControls()
        gestureLayer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        findViewById<MaterialButton>(R.id.create_room_btn).setOnClickListener { showCreateDialog() }
        findViewById<MaterialButton>(R.id.join_room_btn).setOnClickListener { showJoinDialog() }
        findViewById<MaterialButton>(R.id.share_btn).setOnClickListener { shareRoom() }
        findViewById<MaterialButton>(R.id.leave_btn).setOnClickListener { leaveRoom() }
        findViewById<MaterialButton>(R.id.load_btn).setOnClickListener { showLoadDialog() }
        findViewById<MaterialButton>(R.id.send_btn).setOnClickListener { sendMessage() }
        fullscreenBtn.setOnClickListener { showRotateHint() }

        refreshRooms()
        playerContainer.postDelayed(heartbeat, 4000)
        playerContainer.post(progressTick)
    }

    override fun onResume() {
        super.onResume()
        if (lobbyLayout.visibility == View.VISIBLE) refreshRooms()
    }

    override fun onPause() {
        backgroundPlay = mediaIsPlaying()
        if (backgroundPlay) {
            playerWebView.keepPlayingInBackground = true
            playerWebView.onResume()
            if (usingExo) {
                exoPlayer?.setForegroundMode(true)
                exoPlayer?.playWhenReady = true
            }
            PlaybackService.start(this)
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        inBackground = true
    }

    override fun onStart() {
        inBackground = false
        if (::playerWebView.isInitialized) playerWebView.keepPlayingInBackground = false
        exoPlayer?.setForegroundMode(false)
        PlaybackService.stop(this)
        super.onStart()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private var lastBackAt = 0L
    private var holdChrome = false

    @Deprecated("Back goes to the room list first")
    override fun onBackPressed() {
        if (fullscreen) {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000) {
                holdChrome = true
                setWatchFullscreen(false)
            } else {
                lastBackAt = now
                Toast.makeText(this, "Rotate to portrait to exit full screen", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (roomLayout.visibility == View.VISIBLE) {
            leaveRoom()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = playerWebView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(playerWebView, true)
        playerWebView.webChromeClient = WebChromeClient()
        playerWebView.webViewClient = WebViewClient()
        playerWebView.addJavascriptInterface(YtBridge(), "Android")
    }

    private fun setupExoPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
        exoPlayer?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false
        )
        exoPlayer?.setWakeMode(C.WAKE_MODE_NETWORK)
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (applyingRemote) {
                    if (inBackground) syncBackgroundService(isPlaying)
                    return
                }
                if (inBackground && backgroundPlay && usingExo && exoPlayer?.playWhenReady == false) {
                    exoPlayer?.playWhenReady = true
                    return
                }
                if (inBackground && backgroundPlay && !isPlaying) return
                if (!acceptLocalEvents || !usingExo) return
                val pos = (exoPlayer?.currentPosition ?: 0L) / 1000.0
                if (isPlaying && pos < 1.0) return
                pushState(pos, isPlaying)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (applyingRemote || !acceptLocalEvents || !usingExo) return
                pushState(newPosition.positionMs / 1000.0, exoPlayer?.playWhenReady == true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    driveFallbackId = null
                    driveRetried = false
                    pendingYoutubeId = null
                    acceptLocalEvents = true
                    playerPlaceholder.visibility = View.GONE
                }
                if (playbackState == Player.STATE_ENDED && inBackground) {
                    backgroundPlay = false
                    PlaybackService.stop(this@RoomActivity)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val yt = pendingYoutubeId
                if (yt != null) {
                    pendingYoutubeId = null
                    playYoutubeEmbed(yt, (exoPlayer?.currentPosition ?: 0L) / 1000.0, true)
                    return
                }
                val id = driveFallbackId ?: return
                if (!driveRetried) {
                    driveRetried = true
                    val key = driveResourceKey?.let { "&resourcekey=$it" } ?: ""
                    playWithExoPlayer(
                        "https://drive.google.com/uc?export=download&id=$id&confirm=t$key",
                        (exoPlayer?.currentPosition ?: 0L) / 1000.0,
                        true
                    )
                    return
                }
                driveFallbackId = null
                Toast.makeText(
                    this@RoomActivity,
                    "This Drive file can't use Partypark controls. Share it as Anyone with the link.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        playerView = PlayerView(this).apply {
            player = exoPlayer
            useController = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        playerContainer.addView(playerView, 0)
    }

    private fun mediaIsPlaying(): Boolean {
        if (!::roomLayout.isInitialized || roomLayout.visibility != View.VISIBLE) return false
        if (loadedUrl.isNullOrBlank()) return false
        return if (usingExo) {
            exoPlayer?.playWhenReady == true && exoPlayer?.playbackState != Player.STATE_IDLE
        } else {
            playPauseBtn.text == "Pause"
        }
    }

    private fun syncBackgroundService(playing: Boolean) {
        if (!inBackground) return
        if (playing) {
            backgroundPlay = true
            PlaybackService.start(this)
        } else {
            backgroundPlay = false
            playerWebView.keepPlayingInBackground = false
            PlaybackService.stop(this)
        }
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
        val myId = myDeviceId()
        val roomData = mapOf(
            "name" to name,
            "createdAt" to System.currentTimeMillis(),
            "members" to mapOf(myId to mapOf("name" to myName, "online" to true))
        )
        db.child("rooms").child(code).setValue(roomData).addOnSuccessListener {
            enterRoom(code, name)
        }.addOnFailureListener {
            Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun joinRoom(code: String) {
        db.child("rooms").child(code).get().addOnSuccessListener { snap ->
            if (!snap.exists()) {
                Toast.makeText(this, "Room not found", Toast.LENGTH_SHORT).show()
                removeLocalRoom(code)
                refreshRooms()
                return@addOnSuccessListener
            }
            val name = snap.child("name").getValue(String::class.java) ?: "Room"
            val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
            db.child("rooms").child(code).child("members").child(myDeviceId())
                .setValue(mapOf("name" to myName, "online" to true))
            enterRoom(code, name)
        }
    }

    private fun enterRoom(code: String, name: String) {
        saveJoined(code, name)
        currentRoomCode = code
        roomCodeText.text = "$name  $code"
        lobbyLayout.visibility = View.GONE
        roomLayout.visibility = View.VISIBLE
        messages.clear()
        chatAdapter.notifyDataSetChanged()
        loadedUrl = null
        lastAppliedTs = 0L
        hidePlayerControls()
        listenToRoom(code)
        applyOrientation()
    }

    private fun listenToRoom(code: String) {
        stopListening()
        listeningCode = code

        messageListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val who = snapshot.child("name").getValue(String::class.java) ?: ""
                val text = snapshot.child("text").getValue(String::class.java) ?: ""
                messages.add(ChatMessage(who, text))
                chatAdapter.notifyItemInserted(messages.size - 1)
                chatList.scrollToPosition(messages.size - 1)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).child("messages").addChildEventListener(messageListener!!)

        playerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onPlayerSnapshot(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).child("player").addValueEventListener(playerListener!!)
    }

    private fun onPlayerSnapshot(snapshot: DataSnapshot) {
        val url = snapshot.child("url").getValue(String::class.java) ?: return
        val position = numberOf(snapshot, "position")
        val playing = snapshot.child("playing").getValue(Boolean::class.java) ?: true
        val actionTs = longOf(snapshot, "actionTs")
        val by = snapshot.child("by").getValue(String::class.java)
        lastBy = by

        if (url != loadedUrl) {
            loadedUrl = url
            lastAppliedTs = actionTs
            val elapsed = if (playing && actionTs > 0) {
                ((System.currentTimeMillis() - actionTs) / 1000.0).coerceAtLeast(0.0)
            } else 0.0
            acceptLocalEvents = false
            loadVideo(url, position + elapsed, playing)
            playerContainer.postDelayed({ acceptLocalEvents = true }, 12000)
            return
        }

        if (by == myDeviceId()) return
        if (actionTs <= lastAppliedTs) return
        lastAppliedTs = actionTs
        val elapsed = if (playing) {
            ((System.currentTimeMillis() - actionTs) / 1000.0).coerceAtLeast(0.0)
        } else 0.0
        considerRemote(position + elapsed, playing)
    }

    private fun considerRemote(target: Double, playing: Boolean) {
        if (usingExo) {
            val now = (exoPlayer?.currentPosition ?: 0L) / 1000.0
            val samePlay = exoPlayer?.playWhenReady == playing
            if (samePlay && kotlin.math.abs(now - target) < 1.5) return
            applyRemote(target, playing)
            return
        }
        if (!youtubeMode) return
        playerWebView.evaluateJavascript(
            "(function(){if(!window.player||!player.getCurrentTime)return '';" +
                "var on=player.getPlayerState&&player.getPlayerState()==1?1:0;" +
                "return player.getCurrentTime()+','+on;})()"
        ) { raw ->
            val parts = raw?.trim()?.trim('"')?.split(',')
            val now = parts?.getOrNull(0)?.toDoubleOrNull()
            val isPlaying = parts?.getOrNull(1) == "1"
            if (now != null && isPlaying == playing && kotlin.math.abs(now - target) < 1.5) return@evaluateJavascript
            applyRemote(target, playing)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            syncHeartbeat()
            playerContainer.postDelayed(this, 4000)
        }
    }

    private fun syncHeartbeat() {
        if (!acceptLocalEvents || applyingRemote || currentRoomCode == null || loadedUrl == null) return
        if (lastBy != myDeviceId()) return
        if (usingExo && exoPlayer?.isPlaying == true) {
            pushState((exoPlayer?.currentPosition ?: 0L) / 1000.0, true)
            return
        }
        if (!youtubeMode) return
        playerWebView.evaluateJavascript(
            "(function(){if(!window.player||!player.getCurrentTime||!player.getPlayerState)return '';" +
                "if(player.getPlayerState()!=1)return '';return ''+player.getCurrentTime();})()"
        ) { raw ->
            val t = raw?.trim()?.trim('"')?.toDoubleOrNull() ?: return@evaluateJavascript
            if (lastBy == myDeviceId() && !applyingRemote) pushState(t, true)
        }
    }

    private fun pushState(position: Double, playing: Boolean) {
        if (applyingRemote) return
        val code = currentRoomCode ?: return
        val url = loadedUrl ?: return
        val ts = System.currentTimeMillis()
        lastAppliedTs = ts
        lastBy = myDeviceId()
        db.child("rooms").child(code).child("player").updateChildren(mapOf(
            "url" to url,
            "position" to position,
            "playing" to playing,
            "actionTs" to ts,
            "by" to myDeviceId()
        ))
    }

    private fun applyRemote(position: Double, playing: Boolean) {
        applyingRemote = true
        if (usingExo) {
            exoPlayer?.seekTo((position * 1000).toLong())
            exoPlayer?.playWhenReady = playing
        } else if (youtubeMode) {
            val playJs = if (playing) "player.playVideo();" else "player.pauseVideo();"
            val js = "if(window.player&&player.seekTo){window.suppressUntil=Date.now()+1800;player.seekTo($position,true);$playJs}"
            playerWebView.evaluateJavascript(js, null)
        }
        playerContainer.postDelayed({ applyingRemote = false }, 1200)
    }

    private fun seekYoutube(deltaSec: Double) {
        val js = "(function(){if(!window.player||!player.getCurrentTime)return '';" +
            "var t=Math.max(0,player.getCurrentTime()+(" + deltaSec + "));" +
            "window.suppressUntil=Date.now()+1800;player.seekTo(t,true);return ''+t;})()"
        playerWebView.evaluateJavascript(js) { raw ->
            val t = raw?.trim()?.trim('"')?.toDoubleOrNull() ?: return@evaluateJavascript
            pushState(t, true)
            flash(if (deltaSec > 0) "+10s" else "-10s")
        }
    }

    private fun seekBy(deltaSec: Double) {
        if (loadedUrl == null) return
        if (usingExo) {
            val now = (exoPlayer?.currentPosition ?: 0L) / 1000.0
            val duration = (exoPlayer?.duration ?: 0L) / 1000.0
            var target = (now + deltaSec).coerceAtLeast(0.0)
            if (duration > 0) target = target.coerceAtMost(duration)
            pushState(target, exoPlayer?.playWhenReady == true)
            applyingRemote = true
            exoPlayer?.seekTo((target * 1000).toLong())
            playerContainer.postDelayed({ applyingRemote = false }, 400)
            flash(if (deltaSec > 0) "+10s" else "-10s")
        }
    }

    private fun flash(text: String) {
        seekHint.text = text
        seekHint.visibility = View.VISIBLE
        seekHint.removeCallbacks(hideHint)
        seekHint.postDelayed(hideHint, 700)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        val code = currentRoomCode ?: return
        if (text.isEmpty()) return
        val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
        db.child("rooms").child(code).child("messages").push().setValue(
            mapOf("name" to myName, "text" to text, "ts" to System.currentTimeMillis())
        )
        messageInput.text.clear()
    }

    private fun showLoadDialog() {
        val input = EditText(this)
        input.hint = "YouTube, Drive, or a direct video link"
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
        loadedUrl = url
        acceptLocalEvents = false
        lastBy = myDeviceId()
        val ts = System.currentTimeMillis()
        lastAppliedTs = ts
        db.child("rooms").child(code).child("player").setValue(mapOf(
            "url" to url,
            "position" to 0.0,
            "playing" to true,
            "actionTs" to ts,
            "by" to myDeviceId()
        ))
        loadVideo(url, 0.0, true)
        playerContainer.postDelayed({ acceptLocalEvents = true }, 12000)
    }

    private fun loadVideo(url: String, startSec: Double, playing: Boolean) {
        playerPlaceholder.visibility = View.GONE
        driveFallbackId = null
        youtubeMode = false
        val yt = youtubeId(url)
        val vimeo = vimeoId(url)
        val daily = dailymotionId(url)
        val drive = driveId(url)
        when {
            isDirectVideo(url) -> playWithExoPlayer(url, startSec, playing)
            yt != null -> playYoutube(yt, startSec, playing)
            vimeo != null -> playEmbed("https://player.vimeo.com/video/$vimeo?autoplay=1&playsinline=1")
            daily != null -> playEmbed("https://www.dailymotion.com/embed/video/$daily?autoplay=1")
            drive != null -> playDrive(drive, startSec, playing)
            else -> playPage(url)
        }
    }

    private fun playDrive(id: String, startSec: Double, playing: Boolean) {
        driveFallbackId = id
        driveRetried = false
        driveResourceKey = resourceKeyFrom(loadedUrl)
        pendingYoutubeId = null
        playerPlaceholder.visibility = View.VISIBLE
        playerPlaceholder.text = "Loading video…"
        streamExtractor.extractDrive(id) { direct ->
            if (loadedUrl == null || driveId(loadedUrl!!) != id) return@extractDrive
            val key = driveResourceKey?.let { "&resourcekey=$it" } ?: ""
            val download = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t$key"
            playWithExoPlayer(direct ?: download, startSec, playing, "https://drive.google.com/")
        }
    }

    private fun showWeb() {
        usingExo = false
        exoPlayer?.pause()
        playerView?.visibility = View.GONE
        playerWebView.visibility = View.VISIBLE
    }

    private fun playWithExoPlayer(url: String, startSec: Double, playing: Boolean, referer: String? = null) {
        playExtracted(url, null, startSec, playing, referer)
    }

    private fun playExtracted(
        videoUrl: String,
        audioUrl: String?,
        startSec: Double,
        playing: Boolean,
        referer: String?
    ) {
        usingExo = true
        youtubeMode = false
        playerWebView.visibility = View.GONE
        playerWebView.loadUrl("about:blank")
        playerView?.visibility = View.VISIBLE
        val startMs = (startSec * 1000).toLong().coerceAtLeast(0L)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(StreamExtractor.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
        if (referer != null) {
            http.setDefaultRequestProperties(mapOf("Referer" to referer, "Origin" to referer.trimEnd('/')))
        }
        val factory = ProgressiveMediaSource.Factory(http)
        val video = factory.createMediaSource(MediaItem.fromUri(videoUrl))
        val source = if (audioUrl == null) {
            video
        } else {
            MergingMediaSource(video, factory.createMediaSource(MediaItem.fromUri(audioUrl)))
        }
        exoPlayer?.setMediaSource(source, startMs)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = playing
    }

    private fun playYoutube(id: String, startSec: Double, playing: Boolean) {
        pendingYoutubeId = id
        youtubeMode = false
        usingExo = true
        playerPlaceholder.visibility = View.VISIBLE
        playerPlaceholder.text = "Loading video…"
        playerWebView.visibility = View.GONE
        streamExtractor.extractYoutube(id) { video, audio ->
            if (loadedUrl == null || youtubeId(loadedUrl!!) != id) return@extractYoutube
            if (video == null) {
                playYoutubeEmbed(id, startSec, playing)
            } else {
                playExtracted(video, audio, startSec, playing, "https://www.youtube.com/")
            }
        }
    }

    private fun playYoutubeEmbed(id: String, startSec: Double, playing: Boolean) {
        pendingYoutubeId = null
        showWeb()
        youtubeMode = true
        playerPlaceholder.visibility = View.GONE
        val start = startSec.toInt().coerceAtLeast(0)
        val autoplay = if (playing) 1 else 0
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
                window.suppressUntil = 0;
                var lastT = 0;
                function onYouTubeIframeAPIReady() {
                  player = new YT.Player('player', {
                    width: '100%',
                    height: '100%',
                    videoId: '$id',
                    playerVars: {
                      autoplay: $autoplay,
                      playsinline: 1,
                      controls: 0,
                      disablekb: 1,
                      fs: 0,
                      rel: 0,
                      modestbranding: 1,
                      iv_load_policy: 3,
                      start: $start,
                      origin: '$APP_ORIGIN'
                    },
                    events: { onStateChange: onPlayerStateChange }
                  });
                }
                function onPlayerStateChange(e) {
                  if (Date.now() < window.suppressUntil || !player || !player.getCurrentTime) return;
                  var t = player.getCurrentTime();
                  if (e.data === YT.PlayerState.PLAYING) Android.onPlayback(t, true);
                  else if (e.data === YT.PlayerState.PAUSED) Android.onPlayback(t, false);
                }
                setInterval(function() {
                  if (Date.now() < window.suppressUntil || !player || !player.getCurrentTime) return;
                  var st = player.getPlayerState();
                  var t = player.getCurrentTime();
                  var active = st === YT.PlayerState.PLAYING || st === YT.PlayerState.PAUSED;
                  if (active && lastT > 0 && Math.abs(t - lastT) > 2.5) {
                    Android.onPlayback(t, st === YT.PlayerState.PLAYING);
                  }
                  if (active) lastT = t;
                }, 700);
              </script>
            </body>
            </html>
        """.trimIndent()
        playerWebView.loadDataWithBaseURL(APP_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun playEmbed(embedUrl: String) {
        showWeb()
        youtubeMode = false
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
              <iframe src="$safe" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen></iframe>
            </body>
            </html>
        """.trimIndent()
        playerWebView.loadDataWithBaseURL(APP_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun playPage(url: String) {
        showWeb()
        youtubeMode = false
        playerWebView.loadUrl(url)
    }

    @Suppress("DEPRECATION")
    private fun showRotateHint() {
        AlertDialog.Builder(this)
            .setTitle("Full screen")
            .setMessage("Rotate your phone to landscape mode to enter full screen. Only the movie will play. Rotate back to portrait to exit.")
            .setPositiveButton("OK", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun applyOrientation() {
        val inRoom = roomLayout.visibility == View.VISIBLE
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) holdChrome = false
        setWatchFullscreen(inRoom && landscape && !holdChrome)
    }

    @Suppress("DEPRECATION")
    private fun setWatchFullscreen(on: Boolean) {
        if (on == fullscreen) return
        fullscreen = on
        val header = findViewById<View>(R.id.room_header)
        val loadBtn = findViewById<View>(R.id.load_btn)
        val messageBar = findViewById<View>(R.id.message_bar)
        val playerLp = playerContainer.layoutParams as LinearLayout.LayoutParams
        if (on) {
            supportActionBar?.hide()
            header.visibility = View.GONE
            loadBtn.visibility = View.GONE
            chatList.visibility = View.GONE
            messageBar.visibility = View.GONE
            playerLp.height = 0
            playerLp.weight = 1f
            hidePlayerControls()
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        } else {
            supportActionBar?.show()
            header.visibility = View.VISIBLE
            loadBtn.visibility = View.VISIBLE
            chatList.visibility = View.VISIBLE
            messageBar.visibility = View.VISIBLE
            playerLp.height = dp(220)
            playerLp.weight = 0f
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        playerContainer.layoutParams = playerLp
    }

    private fun setupPlayerControls() {
        playerContainer.keepScreenOn = true
        playPauseBtn.setOnClickListener { onPlayPauseClicked() }
        playerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) positionText.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
                playerControls.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                val sec = seekBar.progress / 1000.0
                if (usingExo) {
                    pushState(sec, exoPlayer?.playWhenReady == true)
                    applyingRemote = true
                    exoPlayer?.seekTo(seekBar.progress.toLong())
                    playerContainer.postDelayed({ applyingRemote = false }, 400)
                } else if (youtubeMode) {
                    val js = "if(window.player&&player.seekTo){window.suppressUntil=Date.now()+1800;player.seekTo($sec,true);}"
                    playerWebView.evaluateJavascript(js, null)
                    pushState(sec, playPauseBtn.text != "Play")
                }
                scheduleHideControls()
            }
        })
    }

    private val progressTick = object : Runnable {
        override fun run() {
            if (!userSeeking && loadedUrl != null) refreshProgress()
            playerContainer.postDelayed(this, 500)
        }
    }

    private fun refreshProgress() {
        if (userSeeking) return
        if (usingExo) {
            bindTimes(exoPlayer?.currentPosition ?: 0L, exoPlayer?.duration ?: 0L, exoPlayer?.isPlaying == true)
            return
        }
        if (!youtubeMode) return
        playerWebView.evaluateJavascript(
            "(function(){if(!window.player||!player.getCurrentTime||!player.getDuration)return '';" +
                "var st=player.getPlayerState?player.getPlayerState():-1;" +
                "return player.getCurrentTime()+','+player.getDuration()+','+(st===1?'1':'0');})()"
        ) { raw ->
            if (userSeeking) return@evaluateJavascript
            val parts = raw?.trim()?.trim('"')?.split(',') ?: return@evaluateJavascript
            val pos = parts.getOrNull(0)?.toDoubleOrNull() ?: return@evaluateJavascript
            val dur = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            bindTimes((pos * 1000).toLong(), (dur * 1000).toLong(), parts.getOrNull(2) == "1")
        }
    }

    private fun bindTimes(positionMs: Long, durationMs: Long, playing: Boolean) {
        if (durationMs > 0) {
            val max = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            if (playerSeek.max != max) playerSeek.max = max
            playerSeek.progress = positionMs.coerceIn(0L, max.toLong()).toInt()
            durationText.text = formatTime(durationMs)
        }
        positionText.text = formatTime(positionMs.coerceAtLeast(0L))
        playPauseBtn.text = if (playing) "Pause" else "Play"
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun togglePlayerControls() {
        if (controlsVisible) hidePlayerControls() else showPlayerControls()
    }

    private fun showPlayerControls() {
        if (loadedUrl == null) return
        controlsVisible = true
        playerControls.visibility = View.VISIBLE
        playerControls.bringToFront()
        refreshProgress()
        scheduleHideControls()
    }

    private fun hidePlayerControls() {
        controlsVisible = false
        if (::playerControls.isInitialized) {
            playerControls.removeCallbacks(hideControls)
            playerControls.visibility = View.GONE
        }
    }

    private fun scheduleHideControls() {
        playerControls.removeCallbacks(hideControls)
        val playing = if (usingExo) exoPlayer?.isPlaying == true else playPauseBtn.text == "Pause"
        if (playing) playerControls.postDelayed(hideControls, 3000)
    }

    private fun onPlayPauseClicked() {
        if (loadedUrl == null) return
        if (usingExo) {
            val playingNow = exoPlayer?.isPlaying == true
            val pos = (exoPlayer?.currentPosition ?: 0L) / 1000.0
            exoPlayer?.playWhenReady = !playingNow
            pushState(pos, !playingNow)
            playPauseBtn.text = if (playingNow) "Play" else "Pause"
        } else if (youtubeMode) {
            playerWebView.evaluateJavascript(
                "(function(){if(!window.player||!player.getPlayerState)return '';" +
                    "var st=player.getPlayerState();var t=player.getCurrentTime();" +
                    "window.suppressUntil=Date.now()+1800;" +
                    "if(st===1){player.pauseVideo();return t+',0';}player.playVideo();return t+',1';})()"
            ) { raw ->
                val parts = raw?.trim()?.trim('"')?.split(',') ?: return@evaluateJavascript
                val t = parts.getOrNull(0)?.toDoubleOrNull() ?: return@evaluateJavascript
                val playing = parts.getOrNull(1) == "1"
                pushState(t, playing)
                playPauseBtn.text = if (playing) "Pause" else "Play"
            }
        }
        scheduleHideControls()
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
        holdChrome = false
        setWatchFullscreen(false)
        streamExtractor.cancel()
        pendingYoutubeId = null
        stopListening()
        currentRoomCode = null
        loadedUrl = null
        messages.clear()
        chatAdapter.notifyDataSetChanged()
        exoPlayer?.stop()
        backgroundPlay = false
        PlaybackService.stop(this)
        playerWebView.keepPlayingInBackground = false
        playerWebView.loadUrl("about:blank")
        playerPlaceholder.visibility = View.VISIBLE
        playerPlaceholder.text = "No media loaded"
        playerView?.visibility = View.GONE
        playerWebView.visibility = View.VISIBLE
        roomLayout.visibility = View.GONE
        lobbyLayout.visibility = View.VISIBLE
        refreshRooms()
    }

    private fun confirmDelete(entry: RoomEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete room")
            .setMessage("Delete ${entry.name}? This removes it for everyone.")
            .setPositiveButton("Delete") { _, _ ->
                db.child("rooms").child(entry.code).removeValue()
                removeLocalRoom(entry.code)
                refreshRooms()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopListening() {
        val code = listeningCode ?: return
        playerListener?.let { db.child("rooms").child(code).child("player").removeEventListener(it) }
        messageListener?.let { db.child("rooms").child(code).child("messages").removeEventListener(it) }
        playerListener = null
        messageListener = null
        listeningCode = null
    }

    private fun refreshRooms() {
        val codes = joinedRooms()
        if (codes.isEmpty()) {
            roomItems.clear()
            roomAdapter.notifyDataSetChanged()
            roomsEmpty.visibility = View.VISIBLE
            return
        }
        var pending = codes.size
        val found = mutableListOf<RoomEntry>()
        for (code in codes) {
            db.child("rooms").child(code).get().addOnCompleteListener { task ->
                val snap = task.result
                if (task.isSuccessful && snap != null && snap.exists()) {
                    val name = snap.child("name").getValue(String::class.java)
                        ?: prefs.getString("roomName_$code", "Room")
                        ?: "Room"
                    found.add(RoomEntry(code, name))
                } else if (task.isSuccessful) {
                    removeLocalRoom(code)
                }
                pending--
                if (pending == 0) {
                    roomItems.clear()
                    roomItems.addAll(found)
                    roomAdapter.notifyDataSetChanged()
                    roomsEmpty.visibility = if (roomItems.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun joinedRooms(): MutableList<String> {
        val raw = prefs.getString("joinedRooms", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
    }

    private fun saveJoined(code: String, name: String) {
        val list = joinedRooms()
        list.remove(code)
        list.add(0, code)
        prefs.edit().putString("joinedRooms", list.joinToString(",")).putString("roomName_$code", name).apply()
    }

    private fun removeLocalRoom(code: String) {
        val list = joinedRooms()
        list.remove(code)
        prefs.edit().putString("joinedRooms", list.joinToString(",")).remove("roomName_$code").apply()
    }

    private fun myDeviceId(): String {
        return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    private fun numberOf(snapshot: DataSnapshot, key: String): Double {
        val value = snapshot.child(key).value
        return if (value is Number) value.toDouble() else 0.0
    }

    private fun longOf(snapshot: DataSnapshot, key: String): Long {
        val value = snapshot.child(key).value
        return if (value is Number) value.toLong() else 0L
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
        if (!url.contains("drive.google.com") && !url.contains("docs.google.com") && !url.contains("drive.usercontent.google.com")) {
            return null
        }
        return Regex("/d/([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: Regex("[?&]id=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1)
    }

    private fun resourceKeyFrom(url: String?): String? {
        if (url == null) return null
        return Regex("[?&]resourcekey=([^&#]+)").find(url)?.groupValues?.get(1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::playerContainer.isInitialized) {
            playerContainer.removeCallbacks(heartbeat)
            playerContainer.removeCallbacks(progressTick)
        }
        if (::playerControls.isInitialized) playerControls.removeCallbacks(hideControls)
        if (::streamExtractor.isInitialized) streamExtractor.cancel()
        backgroundPlay = false
        PlaybackService.stop(this)
        stopListening()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    inner class YtBridge {
        @JavascriptInterface
        fun onPlayback(position: Double, playing: Boolean) {
            runOnUiThread {
                if (applyingRemote || !youtubeMode) return@runOnUiThread
                if (!acceptLocalEvents && playing && position < 1.5) return@runOnUiThread
                pushState(position, playing)
            }
        }
    }

    companion object {
        private const val APP_ORIGIN = "https://partypark.app"
    }
}

data class ChatMessage(val name: String, val text: String)

data class RoomEntry(val code: String, val name: String)

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

class RoomListAdapter(
    private val items: List<RoomEntry>,
    private val onOpen: (RoomEntry) -> Unit,
    private val onDelete: (RoomEntry) -> Unit
) : RecyclerView.Adapter<RoomListAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.room_item_name)
        val code: TextView = view.findViewById(R.id.room_item_code)
        val more: TextView = view.findViewById(R.id.room_item_more)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_room, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.code.text = item.code
        holder.itemView.setOnClickListener { onOpen(item) }
        holder.more.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size
}
