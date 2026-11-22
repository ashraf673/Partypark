package com.partypark.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves a pasted link to a real media file, the same way a watch-party app
 * does: let the site build the stream URL, then play that file ourselves.
 */
class StreamExtractor(private val host: FrameLayout) {

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var generation = 0

    fun cancel() {
        generation++
        destroyWeb()
    }

    fun extractYoutube(videoId: String, onResult: (videoUrl: String?, audioUrl: String?) -> Unit) {
        val gen = ++generation
        destroyWeb()
        val progressive = arrayOfNulls<String>(1)
        val videos = HashMap<Int, String>()
        val audios = HashMap<Int, String>()
        val done = AtomicBoolean(false)

        fun finish() {
            if (!done.compareAndSet(false, true)) return
            if (gen != generation) return
            val prog = progressive[0]
            val pair = if (prog != null) {
                prog to null
            } else {
                val video = best(videos, VIDEO_ORDER)
                val audio = best(audios, AUDIO_ORDER)
                if (video != null && audio != null) video to audio else null to null
            }
            destroyWeb()
            onResult(pair.first, pair.second)
        }

        val view = makeWeb(blockVideoPlayback = false) { url ->
            if (gen != generation || !url.contains("videoplayback")) return@makeWeb
            val clean = stripPlaybackParams(url)
            val itag = itagOf(clean)
            val mime = mimeOf(clean)
            if (itag in PROGRESSIVE) {
                val prev = progressive[0]
                if (prev == null || rank(itag) > rank(itagOf(prev))) progressive[0] = clean
                main.post { finish() }
            } else if (mime.startsWith("video/") && itag > 0) {
                videos[itag] = clean
            } else if (mime.startsWith("audio/") && itag > 0) {
                audios[itag] = clean
            }
            if (videos.isNotEmpty() && audios.isNotEmpty()) main.postDelayed({ finish() }, 1200)
        }
        view.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1&mute=1&playsinline=1")
        main.postDelayed({
            if (gen != generation || progressive[0] != null || videos.isNotEmpty()) return@postDelayed
            web?.loadUrl("https://m.youtube.com/watch?v=$videoId")
        }, 4000)
        main.postDelayed({ if (gen == generation) finish() }, 10000)
    }

    fun extractDrive(fileId: String, onResult: (String?) -> Unit) {
        val gen = ++generation
        destroyWeb()
        val done = AtomicBoolean(false)
        fun finish(url: String?) {
            if (!done.compareAndSet(false, true)) return
            if (gen != generation) return
            destroyWeb()
            onResult(url)
        }
        val view = makeWeb(blockVideoPlayback = false) { url ->
            if (gen != generation || !isDriveStream(url)) return@makeWeb
            main.post { finish(url) }
        }
        val probe = object : Runnable {
            var tries = 0
            override fun run() {
                if (gen != generation || done.get() || web == null) return
                view.evaluateJavascript(DRIVE_PROBE) { raw ->
                    val value = unquote(raw)
                    if (value.startsWith("http")) finish(value)
                }
                tries++
                if (tries < 12 && gen == generation && !done.get()) main.postDelayed(this, 700)
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (isDriveStream(url)) main.post { finish(url) }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                main.post(probe)
            }
        }
        view.loadUrl("https://drive.google.com/file/d/$fileId/view")
        main.postDelayed({ if (gen == generation) finish(null) }, 9000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(blockVideoPlayback: Boolean, onUrl: (String) -> Unit): WebView {
        destroyWeb()
        val view = WebView(host.context)
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = UA
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(v){v.muted=true;v.play&&v.play();}})()",
                    null
                )
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (url.contains("videoplayback") || url.contains("googleusercontent.com")) onUrl(url)
                if (blockVideoPlayback && url.contains("videoplayback")) {
                    return WebResourceResponse("video/mp4", null, ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
        view.alpha = 0f
        view.layoutParams = FrameLayout.LayoutParams(1, 1)
        host.addView(view)
        web = view
        return view
    }

    private fun destroyWeb() {
        val view = web ?: return
        web = null
        view.stopLoading()
        host.removeView(view)
        view.destroy()
    }

    private fun best(map: Map<Int, String>, order: List<Int>): String? {
        for (itag in order) {
            map[itag]?.let { return it }
        }
        return map.values.firstOrNull()
    }

    private fun rank(itag: Int): Int = when (itag) {
        22 -> 3
        18 -> 2
        37 -> 1
        else -> 0
    }

    private fun itagOf(url: String): Int {
        return Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun mimeOf(url: String): String {
        val raw = Regex("[?&]mime=([^&]+)").find(url)?.groupValues?.get(1) ?: return ""
        return URLDecoder.decode(raw, "UTF-8")
    }

    private fun stripPlaybackParams(url: String): String {
        val uri = Uri.parse(url)
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            if (name == "range" || name == "rn" || name == "rbuf") continue
            for (value in uri.getQueryParameters(name)) builder.appendQueryParameter(name, value)
        }
        return builder.build().toString()
    }

    private fun isDriveStream(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.contains("googleusercontent.com")) return false
        return lower.contains("=dv") || lower.contains("mime=video") || lower.contains("videoplayback")
    }

    private fun unquote(raw: String?): String {
        if (raw == null) return ""
        return raw.trim().trim('"')
            .replace("\\/", "/")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val PROGRESSIVE = setOf(18, 22, 37)
        private val VIDEO_ORDER = listOf(137, 136, 135, 134, 133)
        private val AUDIO_ORDER = listOf(140, 141, 139)
        private val DRIVE_PROBE =
            "(function(){var m=document.querySelector('meta[property=\"og:video\"]')||document.querySelector('meta[property=\"og:video:url\"]');" +
                "if(m&&m.content&&m.content.indexOf('http')===0)return m.content;" +
                "var v=document.querySelector('video');var s=v?(v.currentSrc||v.src||''):'';" +
                "if(s.indexOf('http')===0)return s;return '';})()"
    }
}
