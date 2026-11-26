package com.partypark.app

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ProfileActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("partypark", Context.MODE_PRIVATE) }
    private var avatar: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        avatar = Profile.ensure(this)
        val avatarView = findViewById<TextView>(R.id.profile_avatar)
        val nameInput = findViewById<EditText>(R.id.profile_name)
        avatarView.text = avatar
        nameInput.setText(Profile.name(this))
        findViewById<TextView>(R.id.profile_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.change_avatar).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Profile picture")
                .setItems(Profile.EMOJIS.toTypedArray()) { _, which ->
                    avatar = Profile.EMOJIS[which]
                    avatarView.text = avatar
                }
                .show()
        }
        findViewById<MaterialButton>(R.id.save_profile).setOnClickListener {
            val name = nameInput.text.toString().trim().ifEmpty { "Guest" }
            prefs.edit().putString("deviceName", name).putString("avatar", avatar).apply()
            Profile.pushToJoinedRooms(this)
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
