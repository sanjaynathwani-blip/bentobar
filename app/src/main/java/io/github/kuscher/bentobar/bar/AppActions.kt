package io.github.kuscher.bentobar.bar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kuscher.bentobar.items.Env
import io.github.kuscher.bentobar.net.LocalBridge
import org.xmlpull.v1.XmlPullParser

/** One quick action an app publishes (what its icon's right-click menu shows): its label, icon and what it opens. */
@Immutable
data class AppAction(val label: String, val icon: ImageBitmap?, val intent: Intent, val dynamic: Boolean)

/**
 * The quick actions an app publishes, as a launcher's right-click menu shows them. Two sources:
 * - The ones its manifest declares (`android.app.shortcuts`), which any app may read: read here exactly.
 * - The prototype bridge ([LocalBridge.shortcuts]), which has what only the home screen and the shell may read: the
 *   ones published while running, and which shortcuts Android has enabled, in the launcher's order. When it runs it
 *   decides which actions there are; a manifest one keeps the intent read here (the bridge's listing shortens links).
 * Without the bridge (always so in a release build, see [LocalBridge.ENABLED]): the manifest's alone.
 */
object AppActions {
    private const val TAG = "BentoBarApps"
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    /** The flags Android gives a manifest shortcut's last intent (ShortcutParser). */
    private const val MANIFEST_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME

    /** Slow (a package's resources, and the bridge's shell command): never on the main thread. */
    fun load(context: Context, pkg: String): List<AppAction> {
        val pm = context.packageManager
        val res = runCatching { pm.getResourcesForApplication(pkg) }.getOrNull() ?: return emptyList()
        val sizePx = (20 * context.resources.displayMetrics.density).toInt()
        fun icon(id: Int) = if (id == 0) null else runCatching { bitmap(res, id, sizePx) }.getOrNull()
        val manifest = runCatching { manifest(pm, res, pkg, ::icon) }.onFailure { Log.w(TAG, "manifest shortcuts of $pkg: $it") }.getOrDefault(emptyMap())
        val bridge = LocalBridge.shortcuts(pkg) ?: return manifest.values.toList()
        return bridge.map { s ->
            if (!s.dynamic) manifest[s.id]?.let { return@map it }
            val i = Intent(s.action)
            s.data?.let { d -> if (s.type != null) i.setDataAndType(Uri.parse(d), s.type) else i.data = Uri.parse(d) } ?: s.type?.let { i.type = it }
            s.component?.let { (p, c) -> i.component = ComponentName(p, c) } ?: i.setPackage(s.pkg ?: pkg)
            s.categories.forEach(i::addCategory)
            // The listing shows extras without their types: whole numbers go as ints, everything else as text.
            s.extras.forEach { (k, v) -> v.toIntOrNull()?.let { i.putExtra(k, it) } ?: i.putExtra(k, v) }
            i.flags = s.flags
            AppAction(s.label, icon(s.iconRes), i, dynamic = s.dynamic)
        }
    }

    /**
     * Starts [action]'s intent directly; false when Android won't let it (the screen it opens isn't exported: only
     * the home screen may start such a shortcut, see [AppWindows.runViaTaskbar]).
     */
    fun run(action: AppAction): Boolean = Env.launch(Intent(action.intent), quiet = true)

    /** The shortcuts the app's launcher activities declare in their `android.app.shortcuts` resource, by shortcut id, in order. */
    private fun manifest(pm: PackageManager, res: Resources, pkg: String, icon: (Int) -> ImageBitmap?): Map<String, AppAction> {
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        val out = LinkedHashMap<String, AppAction>()
        for (ri in launchers) {
            val xml = ri.activityInfo.metaData?.getInt("android.app.shortcuts")?.takeIf { it != 0 } ?: continue
            val parser = res.getXml(xml)
            var id: String? = null
            var label: String? = null
            var iconId = 0
            var enabled = true
            var intent: Intent? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when {
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "shortcut" -> {
                        id = parser.getAttributeValue(ANDROID, "shortcutId")
                        // Often a resource that depends on the device ("@bool/…"): read it, not the reference.
                        val enabledRes = parser.getAttributeResourceValue(ANDROID, "enabled", 0)
                        enabled = if (enabledRes != 0) runCatching { res.getBoolean(enabledRes) }.getOrDefault(true)
                            else parser.getAttributeBooleanValue(ANDROID, "enabled", true)
                        label = text(res, parser.getAttributeResourceValue(ANDROID, "shortcutShortLabel", 0), parser.getAttributeValue(ANDROID, "shortcutShortLabel"))
                        iconId = parser.getAttributeResourceValue(ANDROID, "icon", 0)
                        intent = null
                    }
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "intent" -> {
                        // A shortcut can name several intents (a back stack): the last is the one that opens.
                        intent = Intent(parser.getAttributeValue(ANDROID, "action") ?: Intent.ACTION_VIEW).apply {
                            val target = parser.getAttributeValue(ANDROID, "targetPackage") ?: pkg
                            parser.getAttributeValue(ANDROID, "targetClass")?.let { component = ComponentName(target, it) } ?: setPackage(target)
                            parser.getAttributeValue(ANDROID, "data")?.let { data = Uri.parse(it) }
                            flags = MANIFEST_FLAGS
                        }
                    }
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "extra" -> {
                        val name = parser.getAttributeValue(ANDROID, "name")
                        val value = parser.getAttributeValue(ANDROID, "value")
                        if (name != null && value != null) intent?.putExtra(name, value)
                    }
                    parser.eventType == XmlPullParser.START_TAG && parser.name == "categories" -> {
                        parser.getAttributeValue(ANDROID, "name")?.let { intent?.addCategory(it) }
                    }
                    parser.eventType == XmlPullParser.END_TAG && parser.name == "shortcut" -> {
                        val i = intent
                        if (enabled && i != null && id != null && !label.isNullOrBlank()) out[id!!] = AppAction(label!!, icon(iconId), i, dynamic = false)
                    }
                }
            }
            parser.close()
        }
        return out
    }

    private fun text(res: Resources, id: Int, raw: String?): String? = if (id != 0) runCatching { res.getString(id) }.getOrNull() else raw

    /** The icon on a light disc, as a launcher draws a shortcut's: many are dark glyphs that vanish on dark glass. */
    private fun bitmap(res: Resources, id: Int, size: Int): ImageBitmap {
        val d = res.getDrawable(id, null)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        // An adaptive icon brings its own background; a plain glyph gets the disc.
        if (d !is android.graphics.drawable.AdaptiveIconDrawable) {
            c.drawCircle(size / 2f, size / 2f, size / 2f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() })
            val inset = size / 5
            d.setBounds(inset, inset, size - inset, size - inset)
        } else d.setBounds(0, 0, size, size)
        d.draw(c)
        return b.asImageBitmap()
    }
}
