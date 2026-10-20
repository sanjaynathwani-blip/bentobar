package io.github.kuscher.bentobar.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.github.kuscher.bentobar.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * What the app menu bar needs from the shell (fork, PROTOTYPE ONLY), through tools/menubar-bridge.py: a bridge on a
 * developer's machine, run in the Googlebook's Linux terminal with adb connected to the Googlebook, whose ports
 * Android sees on its own loopback.
 * - Chrome's open tabs, for the Tab menu: Chrome lists them and switches between them on its remote-debugging
 *   socket, which only the shell may open; the bridge brings it to [CHROME_PORT].
 * - An app's dynamic quick actions, for the Actions menu: only the shell and the home screen may read them; the
 *   bridge answers on [SHORTCUTS_PORT].
 * This file opens nothing else: only those two ports at the loopback address.
 *
 * Debug builds only ([ENABLED]). On a device with no bridge running, any app could listen on those ports, learn
 * which app is in front from each request and answer with intents of its choosing: a release build never connects.
 */
object LocalBridge {
    /** Whether the bridge is asked at all: debug builds only. A release build's every call answers null at once. */
    val ENABLED = BuildConfig.DEBUG
    const val CHROME_PORT = 39222
    const val SHORTCUTS_PORT = 39223
    private const val HOST = "127.0.0.1"

    /** One open tab: Chrome's id for it, its title and its address. */
    data class Tab(val id: String, val title: String, val url: String)

    /** Every open page tab of every Chrome window, most recently used first; null when the relay isn't there. Never on the main thread. */
    fun chromeTabs(): List<Tab>? {
        val body = get(CHROME_PORT, "/json/list", "localhost:9222") ?: return null
        return runCatching {
            (Json.parseToJsonElement(body) as JsonArray).map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.content == "page" }
                .map { Tab(it.getValue("id").jsonPrimitive.content, unescape(it["title"]?.jsonPrimitive?.content.orEmpty()), it["url"]?.jsonPrimitive?.content.orEmpty()) }
        }.getOrNull()
    }

    /** Brings tab [id] to the front of its window (and its window to the front). Never on the main thread. */
    fun activateChromeTab(id: String): Boolean =
        get(CHROME_PORT, "/json/activate/${id.filter { it.isLetterOrDigit() }}", "localhost:9222")?.contains("activated") == true

    /**
     * One published quick action as the bridge read it from `cmd shortcut`: its label, whether the app's manifest
     * declares it or the app published it while running, its icon's resource id in that app, and its intent's parts.
     * Android's listing shortens a link's path ("https://www.youtube.com/..."): the manifest's own are read exactly
     * on the device instead, so only dynamic ones are taken from here.
     */
    data class Shortcut(
        val id: String, val label: String, val dynamic: Boolean, val rank: Int, val iconRes: Int,
        val action: String?, val data: String?, val type: String?, val component: Pair<String, String>?, val pkg: String?,
        val categories: List<String>, val flags: Int, val extras: Map<String, String>,
    )

    /** [pkg]'s manifest and dynamic quick actions, launcher order; null when the bridge isn't there. Never on the main thread. */
    fun shortcuts(pkg: String): List<Shortcut>? {
        if (!pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return null
        val body = get(SHORTCUTS_PORT, "/shortcuts?pkg=$pkg", "localhost") ?: return null
        return runCatching {
            (Json.parseToJsonElement(body) as JsonArray).map { e ->
                val o = e.jsonObject
                val i = (o["intent"] as? JsonObject)
                fun str(key: String) = i?.get(key)?.jsonPrimitive?.contentOrNull
                Shortcut(
                    id = o.getValue("id").jsonPrimitive.content, label = o["label"]?.jsonPrimitive?.content.orEmpty(),
                    dynamic = o["kind"]?.jsonPrimitive?.content == "dynamic", rank = o["rank"]?.jsonPrimitive?.intOrNull ?: 0,
                    iconRes = o["iconRes"]?.jsonPrimitive?.intOrNull ?: 0,
                    action = str("action"), data = str("data"), type = str("type"), pkg = str("package"),
                    component = (i?.get("component") as? JsonArray)?.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content },
                    categories = (i?.get("categories") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                    flags = str("flags")?.removePrefix("0x")?.toLongOrNull(16)?.toInt() ?: 0,
                    extras = (i?.get("extras") as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                )
            }
        }.getOrNull()
    }

    /** Chrome escapes a few characters of a title as HTML entities in this list. */
    private fun unescape(s: String) = s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** [hostHeader]: Chrome answers only a request that names a local host. */
    private fun get(port: Int, path: String, hostHeader: String): String? = if (!ENABLED) null else runCatching {
        require(port == CHROME_PORT || port == SHORTCUTS_PORT)
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, port), 800)
            s.soTimeout = 12_000 // a shell command answers the quick actions: a few seconds
            s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
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
            if (bodyAt < 0 || !Regex("^HTTP/1\\.[01] 200").containsMatchIn(String(bytes, 0, minOf(16, bytes.size), Charsets.ISO_8859_1))) null
            else String(bytes, bodyAt, (if (length >= 0) length else bytes.size - bodyAt).coerceAtMost(bytes.size - bodyAt), Charsets.UTF_8)
        }
    }.getOrNull()
}
