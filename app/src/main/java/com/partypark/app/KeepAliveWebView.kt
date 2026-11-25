package com.partypark.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/** Keeps an embedded video playing after the home button hides this view. */
class KeepAliveWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var keepPlayingInBackground: Boolean = false

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepPlayingInBackground && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
            return
        }
        super.onWindowVisibilityChanged(visibility)
    }
}
