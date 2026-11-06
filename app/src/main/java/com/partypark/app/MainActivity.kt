package com.partypark.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import io.getstream.webrtc.android.PeerConnectionUtils
import io.getstream.webrtc.android.WebRtcClient
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.SessionDescription
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }

    private var webRtcClient: WebRtcClient? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = Firebase.auth

        requestBasicPermissions()

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
                registerDevice(ownerUid, name)
                Toast.makeText(this, "Name saved — starting camera stream", Toast.LENGTH_SHORT).show()
                startCameraStream()
            } else {
                Toast.makeText(this, "Activate the app first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCameraStream() {
        val deviceId = prefs.getString("deviceId", null) ?: return
        val signalingRef = Firebase.database.reference.child("signaling").child(deviceId)

        signalingRef.removeValue()

        webRtcClient = WebRtcClient(this, object : WebRtcClient.Listener {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingRef.child("senderCandidates").push().setValue(candidate.sdp)
            }

            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        })

        // Start local camera + mic
        webRtcClient?.startLocalMedia()

        // Create offer
        val constraints = MediaConstraints()
        webRtcClient?.peerConnection?.createOffer(object : org.webrtc.SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                webRtcClient?.peerConnection?.setLocalDescription(object : org.webrtc.SdpObserver {
                    override fun onSetSuccess() {
                        signalingRef.child("offer").setValue(mapOf(
                            "type" to sdp.type.canonicalForm(),
                            "sdp" to sdp.description
                        ))
                        Toast.makeText(this@MainActivity, "Streaming started", Toast.LENGTH_SHORT).show()
                    }
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, constraints)

        // Listen for answer
        signalingRef.child("answer").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val answerMap = snapshot.value as? Map<*, *> ?: return
                val type = answerMap["type"] as? String ?: return
                val sdp = answerMap["sdp"] as? String ?: return
                val answer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                webRtcClient?.peerConnection?.setRemoteDescription(object : org.webrtc.SdpObserver {
                    override fun onSetSuccess() {}
                    override fun onSetFailure(p0: String?) {}
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, answer)
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
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
        val permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcClient?.disconnect()
    }
}