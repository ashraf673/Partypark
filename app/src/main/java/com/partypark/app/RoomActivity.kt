package com.partypark.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupMenu
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class RoomActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }
    private val db = Firebase.database.reference

    private lateinit var lobbyLayout: LinearLayout
    private lateinit var roomLayout: LinearLayout
    private lateinit var roomCodeText: TextView
    private lateinit var roomNameText: TextView
    private lateinit var onlineCount: TextView
    private lateinit var fsChat: LinearLayout
    private lateinit var typingText: TextView
    private lateinit var replyBar: LinearLayout
    private lateinit var replyPreview: TextView
    private lateinit var chatList: RecyclerView
    private lateinit var roomsList: RecyclerView
    private lateinit var roomsEmpty: TextView
    private lateinit var messageInput: EditText
    private lateinit var playerWebView: KeepAliveWebView
    private lateinit var playerPlaceholder: TextView
    private lateinit var playerContainer: FrameLayout
    private lateinit var seekHint: TextView
    private lateinit var gestureLayer: View
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
    private val videoQueue = mutableListOf<String>()
    private val playHistory = mutableListOf<String>()
    private val titles = HashMap<String, String>()
    private var captionsOn = false
    private var playbackSpeed = 1f
    private var inBackground = false
    private var backgroundPlay = false
    private var suppressSync = false
    private var pausedForReturn = false
    private lateinit var bgToggle: TextView
    private lateinit var queueText: TextView
    private lateinit var ytPlayCover: View
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
    private var queueListener: ValueEventListener? = null
    private var historyListener: ValueEventListener? = null
    private var membersListener: ValueEventListener? = null
    private var typingListener: ValueEventListener? = null
    private var listeningCode: String? = null
    private var replyTo: ChatMessage? = null
    private lateinit var playerSettings: ImageView
    private var forcedFullscreen = false
    private var lastSkipTs = 0L
    private var creatorId = ""
    private var everyoneAdmin = false
    private val adminIds = mutableSetOf<String>()
    private val onlineMembers = mutableListOf<OnlineMember>()
    private var roomMetaListener: ValueEventListener? = null
    private var kickedListener: ValueEventListener? = null
    private var overlayArmed = false
    private var overlayHidden = true
    private val overlayMessages = mutableListOf<ChatMessage>()
    private val typingNames = mutableListOf<String>()
    private val armOverlay = Runnable { overlayArmed = true }
    private val typingIdle = Runnable { clearTyping() }

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
                if (fullscreen && fsChat.visibility == View.VISIBLE && e.x > gestureLayer.width * 0.45f) {
                    overlayHidden = true
                    fsChat.visibility = View.GONE
                    return true
                }
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
        roomNameText = findViewById(R.id.room_name_text)
        onlineCount = findViewById(R.id.online_count)
        fsChat = findViewById(R.id.fs_chat)
        typingText = findViewById(R.id.typing_text)
        replyBar = findViewById(R.id.reply_bar)
        replyPreview = findViewById(R.id.reply_preview)
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
        playerControls = findViewById(R.id.player_controls)
        playPauseBtn = findViewById(R.id.play_pause_btn)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        playerSeek = findViewById(R.id.player_seek)
        queueText = findViewById(R.id.queue_text)
        ytPlayCover = findViewById(R.id.yt_play_cover)

        chatAdapter = ChatAdapter(messages, { myDeviceId() }, { showMessageMenu(it) }, { showReactors(it) })
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
        findViewById<ImageView>(R.id.room_menu_btn).setOnClickListener { showRoomMenu(it) }
        findViewById<MaterialButton>(R.id.load_btn).setOnClickListener { showMediaSources(replace = true) }
        findViewById<MaterialButton>(R.id.add_btn).setOnClickListener { showMediaSources(replace = false) }
        playerSettings = findViewById(R.id.player_settings)
        playerSettings.setOnClickListener { showPlayerSettings() }
        findViewById<ImageView>(R.id.fullscreen_btn).setOnClickListener { toggleManualFullscreen() }
        findViewById<ImageView>(R.id.prev_btn).setOnClickListener { playPrevious() }
        findViewById<ImageView>(R.id.next_btn).setOnClickListener { playNextFromQueue(fromUser = true) }
        bgToggle = findViewById(R.id.bg_toggle)
        refreshBgButton()
        bgToggle.setOnClickListener {
            val on = !bgEnabled()
            prefs.edit().putBoolean("bg_play_enabled", on).apply()
            refreshBgButton()
            if (!on) {
                backgroundPlay = false
                PlaybackService.stop(this)
            }
        }
        PlaybackService.onAction = { action ->
            runOnUiThread {
                when (action) {
                    PlaybackService.ACTION_PREV -> playPrevious()
                    PlaybackService.ACTION_NEXT -> playNextFromQueue(fromUser = true)
                    PlaybackService.ACTION_TOGGLE -> onPlayPauseClicked()
                }
                refreshNotification()
            }
        }
        findViewById<LinearLayout>(R.id.members_btn).setOnClickListener { showMembers() }
        findViewById<MaterialButton>(R.id.send_btn).setOnClickListener { sendMessage() }
        findViewById<TextView>(R.id.reply_cancel).setOnClickListener { clearReply() }
        messageInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pulseTyping(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        refreshRooms()
        playerContainer.postDelayed(heartbeat, 4000)
        playerContainer.post(progressTick)

        val openCode = intent.getStringExtra("room_code")
        if (!openCode.isNullOrBlank()) {
            lobbyLayout.visibility = View.GONE
            roomLayout.visibility = View.VISIBLE
            joinRoom(openCode)
        }
    }

    override fun onResume() {
        super.onResume()
        if (lobbyLayout.visibility == View.VISIBLE) refreshRooms()
    }

    override fun onPause() {
        val wasPlaying = mediaIsPlaying()
        if (bgEnabled() && wasPlaying) {
            backgroundPlay = true
            playerWebView.keepPlayingInBackground = true
            playerWebView.onResume()
            if (usingExo) {
                exoPlayer?.setForegroundMode(true)
                exoPlayer?.playWhenReady = true
            }
            PlaybackService.start(this, displayTitle(), true)
        } else {
            backgroundPlay = false
            if (wasPlaying) {
                pausedForReturn = true
                suppressSync = true
                if (usingExo) exoPlayer?.playWhenReady = false
                else playerWebView.evaluateJavascript(
                    "if(window.player&&player.pauseVideo){window.suppressUntil=Date.now()+4000;player.pauseVideo();}",
                    null
                )
                playerContainer.postDelayed({ suppressSync = false }, 1500)
            }
            PlaybackService.stop(this)
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        inBackground = true
        if (backgroundPlay) refreshNotification()
    }

    override fun onStart() {
        inBackground = false
        if (::playerWebView.isInitialized) playerWebView.keepPlayingInBackground = false
        exoPlayer?.setForegroundMode(false)
        if (pausedForReturn) {
            pausedForReturn = false
            suppressSync = true
            if (usingExo) exoPlayer?.playWhenReady = true
            else playerWebView.evaluateJavascript(
                "if(window.player&&player.playVideo){window.suppressUntil=Date.now()+2500;player.playVideo();}",
                null
            )
            if (::playerContainer.isInitialized) {
                playerContainer.postDelayed({ suppressSync = false }, 1500)
            }
        }
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
                if (inBackground) syncBackgroundService(isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    driveFallbackId = null
                    driveRetried = false
                    pendingYoutubeId = null
                    acceptLocalEvents = true
                    playerPlaceholder.visibility = View.GONE
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (inBackground) {
                        backgroundPlay = false
                        PlaybackService.stop(this@RoomActivity)
                    }
                    playNextFromQueue()
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
        if (!inBackground || !bgEnabled()) return
        backgroundPlay = true
        PlaybackService.start(this, displayTitle(), playing)
    }

    private fun bgEnabled(): Boolean = prefs.getBoolean("bg_play_enabled", false)

    private fun refreshBgButton() {
        if (!::bgToggle.isInitialized) return
        val on = bgEnabled()
        bgToggle.text = if (on) "BG On" else "BG Off"
        bgToggle.setTextColor(if (on) 0xFF4ADE80.toInt() else 0xFF94A3B8.toInt())
    }

    private fun displayTitle(): String {
        val url = loadedUrl ?: return "Partypark"
        return titles[url] ?: "Now playing"
    }

    private fun refreshNotification() {
        if (!inBackground || !backgroundPlay || !bgEnabled()) return
        val playing = if (usingExo) exoPlayer?.playWhenReady == true else playPauseBtn.text == "Pause"
        PlaybackService.start(this, displayTitle(), playing)
    }

    private fun showCreateDialog() {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        val input = EditText(this)
        input.hint = "Room name"
        val toggle = androidx.appcompat.widget.SwitchCompat(this)
        toggle.text = "Admin permission to everyone"
        toggle.setPadding(0, dp(12), 0, 0)
        wrap.addView(input)
        wrap.addView(toggle)
        AlertDialog.Builder(this)
            .setTitle("Create Room")
            .setView(wrap)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Room" }
                createRoom(name, toggle.isChecked)
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

    private fun createRoom(name: String, everyone: Boolean) {
        val code = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
        val myName = myName()
        val myId = myDeviceId()
        val roomData = mapOf(
            "name" to name,
            "createdAt" to System.currentTimeMillis(),
            "creatorId" to myId,
            "everyoneAdmin" to everyone,
            "members" to mapOf(myId to mapOf("name" to myName, "online" to true, "avatar" to Profile.avatar(this)))
        )
        db.child("rooms").child(code).setValue(roomData).addOnSuccessListener {
            postJoin(code, myName)
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
                .get()
                .addOnSuccessListener { member ->
                    val wasOnline = member.child("online").getValue(Boolean::class.java) == true
                    db.child("rooms").child(code).child("members").child(myDeviceId())
                        .setValue(mapOf("name" to myName, "online" to true, "avatar" to Profile.avatar(this)))
                    if (!wasOnline) postJoin(code, myName)
                    enterRoom(code, name)
                }
        }
    }

    private fun enterRoom(code: String, name: String) {
        saveJoined(code, name)
        currentRoomCode = code
        roomNameText.text = name
        roomCodeText.text = code
        lobbyLayout.visibility = View.GONE
        roomLayout.visibility = View.VISIBLE
        messages.clear()
        chatAdapter.notifyDataSetChanged()
        loadedUrl = null
        lastAppliedTs = 0L
        hidePlayerControls()
        overlayMessages.clear()
        overlayHidden = true
        overlayArmed = false
        fsChat.removeAllViews()
        fsChat.visibility = View.GONE
        clearReply()
        playerContainer.removeCallbacks(armOverlay)
        playerContainer.postDelayed(armOverlay, 1200)
        listenToRoom(code)
        applyOrientation()
    }

    private fun listenToRoom(code: String) {
        stopListening()
        listeningCode = code

        messageListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                upsertMessage(messageFrom(snapshot))
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                upsertMessage(messageFrom(snapshot))
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val key = snapshot.key ?: return
                val index = messages.indexOfFirst { it.key == key }
                if (index >= 0) {
                    messages.removeAt(index)
                    chatAdapter.notifyItemRemoved(index)
                }
                overlayMessages.removeAll { it.key == key }
                renderOverlay()
            }
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

        queueListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remote = mutableListOf<String>()
                for (child in snapshot.children) {
                    child.getValue(String::class.java)?.let { remote.add(it) }
                }
                if (remote != videoQueue) {
                    videoQueue.clear()
                    videoQueue.addAll(remote)
                    updateQueueText()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).child("queue").addValueEventListener(queueListener!!)

        historyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val remote = mutableListOf<String>()
                for (child in snapshot.children) {
                    child.getValue(String::class.java)?.let { remote.add(it) }
                }
                if (remote != playHistory) {
                    playHistory.clear()
                    playHistory.addAll(remote)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).child("history").addValueEventListener(historyListener!!)

        membersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onlineMembers.clear()
                for (child in snapshot.children) {
                    if (child.child("online").getValue(Boolean::class.java) != true) continue
                    onlineMembers.add(
                        OnlineMember(
                            child.key ?: "",
                            child.child("name").getValue(String::class.java) ?: "Guest",
                            child.child("avatar").getValue(String::class.java) ?: ""
                        )
                    )
                }
                onlineCount.text = onlineMembers.size.toString()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).child("members").addValueEventListener(membersListener!!)

        roomMetaListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                creatorId = snapshot.child("creatorId").getValue(String::class.java) ?: creatorId
                everyoneAdmin = snapshot.child("everyoneAdmin").getValue(Boolean::class.java) == true
                adminIds.clear()
                for (child in snapshot.child("admins").children) {
                    if (child.getValue(Boolean::class.java) == true) child.key?.let { adminIds.add(it) }
                }
                val roomName = snapshot.child("name").getValue(String::class.java)
                if (!roomName.isNullOrBlank()) roomNameText.text = roomName
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).addValueEventListener(roomMetaListener!!)

        kickedListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) == true) {
                    Toast.makeText(this@RoomActivity, "You were removed from the room", Toast.LENGTH_SHORT).show()
                    removeLocalRoom(code)
                    leaveRoom()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).child("kicked").child(myDeviceId()).addValueEventListener(kickedListener!!)

        typingListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = System.currentTimeMillis()
                typingNames.clear()
                for (child in snapshot.children) {
                    if (child.key == myDeviceId()) continue
                    val at = child.child("at").getValue(Long::class.java)
                        ?: child.child("at").getValue(Double::class.java)?.toLong()
                        ?: 0L
                    if (now - at > 4000) continue
                    typingNames.add(child.child("name").getValue(String::class.java) ?: "Someone")
                }
                showTyping()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("rooms").child(code).child("typing").addValueEventListener(typingListener!!)
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
        val skip = snapshot.child("skip").getValue(String::class.java)
        if (!skip.isNullOrBlank() && actionTs > lastSkipTs) {
            lastSkipTs = actionTs
            flash(skip)
        }
        if (actionTs <= lastAppliedTs) return
        lastAppliedTs = actionTs
        val rawElapsed = if (playing && actionTs > 0) {
            (System.currentTimeMillis() - actionTs) / 1000.0
        } else 0.0
        val elapsed = rawElapsed.coerceIn(0.0, 4.0)
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
        // Do not push the playhead on a timer. Buffering and clock differences
        // were being treated as seeks and jumped the movie for everyone.
    }

    private fun pushState(position: Double, playing: Boolean, skip: String? = null) {
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
            "by" to myDeviceId(),
            "skip" to (skip ?: "")
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
            pushState(t, true, if (deltaSec > 0) "+10" else "-10")
            flash(if (deltaSec > 0) "+10" else "-10")
        }
    }

    private fun seekBy(deltaSec: Double) {
        if (loadedUrl == null) return
        if (usingExo) {
            val now = (exoPlayer?.currentPosition ?: 0L) / 1000.0
            val duration = (exoPlayer?.duration ?: 0L) / 1000.0
            var target = (now + deltaSec).coerceAtLeast(0.0)
            if (duration > 0) target = target.coerceAtMost(duration)
            pushState(target, exoPlayer?.playWhenReady == true, if (deltaSec > 0) "+10" else "-10")
            applyingRemote = true
            exoPlayer?.seekTo((target * 1000).toLong())
            playerContainer.postDelayed({ applyingRemote = false }, 400)
            flash(if (deltaSec > 0) "+10" else "-10")
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
        val payload = hashMapOf<String, Any>(
            "name" to myName,
            "senderId" to myDeviceId(),
            "avatar" to Profile.avatar(this),
            "text" to text,
            "kind" to "chat",
            "ts" to System.currentTimeMillis()
        )
        replyTo?.let {
            payload["replyName"] = it.name
            payload["replyText"] = it.text
        }
        db.child("rooms").child(code).child("messages").push().setValue(payload)
        messageInput.text.clear()
        clearReply()
        clearTyping()
    }

    private fun showRoomMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Settings")
        popup.menu.add("Full screen")
        popup.menu.add("Share")
        popup.menu.add("Leave")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Settings" -> showRoomSettings()
                "Full screen" -> toggleManualFullscreen()
                "Share" -> shareRoom()
                "Leave" -> leaveRoom()
            }
            true
        }
        popup.show()
    }

    private fun showMediaSources(replace: Boolean) {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        val input = EditText(this)
        input.hint = "Paste a video link"
        val paste = MaterialButton(this)
        paste.text = if (replace) "Use this link" else "Add this link"
        val youtube = MaterialButton(this)
        youtube.text = "YouTube"
        val playlist = MaterialButton(this)
        playlist.text = "Playlist"
        wrap.addView(input)
        wrap.addView(paste)
        wrap.addView(youtube)
        wrap.addView(playlist)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (replace) "Load Video" else "Add Video")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .create()
        paste.setOnClickListener {
            val url = input.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            if (replace) loadAndSync(url) else addToQueue(url)
        }
        youtube.setOnClickListener {
            dialog.dismiss()
            openYoutubePicker()
        }
        playlist.setOnClickListener {
            dialog.dismiss()
            pickFromPlaylists()
        }
        dialog.show()
    }

    private fun openYoutubePicker() {
        PickBus.onRoomPick = { url, title, top ->
            runOnUiThread {
                titles[url] = title
                if (top) videoQueue.add(0, url) else videoQueue.add(url)
                updateQueueText()
                syncQueue()
                Toast.makeText(this, "Queue: ${videoQueue.size}", Toast.LENGTH_SHORT).show()
                if (loadedUrl.isNullOrBlank()) playNextFromQueue()
            }
        }
        startActivity(Intent(this, YoutubePickActivity::class.java)
            .putExtra(YoutubePickActivity.EXTRA_MODE, YoutubePickActivity.MODE_ROOM))
    }

    private fun pickFromPlaylists() {
        val lists = PlaylistStore.load(this)
        if (lists.isEmpty()) {
            Toast.makeText(this, "No playlists yet. Create one from Home.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = lists.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Playlist")
            .setItems(names) { _, which -> askPlaylistMode(lists[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askPlaylistMode(playlist: UserPlaylist) {
        if (playlist.videos.isEmpty()) {
            Toast.makeText(this, "This playlist is empty", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(arrayOf("Add whole playlist", "Choose videos")) { _, which ->
                if (which == 0) {
                    playlist.videos.forEach { titles[it.url] = it.title }
                    addMany(playlist.videos.map { it.url })
                } else {
                    chooseVideos(playlist)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseVideos(playlist: UserPlaylist) {
        val labels = playlist.videos.map { it.title.ifBlank { it.url } }.toTypedArray()
        val checked = BooleanArray(labels.size)
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Done") { _, _ ->
                val picked = playlist.videos.filterIndexed { index, _ -> checked[index] }
                picked.forEach { titles[it.url] = it.title }
                addMany(picked.map { it.url })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addMany(urls: List<String>) {
        if (urls.isEmpty()) return
        videoQueue.addAll(urls)
        updateQueueText()
        syncQueue()
        Toast.makeText(this, "Added ${urls.size}", Toast.LENGTH_SHORT).show()
        if (loadedUrl.isNullOrBlank()) playNextFromQueue()
    }

    private fun addToQueue(url: String) {
        videoQueue.add(url)
        updateQueueText()
        syncQueue()
        Toast.makeText(this, "Added. Queue: ${videoQueue.size}", Toast.LENGTH_SHORT).show()
        if (loadedUrl.isNullOrBlank()) playNextFromQueue()
    }

    private fun playNextFromQueue(fromUser: Boolean = false) {
        if (videoQueue.isEmpty()) {
            if (fromUser) Toast.makeText(this, "No next video", Toast.LENGTH_SHORT).show()
            return
        }
        val current = loadedUrl
        if (!current.isNullOrBlank()) playHistory.add(current)
        val next = videoQueue.removeAt(0)
        updateQueueText()
        syncQueue()
        syncHistory()
        loadAndSync(next)
        refreshNotification()
    }

    private fun playPrevious() {
        if (playHistory.isEmpty()) {
            Toast.makeText(this, "No previous video", Toast.LENGTH_SHORT).show()
            return
        }
        val previous = playHistory.removeAt(playHistory.lastIndex)
        val current = loadedUrl
        if (!current.isNullOrBlank()) videoQueue.add(0, current)
        updateQueueText()
        syncQueue()
        syncHistory()
        loadAndSync(previous)
        refreshNotification()
    }

    private fun syncHistory() {
        val code = currentRoomCode ?: return
        db.child("rooms").child(code).child("history").setValue(playHistory.toList())
    }

    private fun updateQueueText() {
        if (::queueText.isInitialized) queueText.text = "Queue: ${videoQueue.size}"
    }

    private fun syncQueue() {
        val code = currentRoomCode ?: return
        db.child("rooms").child(code).child("queue").setValue(videoQueue.toList())
    }

    private fun showPlayerSettings() {
        val speeds = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
        val speedValues = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val items = arrayOf(
            "Speed: ${playbackSpeed}x",
            if (captionsOn) "Captions: On" else "Captions: Off",
            "Quality: ${qualityLabel()}"
        )
        AlertDialog.Builder(this)
            .setTitle("Player settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("Playback speed")
                        .setItems(speeds) { _, i ->
                            playbackSpeed = speedValues[i]
                            applySpeed()
                        }
                        .show()
                    1 -> {
                        captionsOn = !captionsOn
                        applyCaptions()
                        Toast.makeText(this, if (captionsOn) "Captions on" else "Captions off", Toast.LENGTH_SHORT).show()
                    }
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Quality")
                        .setItems(arrayOf("144p", "High", "Auto")) { _, i ->
                            val value = arrayOf("144", "high", "auto")[i]
                            prefs.edit().putString("playback_quality", value).apply()
                            reloadCurrentQuality()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun qualityLabel(): String = when (prefs.getString("playback_quality", "auto")) {
        "144" -> "144p"
        "high" -> "High"
        else -> "Auto"
    }

    private fun reloadCurrentQuality() {
        val url = loadedUrl ?: return
        val id = youtubeId(url) ?: return
        val pos = if (usingExo) (exoPlayer?.currentPosition ?: 0L) / 1000.0 else 0.0
        val playing = if (usingExo) exoPlayer?.playWhenReady == true else playPauseBtn.text == "Pause"
        playYoutube(id, pos, playing)
    }

    private fun applySpeed() {
        if (usingExo) {
            exoPlayer?.setPlaybackSpeed(playbackSpeed)
        } else if (youtubeMode) {
            playerWebView.evaluateJavascript(
                "if(window.player&&player.setPlaybackRate)player.setPlaybackRate($playbackSpeed);",
                null
            )
        }
    }

    private fun applyCaptions() {
        if (youtubeMode) {
            val js = if (captionsOn) {
                "if(window.player){try{player.loadModule('captions');player.setOption('captions','track',{languageCode:'en'});}catch(e){}}"
            } else {
                "if(window.player){try{player.unloadModule('captions');player.setOption('captions','track',{});}catch(e){}}"
            }
            playerWebView.evaluateJavascript(js, null)
        }
        ytPlayCover.visibility = if (youtubeMode && !captionsOn) View.GONE else ytPlayCover.visibility
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
        streamExtractor.extractYoutube(id, prefs.getString("playback_quality", "auto") ?: "auto") { video, audio ->
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
        val quality = when (prefs.getString("playback_quality", "auto")) {
            "144" -> "tiny"
            "high" -> "hd1080"
            else -> "default"
        }
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
              <style>
                html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
                #wrap{position:relative;width:100%;height:100%;background:#000}
                #player{width:100%;height:100%}
                #cover{position:absolute;left:50%;top:46%;width:92px;height:92px;margin-left:-46px;background:#000;border-radius:46px;z-index:5;pointer-events:none}
              </style>
            </head>
            <body>
              <div id="wrap">
                <div id="player"></div>
                <div id="cover"></div>
              </div>
              <script src="https://www.youtube.com/iframe_api"></script>
              <script>
                var player;
                window.suppressUntil = 0;
                function cover(show){var c=document.getElementById('cover'); if(c) c.style.display=show?'block':'none';}
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
                      cc_load_policy: 0,
                      start: $start,
                      origin: '$APP_ORIGIN'
                    },
                    events: {
                      onReady: function(e){
                        try { e.target.setPlaybackQuality('$quality'); } catch (err) {}
                        if ($autoplay === 1) e.target.playVideo();
                      },
                      onStateChange: onPlayerStateChange
                    }
                  });
                }
                function onPlayerStateChange(e) {
                  cover(e.data !== 1);
                  if (Date.now() < window.suppressUntil || !player || !player.getCurrentTime) return;
                  if (e.data === YT.PlayerState.ENDED) Android.onEnded();
                }
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
        if (!landscape && !forcedFullscreen) holdChrome = false
        setWatchFullscreen(inRoom && !holdChrome && (landscape || forcedFullscreen))
    }

    private fun toggleManualFullscreen() {
        if (currentRoomCode == null) return
        if (fullscreen) {
            forcedFullscreen = false
            holdChrome = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            setWatchFullscreen(false)
        } else {
            forcedFullscreen = true
            holdChrome = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            setWatchFullscreen(true)
        }
    }

    @Suppress("DEPRECATION")
    private fun setWatchFullscreen(on: Boolean) {
        if (on == fullscreen) return
        fullscreen = on
        val header = findViewById<View>(R.id.room_header)
        val buttons = findViewById<View>(R.id.video_buttons)
        val queue = findViewById<View>(R.id.queue_text)
        val messageBar = findViewById<View>(R.id.message_bar)
        val playerLp = playerContainer.layoutParams as LinearLayout.LayoutParams
        if (on) {
            supportActionBar?.hide()
            header.visibility = View.GONE
            buttons.visibility = View.GONE
            queue.visibility = View.GONE
            chatList.visibility = View.GONE
            messageBar.visibility = View.GONE
            findViewById<View>(R.id.typing_text).visibility = View.GONE
            findViewById<View>(R.id.reply_bar).visibility = View.GONE
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
            buttons.visibility = View.VISIBLE
            queue.visibility = View.VISIBLE
            chatList.visibility = View.VISIBLE
            messageBar.visibility = View.VISIBLE
            if (replyTo != null) replyBar.visibility = View.VISIBLE
            showTyping()
            playerLp.height = dp(220)
            playerLp.weight = 0f
            overlayHidden = true
            fsChat.visibility = View.GONE
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
        playerSettings.visibility = View.VISIBLE
        playerControls.bringToFront()
        playerSettings.bringToFront()
        refreshProgress()
        scheduleHideControls()
    }

    private fun hidePlayerControls() {
        controlsVisible = false
        if (::playerControls.isInitialized) {
            playerControls.removeCallbacks(hideControls)
            playerControls.visibility = View.GONE
        }
        if (::playerSettings.isInitialized) playerSettings.visibility = View.GONE
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
        refreshNotification()
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
        val code = currentRoomCode
        if (code != null) markOffline(code)
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
        finish()
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
        queueListener?.let { db.child("rooms").child(code).child("queue").removeEventListener(it) }
        historyListener?.let { db.child("rooms").child(code).child("history").removeEventListener(it) }
        membersListener?.let { db.child("rooms").child(code).child("members").removeEventListener(it) }
        typingListener?.let { db.child("rooms").child(code).child("typing").removeEventListener(it) }
        roomMetaListener?.let { db.child("rooms").child(code).removeEventListener(it) }
        kickedListener?.let {
            db.child("rooms").child(code).child("kicked").child(myDeviceId()).removeEventListener(it)
        }
        playerListener = null
        messageListener = null
        queueListener = null
        historyListener = null
        membersListener = null
        typingListener = null
        roomMetaListener = null
        kickedListener = null
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
            playerContainer.removeCallbacks(armOverlay)
        }
        if (::playerControls.isInitialized) playerControls.removeCallbacks(hideControls)
        if (::messageInput.isInitialized) messageInput.removeCallbacks(typingIdle)
        currentRoomCode?.let { markOffline(it) }
        if (::streamExtractor.isInitialized) streamExtractor.cancel()
        backgroundPlay = false
        PlaybackService.onAction = null
        PickBus.onRoomPick = null
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
                if (!youtubeMode) return@runOnUiThread
                ytPlayCover.visibility = if (playing) View.GONE else View.VISIBLE
            }
        }

        @JavascriptInterface
        fun onEnded() {
            runOnUiThread { playNextFromQueue() }
        }
    }

    private fun postJoin(code: String, name: String) {
        db.child("rooms").child(code).child("messages").push().setValue(
            mapOf(
                "name" to name,
                "senderId" to myDeviceId(),
                "text" to "$name joined the room",
                "kind" to "join",
                "ts" to System.currentTimeMillis()
            )
        )
    }

    private fun markOffline(code: String) {
        db.child("rooms").child(code).child("members").child(myDeviceId()).child("online").setValue(false)
        db.child("rooms").child(code).child("typing").child(myDeviceId()).removeValue()
    }

    private fun pulseTyping(text: String) {
        val code = currentRoomCode ?: return
        messageInput.removeCallbacks(typingIdle)
        if (text.isBlank()) {
            clearTyping()
            return
        }
        val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
        db.child("rooms").child(code).child("typing").child(myDeviceId()).setValue(
            mapOf("name" to myName, "at" to System.currentTimeMillis())
        )
        messageInput.postDelayed(typingIdle, 2500)
    }

    private fun clearTyping() {
        val code = currentRoomCode ?: return
        db.child("rooms").child(code).child("typing").child(myDeviceId()).removeValue()
    }

    private fun showTyping() {
        if (!::typingText.isInitialized) return
        if (typingNames.isEmpty() || fullscreen) {
            typingText.visibility = View.GONE
        } else {
            typingText.visibility = View.VISIBLE
            typingText.text = when (typingNames.size) {
                1 -> "${typingNames[0]} is typing"
                2 -> "${typingNames[0]} and ${typingNames[1]} are typing"
                else -> "${typingNames[0]} and ${typingNames.size - 1} others are typing"
            }
        }
        if (fullscreen && fsChat.visibility == View.VISIBLE) renderOverlay()
    }

    private fun clearReply() {
        replyTo = null
        if (::replyBar.isInitialized) replyBar.visibility = View.GONE
    }

    private fun showMembers() {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(16), dp(8), dp(16), dp(8))
        if (onlineMembers.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Nobody is online"
            empty.setTextColor(android.graphics.Color.WHITE)
            wrap.addView(empty)
        }
        for (member in onlineMembers) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(8), 0, dp(8))
            val face = TextView(this)
            face.text = member.avatar.ifBlank { "\uD83D\uDE0E" }
            face.textSize = 20f
            val label = TextView(this)
            label.text = member.name
            label.setTextColor(android.graphics.Color.WHITE)
            label.textSize = 16f
            label.setPadding(dp(10), 0, dp(8), 0)
            label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(face)
            row.addView(label)
            if (canKick(member.id)) {
                val kick = TextView(this)
                kick.text = "Kick"
                kick.setTextColor(0xFFF87171.toInt())
                kick.textSize = 14f
                kick.setOnClickListener { kickMember(member) }
                row.addView(kick)
            }
            wrap.addView(row)
        }
        AlertDialog.Builder(this)
            .setTitle("Online (${onlineMembers.size})")
            .setView(wrap)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun canKick(targetId: String): Boolean {
        if (targetId.isBlank() || targetId == myDeviceId() || targetId == creatorId) return false
        if (everyoneAdmin) return true
        if (myDeviceId() == creatorId) return true
        return adminIds.contains(myDeviceId())
    }

    private fun kickMember(member: OnlineMember) {
        val code = currentRoomCode ?: return
        db.child("rooms").child(code).child("kicked").child(member.id).setValue(true)
        db.child("rooms").child(code).child("members").child(member.id).removeValue()
        db.child("rooms").child(code).child("admins").child(member.id).removeValue()
        Toast.makeText(this, "${member.name} removed", Toast.LENGTH_SHORT).show()
    }

    private fun showRoomSettings() {
        val options = mutableListOf("Rename")
        if (myDeviceId() == creatorId) options.add("Promote as admin")
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options.toTypedArray()) { _, which ->
                if (options[which] == "Rename") renameRoom() else promoteAdmins()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun renameRoom() {
        val code = currentRoomCode ?: return
        val input = EditText(this)
        input.setText(roomNameText.text)
        AlertDialog.Builder(this)
            .setTitle("Rename room")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Room" }
                db.child("rooms").child(code).child("name").setValue(name)
                roomNameText.text = name
                prefs.edit().putString("roomName_$code", name).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promoteAdmins() {
        val code = currentRoomCode ?: return
        if (onlineMembers.isEmpty()) {
            Toast.makeText(this, "Nobody else is here", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = onlineMembers.map { it.name }.toTypedArray()
        val checked = onlineMembers.map { adminIds.contains(it.id) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Promote as admin")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Save") { _, _ ->
                onlineMembers.forEachIndexed { index, member ->
                    val ref = db.child("rooms").child(code).child("admins").child(member.id)
                    if (checked[index] && member.id != creatorId) ref.setValue(true) else ref.removeValue()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun messageFrom(snapshot: DataSnapshot): ChatMessage {
        val reactions = linkedMapOf<String, Pair<String, String>>()
        for (child in snapshot.child("reactions").children) {
            val id = child.key ?: continue
            val emoji = child.child("emoji").getValue(String::class.java)
                ?: child.getValue(String::class.java)
                ?: continue
            val name = child.child("name").getValue(String::class.java) ?: "Someone"
            reactions[id] = emoji to name
        }
        val ts = snapshot.child("ts").getValue(Long::class.java)
            ?: snapshot.child("ts").getValue(Double::class.java)?.toLong()
            ?: 0L
        return ChatMessage(
            key = snapshot.key ?: "",
            name = snapshot.child("name").getValue(String::class.java) ?: "",
            senderId = snapshot.child("senderId").getValue(String::class.java) ?: "",
            text = snapshot.child("text").getValue(String::class.java) ?: "",
            ts = ts,
            kind = snapshot.child("kind").getValue(String::class.java) ?: "chat",
            replyName = snapshot.child("replyName").getValue(String::class.java),
            replyText = snapshot.child("replyText").getValue(String::class.java),
            reactions = reactions,
            avatar = snapshot.child("avatar").getValue(String::class.java) ?: ""
        )
    }

    private fun upsertMessage(msg: ChatMessage) {
        val index = messages.indexOfFirst { it.key == msg.key && msg.key.isNotEmpty() }
        if (index >= 0) {
            messages[index] = msg
            chatAdapter.notifyItemChanged(index)
            val overlayIndex = overlayMessages.indexOfFirst { it.key == msg.key }
            if (overlayIndex >= 0) {
                overlayMessages[overlayIndex] = msg
                renderOverlay()
            }
            return
        }
        messages.add(msg)
        chatAdapter.notifyItemInserted(messages.lastIndex)
        chatList.scrollToPosition(messages.lastIndex)
        if (overlayArmed && fullscreen && msg.kind == "chat") {
            overlayHidden = false
            overlayMessages.add(msg)
            while (overlayMessages.size > 6) overlayMessages.removeAt(0)
            renderOverlay()
        }
    }

    private fun renderOverlay() {
        if (!::fsChat.isInitialized) return
        fsChat.removeAllViews()
        if (!fullscreen || overlayHidden || overlayMessages.isEmpty()) {
            fsChat.visibility = View.GONE
            return
        }
        fsChat.visibility = View.VISIBLE
        val mineId = myDeviceId()
        val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
        for (msg in overlayMessages) {
            val mine = msg.senderId == mineId || (msg.senderId.isEmpty() && msg.name == myName)
            val column = LinearLayout(this)
            column.orientation = LinearLayout.VERTICAL
            column.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            column.gravity = android.view.Gravity.END
            if (!mine) {
                val who = TextView(this)
                who.text = "${msg.avatar} ${msg.name}".trim()
                who.setTextColor(0xFFF0AB9D.toInt())
                who.textSize = 11f
                who.gravity = android.view.Gravity.END
                column.addView(who)
            }
            val bubble = TextView(this)
            bubble.text = msg.text
            bubble.setTextColor(android.graphics.Color.WHITE)
            bubble.textSize = 14f
            bubble.maxWidth = dp(200)
            bubble.setPadding(dp(10), dp(6), dp(10), dp(6))
            bubble.setBackgroundResource(if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_other)
            column.addView(bubble)
            fsChat.addView(column)
        }
        if (typingNames.isNotEmpty()) {
            val typing = TextView(this)
            typing.text = "${typingNames.joinToString(", ")} is typing"
            typing.setTextColor(0xFFC4B5FD.toInt())
            typing.textSize = 12f
            fsChat.addView(typing)
        }
    }

    private fun showMessageMenu(msg: ChatMessage) {
        if (msg.kind == "join" || fullscreen) return
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(16), dp(12), dp(16), dp(4))
        val time = TextView(this)
        time.text = if (msg.ts > 0) SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(msg.ts)) else ""
        time.gravity = android.view.Gravity.CENTER
        time.setTextColor(android.graphics.Color.WHITE)
        time.textSize = 14f
        wrap.addView(time)
        val reactions = LinearLayout(this)
        reactions.orientation = LinearLayout.HORIZONTAL
        reactions.gravity = android.view.Gravity.CENTER
        val dialog = AlertDialog.Builder(this).setView(wrap).create()
        fun addEmoji(emoji: String) {
            val button = TextView(this)
            button.text = emoji
            button.textSize = 22f
            button.setPadding(dp(10), dp(8), dp(10), dp(8))
            button.setOnClickListener {
                react(msg, emoji)
                dialog.dismiss()
            }
            reactions.addView(button)
        }
        QUICK_REACTIONS.forEach { addEmoji(it) }
        val more = ImageView(this)
        more.setImageResource(R.drawable.ic_add)
        more.setPadding(dp(8), dp(8), dp(8), dp(8))
        more.setOnClickListener {
            dialog.dismiss()
            AlertDialog.Builder(this)
                .setTitle("Reaction")
                .setItems(MORE_REACTIONS.toTypedArray()) { _, which -> react(msg, MORE_REACTIONS[which]) }
                .show()
        }
        reactions.addView(more)
        wrap.addView(reactions)
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        fun action(label: String, onClick: () -> Unit) {
            val button = TextView(this)
            button.text = label
            button.setTextColor(android.graphics.Color.WHITE)
            button.textSize = 14f
            button.setPadding(dp(12), dp(12), dp(12), dp(12))
            button.setOnClickListener {
                dialog.dismiss()
                onClick()
            }
            actions.addView(button)
        }
        action("Reply") {
            replyTo = msg
            replyPreview.text = "Reply to ${msg.name}: ${msg.text}"
            replyBar.visibility = View.VISIBLE
        }
        action("Delete") {
            val mine = msg.senderId == myDeviceId() || (msg.senderId.isEmpty() && msg.name == myName())
            if (!mine) {
                Toast.makeText(this, "You can only delete your own message", Toast.LENGTH_SHORT).show()
            } else {
                val code = currentRoomCode ?: return@action
                db.child("rooms").child(code).child("messages").child(msg.key).removeValue()
            }
        }
        action("Copy") {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("message", msg.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        wrap.addView(actions)
        dialog.show()
    }

    private fun react(msg: ChatMessage, emoji: String) {
        val code = currentRoomCode ?: return
        if (msg.key.isEmpty()) return
        val ref = db.child("rooms").child(code).child("messages").child(msg.key)
            .child("reactions").child(myDeviceId())
        val current = msg.reactions[myDeviceId()]?.first
        if (current == emoji) ref.removeValue()
        else ref.setValue(mapOf("emoji" to emoji, "name" to myName()))
    }

    private fun showReactors(msg: ChatMessage) {
        if (msg.reactions.isEmpty()) return
        val lines = msg.reactions.values.map { "${it.first}  ${it.second}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Reactions")
            .setItems(lines, null)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun myName(): String = prefs.getString("deviceName", "Guest") ?: "Guest"

    companion object {
        private const val APP_ORIGIN = "https://partypark.app"
        private val QUICK_REACTIONS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE22")
        private val MORE_REACTIONS = listOf("\uD83D\uDD25", "\uD83D\uDC4F", "\uD83D\uDE2E", "\uD83C\uDF89", "\uD83D\uDE4F", "\uD83D\uDE0E", "\uD83E\uDD23", "\uD83D\uDE0D")
    }
}

data class ChatMessage(
    val key: String,
    val name: String,
    val senderId: String,
    val text: String,
    val ts: Long,
    val kind: String,
    val replyName: String?,
    val replyText: String?,
    val reactions: Map<String, Pair<String, String>>,
    val avatar: String
)

data class OnlineMember(val id: String, val name: String, val avatar: String)

data class RoomEntry(val code: String, val name: String)

class ChatAdapter(
    private val items: List<ChatMessage>,
    private val myId: () -> String,
    private val onLongPress: (ChatMessage) -> Unit,
    private val onReaction: (ChatMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int {
        val msg = items[position]
        if (msg.kind == "join") return 2
        return if (msg.senderId == myId() || (msg.senderId.isEmpty() && false)) 0 else 1
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = android.view.LayoutInflater.from(parent.context)
        val layout = when (viewType) {
            0 -> R.layout.item_chat_mine
            2 -> R.layout.item_chat_join
            else -> R.layout.item_chat_other
        }
        return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = items[position]
        val view = holder.itemView
        val textView: TextView? = if (view is TextView) view else view.findViewById(R.id.chat_text)
        textView?.text = msg.text
        view.findViewById<TextView>(R.id.chat_name)?.text = msg.name
        view.findViewById<TextView>(R.id.chat_avatar)?.text = msg.avatar
        val reply = view.findViewById<TextView>(R.id.chat_reply)
        if (reply != null) {
            if (msg.replyText.isNullOrBlank()) {
                reply.visibility = View.GONE
            } else {
                reply.visibility = View.VISIBLE
                reply.text = "${msg.replyName}: ${msg.replyText}"
            }
        }
        val reactions = view.findViewById<TextView>(R.id.chat_reactions)
        if (reactions != null) {
            if (msg.reactions.isEmpty()) {
                reactions.visibility = View.GONE
            } else {
                reactions.visibility = View.VISIBLE
                reactions.text = msg.reactions.values.groupBy { it.first }
                    .entries.joinToString("  ") { "${it.key} ${it.value.size}" }
                reactions.setOnClickListener { onReaction(msg) }
            }
        }
        if (msg.kind != "join") {
            view.setOnLongClickListener {
                onLongPress(msg)
                true
            }
        }
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
