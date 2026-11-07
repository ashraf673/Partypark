package com.partypark.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import org.webrtc.*

class StreamService : Service() {

    companion object {
        const val CHANNEL_ID = "partypark_stream"
        const val NOTIFICATION_ID = 1
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var eglBase: EglBase? = null

    private val pendingViewerCandidates = mutableListOf<IceCandidate>()
    private var remoteAnswerSet = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start streaming in service so it survives Activity destruction
        startStreaming()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startStreaming() {
        try {
            val prefs = getSharedPreferences("partypark", Context.MODE_PRIVATE)
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
                Log.e("Partypark", "Camera not available in service")
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
                override fun onCancelled(error: DatabaseError) {}
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
            Log.e("Partypark", "Service stream start failed", e)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Partypark Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Partypark")
            .setContentText("Streaming active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {}
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        eglBase?.release()
    }
}