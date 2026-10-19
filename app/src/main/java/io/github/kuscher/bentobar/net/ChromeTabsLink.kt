package io.github.kuscher.bentobar.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Chrome's open tabs, for the app menu bar's Tab menu (fork, PROTOTYPE ONLY). Chrome lists its tabs and switches
 * between them on its remote-debugging socket, which only the shell may open. A relay on a developer's machine
 * (tools/chrome-tabs-relay.py: adb forward plus a TCP relay in the Googlebook's Linux terminal, whose ports Android
 * sees on its own loopback) brings it to 127.0.0.1:[PORT]. This file opens nothing else: only that address.
 */
object ChromeTabsLink {
    const val PORT = 39222
    private const val HOST = "127.0.0.1"

    /** One open tab: Chrome's id for it, its title and its address. */
    data class Tab(val id: String, val title: String, val url: String)

    /** Every open page tab of every Chrome window, most recently used first; null when the relay isn't there. Never on the main thread. */
    fun list(): List<Tab>? {
        val body = get("/json/list") ?: return null
        return runCatching {
            (Json.parseToJsonElement(body) as JsonArray).map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "page" }
                .map { Tab(it.getValue("id").jsonPrimitive.content, unescape(it["title"]?.jsonPrimitive?.content.orEmpty()), it["url"]?.jsonPrimitive?.content.orEmpty()) }
        }.getOrNull()
    }

    /** Brings tab [id] to the front of its window (and its window to the front). Never on the main thread. */
    fun activate(id: String): Boolean = get("/json/activate/${id.filter { it.isLetterOrDigit() }}")?.contains("activated") == true

    /** Chrome escapes a few characters of a title as HTML entities in this list. */
    private fun unescape(s: String) = s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun get(path: String): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, PORT), 800)
            s.soTimeout = 2000
            // Chrome answers only a request that names a local host.
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost:9222\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
            // Read up to the end of the body its length names: through the relay the connection's end can come late or never.
            val input = s.getInputStream()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            var bodyAt = -1
            var length = -1
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (bodyAt < 0) {
                    val head = out.toString(Charsets.ISO_8859_1.name())
                    val end = head.indexOf("\r\n\r\n")
                    if (end >= 0) {
                        bodyAt = end + 4
                        length = Regex("(?i)\r\ncontent-length:\\s*(\\d+)").find(head.substring(0, end))?.groupValues?.get(1)?.toInt() ?: -1
                    }
                }
                if (bodyAt >= 0 && length >= 0 && out.size() >= bodyAt + length) break
            }
            val bytes = out.toByteArray()
            if (bodyAt < 0 || !String(bytes, 0, 12, Charsets.ISO_8859_1).startsWith("HTTP/1.1 200")) null
            else String(bytes, bodyAt, (if (length >= 0) length else bytes.size - bodyAt).coerceAtMost(bytes.size - bodyAt), Charsets.UTF_8)
        }
    }.getOrNull()
}
