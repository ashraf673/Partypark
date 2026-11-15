package com.partypark.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

        chatAdapter = ChatAdapter(messages)
        chatList.layoutManager = LinearLayoutManager(this)
        chatList.adapter = chatAdapter

        setupWebView()

        findViewById<MaterialButton>(R.id.create_room_btn).setOnClickListener { showCreateDialog() }
        findViewById<MaterialButton>(R.id.join_room_btn).setOnClickListener { showJoinDialog() }
        findViewById<MaterialButton>(R.id.share_btn).setOnClickListener { shareRoom() }
        findViewById<MaterialButton>(R.id.leave_btn).setOnClickListener { leaveRoom() }
        findViewById<MaterialButton>(R.id.load_btn).setOnClickListener { showLoadDialog() }
        findViewById<MaterialButton>(R.id.send_btn).setOnClickListener { sendMessage() }
    }

    private fun setupWebView() {
        playerWebView.settings.javaScriptEnabled = true
        playerWebView.settings.mediaPlaybackRequiresUserGesture = false
        playerWebView.webChromeClient = WebChromeClient()
        playerWebView.webViewClient = WebViewClient()
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
        lobbyLayout.visibility = android.view.View.GONE
        roomLayout.visibility = android.view.View.VISIBLE
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
        input.hint = "YouTube or Drive URL"
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
        playerPlaceholder.visibility = android.view.View.GONE
        val embed = if (url.contains("youtube.com") || url.contains("youtu.be")) {
            val id = extractYoutubeId(url)
            "https://www.youtube.com/embed/$id?autoplay=1"
        } else {
            url
        }
        playerWebView.loadUrl(embed)
    }

    private fun extractYoutubeId(url: String): String {
        return when {
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            else -> url
        }
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
        roomLayout.visibility = android.view.View.GONE
        lobbyLayout.visibility = android.view.View.VISIBLE
        playerWebView.loadUrl("about:blank")
        playerPlaceholder.visibility = android.view.View.VISIBLE
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