package com.partypark.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions result handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = Firebase.auth

        val activateBtn = findViewById<MaterialButton>(R.id.activate_btn)
        val googleBtn = findViewById<MaterialButton>(R.id.google_btn)

        val isActivated = prefs.getBoolean("activated", false)
        if (isActivated) {
            enableGoogleButton(googleBtn)
        }

        activateBtn.setOnClickListener {
            showActivationDialog(googleBtn)
        }

        googleBtn.setOnClickListener {
            if (prefs.getBoolean("activated", false)) {
                startGoogleSignIn()
            } else {
                Toast.makeText(this, "Please activate the app first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableGoogleButton(button: MaterialButton) {
        button.isEnabled = true
        button.alpha = 1f
    }

    private fun showActivationDialog(googleBtn: MaterialButton) {
        val input = android.widget.EditText(this)
        input.hint = "Enter activation code"

        AlertDialog.Builder(this)
            .setTitle("Activate Application")
            .setMessage("Enter the activation code from Watchnow")
            .setView(input)
            .setPositiveButton("Activate") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.isNotEmpty()) {
                    verifyActivationCode(code, googleBtn)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyActivationCode(code: String, googleBtn: MaterialButton) {
        val db = Firebase.database.reference
        db.child("activationCodes").child(code).get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                val ownerUid = snapshot.child("uid").value as? String
                if (ownerUid != null) {
                    registerDevice(ownerUid)
                    prefs.edit().putBoolean("activated", true).putString("ownerUid", ownerUid).apply()
                    enableGoogleButton(googleBtn)
                    Toast.makeText(this, "App activated successfully", Toast.LENGTH_SHORT).show()
                    requestBasicPermissions()
                }
            } else {
                Toast.makeText(this, "Invalid activation code", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDevice(ownerUid: String) {
        val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

        val deviceName = Build.MODEL ?: "Unknown Device"

        val deviceData = mapOf(
            "name" to deviceName,
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

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun startGoogleSignIn() {
        Toast.makeText(this, "Google Sign-In → Watch Together page (coming next)", Toast.LENGTH_LONG).show()
    }
}