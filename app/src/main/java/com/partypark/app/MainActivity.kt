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
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        auth = Firebase.auth

        val activateBtn = findViewById<MaterialButton>(R.id.activate_btn)
        val googleBtn = findViewById<MaterialButton>(R.id.google_btn)

        updateButtons(activateBtn, googleBtn)

        activateBtn.setOnClickListener {
            if (!prefs.getBoolean("activated", false)) {
                showActivationDialog(activateBtn, googleBtn)
            }
        }

        googleBtn.setOnClickListener {
            if (prefs.getBoolean("activated", false)) {
                showNameDialog()
            } else {
                Toast.makeText(this, "Please activate the app first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateButtons(activateBtn: MaterialButton, googleBtn: MaterialButton) {
        val isActivated = prefs.getBoolean("activated", false)
        if (isActivated) {
            activateBtn.text = "Activated"
            activateBtn.alpha = 0.5f
            activateBtn.isEnabled = false

            googleBtn.isEnabled = true
            googleBtn.alpha = 1f
        } else {
            activateBtn.text = "Activate Application"
            activateBtn.alpha = 1f
            activateBtn.isEnabled = true

            googleBtn.isEnabled = false
            googleBtn.alpha = 0.4f
        }
    }

    private fun showActivationDialog(activateBtn: MaterialButton, googleBtn: MaterialButton) {
        val input = EditText(this)
        input.hint = "Enter activation code"

        AlertDialog.Builder(this)
            .setTitle("Activate Application")
            .setMessage("Enter the activation code from Watchnow")
            .setView(input)
            .setPositiveButton("Activate") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.isNotEmpty()) {
                    verifyActivationCode(code, activateBtn, googleBtn)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun verifyActivationCode(code: String, activateBtn: MaterialButton, googleBtn: MaterialButton) {
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
                    updateButtons(activateBtn, googleBtn)
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

    private fun showNameDialog() {
        val input = EditText(this)
        input.hint = "Enter your name (example: Afzal Ali)"

        AlertDialog.Builder(this)
            .setTitle("Enter Your Name")
            .setMessage("This name will appear in Watchnow with the device name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val ownerUid = prefs.getString("ownerUid", null)
                    if (ownerUid != null) {
                        registerDevice(ownerUid, name)
                        Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun registerDevice(ownerUid: String, googleName: String?) {
        val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

        val model = Build.MODEL ?: "Unknown Device"
        val displayName = if (googleName != null) "$googleName • $model" else model

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
}