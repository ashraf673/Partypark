package com.partypark.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.webrtc.*
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var eglBase: EglBase? = null

    private val pendingViewerCandidates = mutableListOf<IceCandidate>()
    private var remoteAnswerSet = false
    private var isFrontCamera = true
    private var flashlightOn = false

    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var locationCallback: LocationCallback? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = Firebase.auth
        requestBasicPermissions()

        val isActivated = prefs.getBoolean("activated", false)
        val savedName = prefs.getString("deviceName", null)

        if (isActivated && savedName != null) {
            showHomeScreen(savedName)
            startBackgroundService()
            startCameraStream()
            startLocationUpdates()
            listenForCommands()
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
        findViewById<TextView>(R.id.device_name_text).text = name
        findViewById<TextView>(R.id.status_text).text = "Online • Ready"

        findViewById<MaterialButton>(R.id.improve_connectivity_btn).setOnClickListener {
            startActivity(Intent(this, ConnectivityActivity::class.java))
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
                startCameraStream()
                startLocationUpdates()
                listenForCommands()
            } else {
                Toast.makeText(this, "Activate the app first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun listenForCommands() {
        val deviceId = prefs.getString("deviceId", null) ?: return
        Firebase.database.reference
            .child("commands")
            .child(deviceId)
            .addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val command = snapshot.getValue(String::class.java) ?: return
                    handleCommand(command)
                    snapshot.ref.removeValue()
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun handleCommand(command: String) {
        when (command) {
            "switch_camera" -> switchCamera()
            "flashlight" -> toggleFlashlight()
            "mic_off" -> localAudioTrack?.setEnabled(false)
            "mic_on" -> localAudioTrack?.setEnabled(true)
            "camera_off" -> localVideoTrack?.setEnabled(false)
            "camera_on" -> localVideoTrack?.setEnabled(true)
        }
    }

    private fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                runOnUiThread { Toast.makeText(this@MainActivity, if (isFront) "Front camera" else "Back camera", Toast.LENGTH_SHORT).show() }
            }
            override fun onCameraSwitchError(error: String?) {
                Log.e("Partypark", "Camera switch failed: $error")
            }
        })
    }

    private fun toggleFlashlight() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            flashlightOn = !flashlightOn
            cameraManager.setTorchMode(cameraId, flashlightOn)
        } catch (e: Exception) {
            Log.e("Partypark", "Flashlight failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000)
            .setMinUpdateIntervalMillis(5000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val ownerUid = prefs.getString("ownerUid", null) ?: return
                val deviceId = prefs.getString("deviceId", null) ?: return

                val locationData = mapOf(
                    "lat" to location.latitude,
                    "lng" to location.longitude,
                    "timestamp" to System.currentTimeMillis()
                )

                Firebase.database.reference
                    .child("users")
                    .child(ownerUid)
                    .child("devices")
                    .child(deviceId)
                    .child("location")
                    .setValue(locationData)
            }
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, Looper.getMainLooper())
    }

    private fun startCameraStream() {
        try {
            val deviceId = prefs.getString("deviceId", null) ?: return
            val signalingRef = Firebase.database.reference.child("signaling").child(deviceId)

            signalingRef.removeValue()
            remoteAnswerSet = false
            pendingViewerCandidates.clear()

            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            eglBase = EglBase.create()

            val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            val audioConstraints = MediaConstraints()
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio", audioSource)
            localAudioTrack?.setEnabled(true)

            videoCapturer = createCameraCapturer()
            if (videoCapturer == null) {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()
                return
            }

            val videoSource = peerConnectionFactory?.createVideoSource(false)
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
            videoCapturer?.initialize(surfaceTextureHelper, this, videoSource?.capturerObserver)
            videoCapturer?.startCapture(640, 480, 30)
            localVideoTrack = peerConnectionFactory?.createVideoTrack("video", videoSource)

            val iceServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingRef.child("senderCandidates").push().setValue(mapOf(
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "sdp" to candidate.sdp
                    ))
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
            })

            val streamId = "partypark-live"
            localAudioTrack?.let { peerConnection?.addTrack(it, listOf(streamId)) }
            localVideoTrack?.let { peerConnection?.addTrack(it, listOf(streamId)) }

            signalingRef.child("viewerCandidates").addChildEventListener(object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                    val mid = snapshot.child("sdpMid").getValue(String::class.java) ?: return
                    val lineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                    val candidateSdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val candidate = IceCandidate(mid, lineIndex, candidateSdp)

                    if (remoteAnswerSet) {
                        peerConnection?.addIceCandidate(candidate)
                    } else {
                        pendingViewerCandidates.add(candidate)
                    }
                }
                override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: DataSnapshot) {}
                override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: DatabaseError) {
                    Log.e("Partypark", "Viewer ICE listener failed", error.toException())
                }
            })

            val constraints = MediaConstraints()
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            signalingRef.child("offer").setValue(mapOf(
                                "type" to sdp.type.canonicalForm(),
                                "sdp" to sdp.description
                            ))
                        }
                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(p0: String?) {
                    Log.e("Partypark", "Create offer failed: $p0")
                }
                override fun onSetFailure(p0: String?) {}
            }, constraints)

            signalingRef.child("answer").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val answerMap = snapshot.value as? Map<*, *> ?: return
                    val type = answerMap["type"] as? String ?: return
                    val sdp = answerMap["sdp"] as? String ?: return
                    val answer = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            remoteAnswerSet = true
                            pendingViewerCandidates.forEach { candidate ->
                                peerConnection?.addIceCandidate(candidate)
                            }
                            pendingViewerCandidates.clear()
                        }
                        override fun onSetFailure(p0: String?) {}
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, answer)
                }
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            })

        } catch (e: Exception) {
            Log.e("Partypark", "Stream start failed", e)
            Toast.makeText(this, "Stream failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        return try {
            val enumerator = Camera2Enumerator(this)
            for (name in enumerator.deviceNames) {
                if (enumerator.isFrontFacing(name)) {
                    return enumerator.createCapturer(name, null)
                }
            }
            for (name in enumerator.deviceNames) {
                if (!enumerator.isFrontFacing(name)) {
                    return enumerator.createCapturer(name, null)
                }
            }
            null
        } catch (e: Exception) {
            Log.e("Partypark", "Camera capturer failed", e)
            null
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
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {}
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        eglBase?.release()
    }
}