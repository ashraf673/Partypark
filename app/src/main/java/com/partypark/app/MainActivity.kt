package com.partypark.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.google.firebase.database.ktx.database

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }

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
                prefs.edit().putBoolean("activated", true).apply()
                enableGoogleButton(googleBtn)
                Toast.makeText(this, "App activated successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid activation code", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGoogleSignIn() {
        // Placeholder - will be connected to Firebase Auth later
        Toast.makeText(this, "Google Sign-In will open Watch Together page", Toast.LENGTH_LONG).show()
        // TODO: Implement real Google Sign-In and navigate to Watch Together
    }
}