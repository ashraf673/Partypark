package com.partypark.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }
    private val db by lazy { Firebase.database.reference }
    private val roomItems = mutableListOf<RoomEntry>()
    private lateinit var roomAdapter: RoomListAdapter
    private var allRooms = listOf<RoomEntry>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Log.w("Partypark", "Permissions denied: $denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = Firebase.auth
        requestBasicPermissions()

        val isActivated = prefs.getBoolean("activated", false)
        val savedName = prefs.getString("deviceName", null)

        if (isActivated && savedName != null) {
            showHomeScreen(savedName)
            startBackgroundService()
        } else {
            showSetupScreen()
        }
    }

    private fun startBackgroundService() {
        val intent = Intent(this, StreamService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showHomeScreen(name: String) {
        setContentView(R.layout.activity_home)

        val search = findViewById<EditText>(R.id.home_search)
        val roomsList = findViewById<RecyclerView>(R.id.rooms_list)
        val roomsEmpty = findViewById<TextView>(R.id.rooms_empty)

        roomAdapter = RoomListAdapter(roomItems, { entry ->
            openRoom(entry.code)
        }, { entry ->
            confirmDelete(entry)
        })
        roomsList.layoutManager = LinearLayoutManager(this)
        roomsList.adapter = roomAdapter

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterRooms(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        findViewById<ImageView>(R.id.search_btn).setOnClickListener {
            val q = search.text.toString().trim()
            if (q.length >= 4) joinRoom(q.uppercase()) else filterRooms(q)
        }
        findViewById<TextView>(R.id.playlist_btn).setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.create_room_btn).setOnClickListener { showCreateDialog() }
        findViewById<MaterialButton>(R.id.join_room_btn).setOnClickListener { showJoinDialog() }
        findViewById<TextView>(R.id.nav_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        refreshRooms()
    }

    override fun onResume() {
        super.onResume()
        if (::roomAdapter.isInitialized) refreshRooms()
    }

    private fun filterRooms(query: String) {
        val q = query.trim().lowercase()
        roomItems.clear()
        roomItems.addAll(if (q.isEmpty()) allRooms else allRooms.filter {
            it.name.lowercase().contains(q) || it.code.lowercase().contains(q)
        })
        roomAdapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.rooms_empty).visibility =
            if (roomItems.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun refreshRooms() {
        val codes = joinedRooms()
        val empty = findViewById<TextView>(R.id.rooms_empty)
        if (codes.isEmpty()) {
            allRooms = emptyList()
            roomItems.clear()
            if (::roomAdapter.isInitialized) roomAdapter.notifyDataSetChanged()
            empty.visibility = android.view.View.VISIBLE
            return
        }
        var pending = codes.size
        val found = mutableListOf<RoomEntry>()
        for (code in codes) {
            db.child("rooms").child(code).get().addOnCompleteListener { task ->
                val snap = task.result
                if (task.isSuccessful && snap != null && snap.exists()) {
                    val name = snap.child("name").getValue(String::class.java)
                        ?: prefs.getString("roomName_$code", "Room") ?: "Room"
                    found.add(RoomEntry(code, name))
                } else if (task.isSuccessful) {
                    removeLocalRoom(code)
                }
                pending--
                if (pending == 0) {
                    allRooms = found
                    filterRooms(findViewById<EditText>(R.id.home_search).text.toString())
                }
            }
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
        val myId = deviceId()
        db.child("rooms").child(code).setValue(mapOf(
            "name" to name,
            "createdAt" to System.currentTimeMillis(),
            "members" to mapOf(myId to mapOf("name" to myName, "online" to true))
        )).addOnSuccessListener {
            saveJoined(code, name)
            openRoom(code)
        }.addOnFailureListener {
            Toast.makeText(this, "Failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun joinRoom(code: String) {
        db.child("rooms").child(code).get().addOnSuccessListener { snap ->
            if (!snap.exists()) {
                Toast.makeText(this, "Room not found", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            val name = snap.child("name").getValue(String::class.java) ?: "Room"
            val myName = prefs.getString("deviceName", "Guest") ?: "Guest"
            db.child("rooms").child(code).child("members").child(deviceId())
                .setValue(mapOf("name" to myName, "online" to true))
            saveJoined(code, name)
            openRoom(code)
        }
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

    private fun openRoom(code: String) {
        startActivity(Intent(this, RoomActivity::class.java).putExtra("room_code", code))
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

    private fun deviceId(): String {
        return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    private fun showSetupScreen() {
        setContentView(R.layout.activity_main)

        val activateCodeBtn = findViewById<MaterialButton>(R.id.activate_code_btn)
        val nameInput = findViewById<TextInputEditText>(R.id.name_input)
        val doneBtn = findViewById<MaterialButton>(R.id.done_btn)
        val scannerContainer = findViewById<android.widget.FrameLayout>(R.id.scanner_container)

        val isActivated = prefs.getBoolean("activated", false)
        if (isActivated) {
            activateCodeBtn.text = "Activated"
            activateCodeBtn.isEnabled = false
            activateCodeBtn.alpha = 0.5f
        }

        activateCodeBtn.setOnClickListener {
            if (!prefs.getBoolean("activated", false)) {
                showActivationDialog()
            }
        }

        scannerContainer.setOnClickListener {
            if (!prefs.getBoolean("activated", false)) {
                Toast.makeText(this, "QR scanning coming soon. Use code for now.", Toast.LENGTH_SHORT).show()
            }
        }

        doneBtn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ownerUid = prefs.getString("ownerUid", null)
            if (ownerUid != null) {
                prefs.edit().putString("deviceName", name).apply()
                registerDevice(ownerUid, name)
                Toast.makeText(this, "Setup complete", Toast.LENGTH_SHORT).show()
                showHomeScreen(name)
                startBackgroundService()
            } else {
                Toast.makeText(this, "Activate the app first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showActivationDialog() {
        val input = EditText(this)
        input.hint = "Enter activation code"

        AlertDialog.Builder(this)
            .setTitle("Activate with Code")
            .setView(input)
            .setPositiveButton("Activate") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.isNotEmpty()) {
                    verifyActivationCode(code)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyActivationCode(code: String) {
        val db = Firebase.database.reference
        db.child("activationCodes").child(code).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val ownerUid = snapshot.child("uid").value as? String
                if (ownerUid != null) {
                    registerDevice(ownerUid, null)
                    prefs.edit()
                        .putBoolean("activated", true)
                        .putString("ownerUid", ownerUid)
                        .apply()

                    val activateCodeBtn = findViewById<MaterialButton>(R.id.activate_code_btn)
                    activateCodeBtn.text = "Activated"
                    activateCodeBtn.isEnabled = false
                    activateCodeBtn.alpha = 0.5f

                    Toast.makeText(this, "App activated successfully", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Invalid activation code", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDevice(ownerUid: String, name: String?) {
        val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

        val model = Build.MODEL ?: "Unknown Device"
        val displayName = if (name != null) "$name • $model" else model

        val deviceData = mapOf(
            "name" to displayName,
            "online" to true,
            "lastSeen" to System.currentTimeMillis()
        )

        Firebase.database.reference
            .child("users")
            .child(ownerUid)
            .child("devices")
            .child(deviceId)
            .setValue(deviceData)
    }

    private fun requestBasicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (Build.VERSION.SDK_INT >= 34) {
                permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }
}