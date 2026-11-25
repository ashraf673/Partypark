package com.partypark.app

/** Live callback while the in-app YouTube page stays open. */
object PickBus {
    var onRoomPick: ((url: String, title: String, top: Boolean) -> Unit)? = null
}
