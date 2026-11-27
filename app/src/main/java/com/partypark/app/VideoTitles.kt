package com.partypark.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

object VideoTitles {
    fun lookup(url: String): String? {
        val youtube = youtubeId(url)
        if (youtube != null) return youtubeTitle(youtube)
        if (url.contains("drive.google.com") || url.contains("docs.google.com")) {
            return driveTitle(url)
        }
        return pageTitle(url)
    }

    fun needsLookup(title: String, url: String): Boolean {
        val name = title.trim()
        if (name.isBlank()) return true
        if (name.equals(url, ignoreCase = true)) return true
        if (name.equals("YouTube", ignoreCase = true)) return true
        if (name.equals("YouTube video", ignoreCase = true)) return true
        if (name.equals("Loading title…", ignoreCase = true)) return true
        if (name.startsWith("http://") || name.startsWith("https://")) return true
        return false
    }

    private fun youtubeTitle(id: String): String? {
        val watch = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
        val body = get("https://www.youtube.com/oembed?format=json&url=$watch") ?: return null
        return JSONObject(body).optString("title").trim().ifBlank { null }
    }

    private fun driveTitle(url: String): String? {
        val id = Regex("""/d/([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""[?&]id=([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return pageTitle(url)
        val fromHeader = contentName("https://drive.google.com/uc?export=download&id=$id")
        if (!fromHeader.isNullOrBlank()) return fromHeader
        return pageTitle("https://drive.google.com/file/d/$id/view")
    }

    private fun pageTitle(url: String): String? {
        val html = get(url, maxBytes = 180_000) ?: return null
        val og = Regex(
            """<meta[^>]+property=["']og:title["'][^>]+content=["'](.*?)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        val tag = Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val raw = (og ?: tag)?.let { decode(it) }?.trim() ?: return null
        val cleaned = raw
            .removeSuffix(" - YouTube")
            .removeSuffix(" - Google Drive")
            .trim()
        if (cleaned.isBlank()) return null
        if (cleaned.equals("YouTube", true) || cleaned.equals("Google Drive", true)) return null
        return cleaned
    }

    private fun contentName(url: String): String? {
        val conn = open(url)
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        return try {
            conn.connect()
            val name = filename(conn.getHeaderField("Content-Disposition"))
            name
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun filename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex("""filename\*=UTF-8''([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        if (!star.isNullOrBlank()) {
            return URLDecoder.decode(star, "UTF-8").trim().trim('"').ifBlank { null }
        }
        val plain = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)
        return plain?.trim()?.trim('"')?.ifBlank { null }
    }

    private fun youtubeId(url: String): String? {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return null
        if (!host.contains("youtube.com") && !host.contains("youtu.be")) return null
        if (host.contains("youtu.be")) return uri.lastPathSegment
        val path = uri.path ?: return null
        if (path.startsWith("/watch")) return uri.getQueryParameter("v")
        if (path.startsWith("/shorts/")) return path.removePrefix("/shorts/").substringBefore("/")
        if (path.startsWith("/embed/")) return path.removePrefix("/embed/").substringBefore("/")
        return null
    }

    private fun get(url: String, maxBytes: Int = 80_000): String? {
        val conn = open(url)
        return try {
            conn.connect()
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val out = StringBuilder()
                var read = reader.read(buffer)
                while (read > 0 && out.length < maxBytes) {
                    out.append(buffer, 0, read)
                    read = reader.read(buffer)
                }
                out.toString()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
        return conn
    }

    private fun decode(value: String): String {
        return value
            .replace("&", "&")
            .replace(""", "\"")
            .replace("&#39;", "'")
            .replace("<", "<")
            .replace(">", ">")
    }
}
