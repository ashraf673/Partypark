package com.partypark.app

import android.content.Context
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

object Profile {
    val EMOJIS = listOf(
        "\uD83D\uDE0E", "\uD83C\uDFAC", "\uD83C\uDFB5", "\uD83C\uDF1F",
        "\uD83D\uDD25", "\uD83C\uDFA7", "\uD83C\uDFAE", "\uD83C\uDF08",
        "\uD83D\uDC31", "\uD83E\uDD8A", "\uD83D\uDC3C", "\uD83C\uDF19"
    )

    fun ensure(context: Context): String {
        val prefs = context.getSharedPreferences("partypark", Context.MODE_PRIVATE)
        val saved = prefs.getString("avatar", null)
        if (!saved.isNullOrBlank()) return saved
        val picked = EMOJIS.random()
        prefs.edit().putString("avatar", picked).apply()
        return picked
    }

    fun avatar(context: Context): String = ensure(context)

    fun name(context: Context): String {
        return context.getSharedPreferences("partypark", Context.MODE_PRIVATE)
            .getString("deviceName", "Guest") ?: "Guest"
    }

    fun pushToJoinedRooms(context: Context) {
        val prefs = context.getSharedPreferences("partypark", Context.MODE_PRIVATE)
        val id = prefs.getString("deviceId", null) ?: return
        val raw = prefs.getString("joinedRooms", "") ?: ""
        if (raw.isBlank()) return
        val name = name(context)
        val avatar = avatar(context)
        val db = Firebase.database.reference
        for (code in raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            db.child("rooms").child(code).child("members").child(id).updateChildren(
                mapOf("name" to name, "avatar" to avatar)
            )
        }
    }
}
