package io.github.kuscher.bentobar.bar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import io.github.kuscher.bentobar.R
import io.github.kuscher.bentobar.items.Env
import io.github.kuscher.bentobar.items.MenuHost
import io.github.kuscher.bentobar.net.LocalBridge
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.kuscher.bentobar.ui.MenuCard
import io.github.kuscher.bentobar.ui.MenuDivider
import io.github.kuscher.bentobar.ui.MenuEntry
import io.github.kuscher.bentobar.util.Fonts
import io.github.kuscher.bentobar.util.Sym

/** The app the menu bar is for: its window, package and name. */
@Immutable
data class FrontApp(val windowId: Int, val pkg: String, val label: String)

/** The words of the app menu bar, as popup keys ("app:name"…). */
enum class AppMenu(val key: String) {
    NAME("app:name"), WINDOW("app:window"), TAB("app:tab"), ACTIONS("app:actions"), WIDGETS("app:widgets"), SETTINGS("app:settings");
    /**
     * Whether [app]'s bar has this word: Tab is Chrome's alone (prototype, debug builds only), Actions an app's that
     * publishes some, Widgets an app's that publishes home screen widgets.
     */
    fun shownFor(app: FrontApp, hasActions: Boolean, hasWidgets: Boolean) = when (this) {
        TAB -> LocalBridge.ENABLED && app.pkg == CHROME
        ACTIONS -> hasActions
        WIDGETS -> hasWidgets
        else -> true
    }
    companion object {
        const val CHROME = "com.android.chrome"
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * The app in front, and what can be done to its window. This reads more than the rest of BentoBar does: which
 * application window is active, that window's package (its root node's package name, nothing of what it shows),
 * and the caption SystemUI draws above it, whose buttons it presses for the Window menu. Two more, each only when
 * asked: the window's title, for the Tabs menu (debug builds, [title]), and the taskbar's icon for the app with the
 * menu it opens, to start a quick action only the launcher may start ([runViaTaskbar]).
 */
class AppWindows(private val service: AccessibilityService) {
    private val tag = "BentoBarApps"
    private val packages = HashMap<Int, String>()
    private var following: Int? = null
    private var last: FrontApp? = null
    /** The app whose menus were shown when a window that can't be read yet came forward. */
    private var interrupted: FrontApp? = null
    private val home: String? by lazy {
        service.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    private fun seen(w: AccessibilityWindowInfo): SeenWindow {
        val r = Rect().also { w.getBoundsInScreen(it) }
        return SeenWindow(w.id, w.type, w.isActive, w.isFocused, r.left, r.top, r.right, r.bottom)
    }

    /** The last read found a window that couldn't be read yet: the caller looks again soon. */
    var unreadable = false
        private set

    /** The app in front now; null for none (the home screen). Cheap: the window list, and a package read once per window. */
    fun read(): FrontApp? {
        val list = runCatching { service.windows }.getOrDefault(emptyList())
        val seen = list.map(::seen)
        if (packages.size > 64) packages.clear()
        fun pkgOf(id: Int) = packages[id] ?: runCatching { list.firstOrNull { it.id == id }?.root?.packageName?.toString() }.getOrNull()?.also { packages[id] = it }
        // While a window can't be read yet the menus are off, but the app they were for is remembered: if that window
        // turns out to be our own settings, the menus come back for it.
        val shown = last ?: interrupted
        val pick = FrontAppRules.pick(seen, following, shown?.windowId, ::pkgOf, home, service.packageName)
        unreadable = pick == FrontAppRules.Pick.Unreadable
        FrontAppRules.next(seen, following)?.let { following = it }
        last = when (pick) {
            is FrontAppRules.Pick.Show -> if (shown?.windowId == pick.id && shown.pkg == pick.pkg) shown else FrontApp(pick.id, pick.pkg, label(pick.pkg))
            FrontAppRules.Pick.Keep -> shown
            FrontAppRules.Pick.Hide, FrontAppRules.Pick.Unreadable -> null
        }
        interrupted = if (unreadable) shown else null
        return last
    }

    private fun label(pkg: String): String = runCatching {
        service.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
    }.getOrDefault(pkg)

    /** The caption's node tree for [app]'s window, read fresh. Null when it has none (full screen) or it went. */
    private fun captionRoot(app: FrontApp): AccessibilityNodeInfo? {
        service.clearCache()
        val list = runCatching { service.windows }.getOrDefault(emptyList())
        val seen = list.map(::seen)
        val window = seen.firstOrNull { it.id == app.windowId } ?: return null
        val caption = FrontAppRules.caption(seen, window) ?: return null
        return list.firstOrNull { it.id == caption.id }?.root
    }

    private fun find(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.viewIdResourceName?.endsWith("/$viewId") == true) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    /** Whether [app]'s window has a caption to work with, and whether it is maximized (its button says "Restore"). */
    fun caption(app: FrontApp): CaptionState? {
        val root = captionRoot(app) ?: return null
        val max = find(root, "maximize_window")
        val restore = max?.contentDescription?.startsWith("Restore") == true
        return CaptionState(maximized = restore, actions = max?.actionList?.mapNotNull { it.label?.toString() }.orEmpty().toSet())
    }

    /**
     * Presses a caption button of [app]'s window: [button] is its view id ("minimize_window"), [label] one of its
     * own accessibility actions ("Resize app window left"), or a plain click without one. The labels are SystemUI's
     * English ones (this device's); another language wouldn't find them.
     */
    fun press(app: FrontApp, button: String, label: String? = null): Boolean = pressIn(captionRoot(app), button, label)

    /** As [press], on the caption window [captionId] itself (adb tests: a window behind others may be missing from the list). */
    fun pressCaption(captionId: Int, button: String, label: String? = null): Boolean {
        service.clearCache()
        return pressIn(runCatching { service.windows }.getOrDefault(emptyList()).firstOrNull { it.id == captionId }?.root, button, label)
    }

    /**
     * SystemUI's caption answers its own actions with false even when it carries them out (a restore did restore),
     * so what performAction says is only logged: a button or action that isn't there is the one failure we can see.
     */
    private fun pressIn(root: AccessibilityNodeInfo?, button: String, label: String?): Boolean {
        val node = root?.let { find(it, button) } ?: return failed("no $button")
        val said = if (label == null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else node.actionList.firstOrNull { it.label?.toString() == label }?.let { node.performAction(it.id) } ?: return failed("no '$label' on $button")
        Log.i(tag, "window action $button ${label ?: "click"} (performAction said $said)")
        return true
    }

    private fun failed(why: String): Boolean {
        Log.w(tag, "window action: $why")
        Toast.makeText(service, R.string.appmenu_action_failed, Toast.LENGTH_SHORT).show()
        return false
    }

    /** The window's title as Android lists it, without the app's name in front ("Chrome: Reddit" is "Reddit"): a browser's current tab. */
    fun title(app: FrontApp): String? {
        val t = runCatching { service.windows }.getOrDefault(emptyList()).firstOrNull { it.id == app.windowId }?.title?.toString() ?: return null
        return t.removePrefix("${app.label}: ")
    }

    /**
     * Starts one of [app]'s quick actions through the launcher, for one whose screen the app keeps to itself (not
     * exported: only the home screen may start it, on the app's behalf). Opens the app's own right-click menu from its
     * taskbar icon (the icon's "Shortcut Menu" accessibility action) and clicks the entry named [label]. Slow (waits
     * for the menu): never on the main thread.
     */
    fun runViaTaskbar(app: FrontApp, label: String): Boolean {
        service.clearCache()
        val taskbar = runCatching { service.windows }.getOrDefault(emptyList()).firstOrNull { it.title?.toString() == "Taskbar" }?.root
            ?: return failedQuietly("no taskbar")
        // The icon's description is the app's name, then its window's when they differ ("Settings Internet Active").
        val icon = findAll(taskbar) { n -> n.contentDescription?.toString()?.let { it == app.label || it.startsWith("${app.label} ") } == true &&
            n.actionList.any { it.label?.toString() == "Shortcut Menu" } }.firstOrNull() ?: return failedQuietly("no taskbar icon for ${app.label}")
        val launcher = taskbar.packageName?.toString()
        fun launcherWindows() = runCatching { service.windows }.getOrDefault(emptyList()).filter { it.root?.packageName?.toString() == launcher }
        val before = launcherWindows().map { it.id }.toSet()
        var menuOpened = false
        var unfolded = false
        icon.performAction(icon.actionList.first { it.label?.toString() == "Shortcut Menu" }.id)
        repeat(20) {
            Thread.sleep(100)
            service.clearCache()
            for (w in launcherWindows()) {
                val root = w.root ?: continue
                if (w.id !in before) menuOpened = true
                val entry = findAll(root) { it.text?.toString() == label || it.contentDescription?.toString() == label }.firstOrNull()
                if (entry == null) {
                    // The launcher may fold the app's own shortcuts under a toggle (the menu then lies inside the
                    // taskbar's window, not in one of its own): open that once and look again.
                    if (!unfolded) findAll(root) { n -> n.actionList.any { it.label?.toString() == UNFOLD } }.firstOrNull()?.let { t ->
                        unfolded = true
                        t.performAction(t.actionList.first { it.label?.toString() == UNFOLD }.id)
                    }
                    continue
                }
                var target: AccessibilityNodeInfo? = entry
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { Log.i(tag, "quick action '$label' through the taskbar"); return true }
            }
        }
        // The menu came up without that entry: close it. Only a window of its own that came up is closed this way, so
        // Back never goes to the app in front when no menu opened. A menu inside the taskbar's window stays open: it
        // offers nothing that closes it, and Back would go to the app in front.
        if (menuOpened) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return failedQuietly("'$label' not in ${app.label}'s taskbar menu")
    }

    private fun failedQuietly(why: String): Boolean { Log.w(tag, "quick action: $why"); return false }

    private companion object {
        /** The launcher's label for the toggle that shows an app's own shortcuts in its taskbar menu (English only). */
        const val UNFOLD = "Show app shortcuts menu"
    }

    private fun findAll(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && out.size < 8) {
            val n = queue.removeFirst()
            if (match(n)) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    fun appInfo(app: FrontApp) = Env.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
    fun notifications(app: FrontApp) = Env.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.pkg))
    fun language(app: FrontApp) {
        if (!Env.launch(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", app.pkg, null)), quiet = true)) appInfo(app)
    }
    /** The permissions page itself is only for apps that may grant permissions; everyone else gets App info, where it is one click away. */
    fun permissions(app: FrontApp) {
        val direct = Intent("android.intent.action.MANAGE_APP_PERMISSIONS").putExtra("android.intent.extra.PACKAGE_NAME", app.pkg)
        if (!Env.launch(direct, quiet = true)) appInfo(app)
    }
    fun openByDefault(app: FrontApp) {
        if (!Env.launch(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.fromParts("package", app.pkg, null)), quiet = true)) appInfo(app)
    }
    /** A new window of the app: its launcher activity in a task of its own (apps that allow only one window come to the front). */
    fun newWindow(app: FrontApp) {
        val launch = service.packageManager.getLaunchIntentForPackage(app.pkg) ?: return Unit.also { failed("no launcher entry") }
        Env.launch(launch.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
    }
}

/** What the Window menu needs to know when it opens. */
data class CaptionState(val maximized: Boolean, val actions: Set<String>)

/**
 * The Googlebook's own keyboard shortcuts for the Window menu's actions, as its Keyboard shortcuts screen (G + /)
 * lists them and `dumpsys input` binds them (this device's defaults, 2026-10-19; Customize there can change them,
 * and an app can't read the changes). "G" is the Googlebook key, as that screen draws it.
 */
object WindowShortcuts {
    val minimize = listOf("G", "-")
    val maximize = listOf("G", "=")
    val left = listOf("G", "[")
    val right = listOf("G", "]")
    /** "Use full screen". */
    val fullscreen = listOf("G", "Ctrl", "↑")
    /** "Use split screen with app on the left". */
    val split = listOf("G", "Ctrl", "←")
    /** "Close current window". */
    val close = listOf("G", "W")
}

/** A shortcut's keys as small caps at the end of a menu row; the Googlebook key is a bold G. */
@Composable
private fun KeyCaps(keys: List<String>, enabled: Boolean) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (k in keys) Box(
            Modifier.height(20.dp).widthIn(min = 20.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)).padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(k, color = color, fontSize = 11.sp, lineHeight = 11.sp, maxLines = 1,
                fontWeight = if (k == "G") FontWeight.Bold else FontWeight.Medium)
        }
    }
}

/** SystemUI's own action labels on a caption's maximize button. */
private object CaptionActions {
    const val LEFT = "Resize app window left"
    const val RIGHT = "Resize app window right"
    const val MAX_OR_RESTORE = "Maximize or restore window size"
    const val FULLSCREEN = "Enter Fullscreen"
    const val SPLIT = "Enter Split Screen"
}

/**
 * The app menu bar's words, right of the clock: the app's name (semibold, as a menu bar's first word is) and then
 * Window, Tab (Chrome, debug builds), Actions (an app that publishes some), Widgets (an app that publishes some) and Settings. Each opens its popup; with one open, pointing at another opens that one ([onHover]), as in a
 * menu bar, whatever "Switch popups on hover" says for the items.
 */
@Composable
fun AppMenuStrip(
    app: FrontApp,
    /** The app publishes quick actions: the bar has an Actions word. */
    hasActions: Boolean,
    /** The app publishes home screen widgets: the bar has a Widgets word. */
    hasWidgets: Boolean,
    look: StripLook,
    heightDp: Dp,
    open: AppMenu?,
    onWidth: (Int) -> Unit,
    onPlaced: (AppMenu, Rect) -> Unit,
    onClick: (AppMenu) -> Unit,
    onHover: (AppMenu) -> Unit,
) {
    MeasuredStrip(bias = -1f, onWidth = onWidth) {
        Row(Modifier.height(heightDp), verticalAlignment = Alignment.CenterVertically) {
            for (menu in AppMenu.entries.filter { it.shownFor(app, hasActions, hasWidgets) }) {
                val word = when (menu) {
                    AppMenu.NAME -> app.label
                    AppMenu.WINDOW -> stringResource(R.string.appmenu_window)
                    AppMenu.TAB -> stringResource(R.string.appmenu_tab)
                    AppMenu.ACTIONS -> stringResource(R.string.appmenu_actions)
                    AppMenu.WIDGETS -> stringResource(R.string.appmenu_widgets)
                    AppMenu.SETTINGS -> stringResource(R.string.appmenu_settings)
                }
                MenuWord(word, bold = menu == AppMenu.NAME, look = look, open = open == menu,
                    onPlaced = { onPlaced(menu, it) }, onClick = { onClick(menu) }, onHover = { onHover(menu) })
            }
        }
    }
}

@Composable
private fun MenuWord(word: String, bold: Boolean, look: StripLook, open: Boolean, onPlaced: (Rect) -> Unit, onClick: () -> Unit, onHover: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Box(
        Modifier.fillMaxHeight()
            .onGloballyPositioned { c -> c.boundsInWindow().let { r: ComposeRect -> onPlaced(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())) } }
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.DropdownList, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.height(24.dp).clip(RoundedCornerShape(50))
                .background(if (hovered || open) look.fg.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(word, color = look.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
                style = TextStyle(fontFamily = Fonts.bar, fontWeight = if (bold) FontWeight.SemiBold else Fonts.barWeight,
                    fontSize = look.textSp, lineHeight = look.textSp))
        }
    }
}

/** The app's own menu: App info, Screenshot, a new window. */
@Composable
fun AppNameMenu(app: FrontApp, windows: AppWindows, host: MenuHost) {
    MenuCard(Sym.DESKTOP_WINDOWS, app.label) {
        MenuEntry(Sym.INFO, stringResource(R.string.appmenu_app_info)) { host.close(); windows.appInfo(app) }
        MenuEntry(Sym.SCREENSHOT_MONITOR, stringResource(R.string.appmenu_screenshot)) {
            host.afterClose { Env.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }
        }
        MenuDivider()
        MenuEntry(Sym.NEW_WINDOW, stringResource(R.string.appmenu_new_window)) { host.close(); windows.newWindow(app) }
    }
}

/** The Window menu: the window's caption buttons, by name. [caption] is null for a window without one. */
@Composable
fun WindowMenu(app: FrontApp, caption: CaptionState?, windows: AppWindows, host: MenuHost) {
    MenuCard(Sym.DESKTOP_WINDOWS, stringResource(R.string.appmenu_window), subtitle = if (caption == null) stringResource(R.string.appmenu_no_caption) else null) {
        val on = caption != null
        fun has(label: String) = caption?.actions?.contains(label) == true
        fun press(button: String, label: String? = null): () -> Unit = { host.afterClose { windows.press(app, button, label) } }
        @Composable fun Entry(icon: String, label: Int, keys: List<String>?, enabled: Boolean, onClick: () -> Unit) =
            MenuEntry(icon, stringResource(label), enabled = enabled, trailing = keys?.let { { KeyCaps(it, enabled) } }, onClick = onClick)
        Entry(Sym.MINIMIZE, R.string.appmenu_minimize, WindowShortcuts.minimize, on, press("minimize_window"))
        Entry(if (caption?.maximized == true) Sym.FILTER_NONE else Sym.CROP_SQUARE,
            if (caption?.maximized == true) R.string.appmenu_restore else R.string.appmenu_maximize, WindowShortcuts.maximize,
            has(CaptionActions.MAX_OR_RESTORE), press("maximize_window", CaptionActions.MAX_OR_RESTORE))
        Entry(Sym.SPLITSCREEN_LEFT, R.string.appmenu_snap_left, WindowShortcuts.left, has(CaptionActions.LEFT), press("maximize_window", CaptionActions.LEFT))
        Entry(Sym.SPLITSCREEN_RIGHT, R.string.appmenu_snap_right, WindowShortcuts.right, has(CaptionActions.RIGHT), press("maximize_window", CaptionActions.RIGHT))
        Entry(Sym.FULLSCREEN, R.string.appmenu_fullscreen, WindowShortcuts.fullscreen, has(CaptionActions.FULLSCREEN),
            press("maximize_window", CaptionActions.FULLSCREEN))
        Entry(Sym.SPLITSCREEN, R.string.appmenu_split, WindowShortcuts.split, has(CaptionActions.SPLIT), press("maximize_window", CaptionActions.SPLIT))
        MenuDivider()
        Entry(Sym.MENU, R.string.appmenu_window_options, null, on, press("open_menu_button"))
        Entry(Sym.CLOSE, R.string.appmenu_close, WindowShortcuts.close, on, press("close_window"))
    }
}

/** The app's settings pages in Android's Settings, one click each. */
@Composable
fun AppSettingsMenu(app: FrontApp, windows: AppWindows, host: MenuHost) {
    MenuCard(Sym.SETTINGS, stringResource(R.string.appmenu_settings), subtitle = app.label) {
        MenuEntry(Sym.NOTIFICATIONS, stringResource(R.string.appmenu_notifications)) { host.close(); windows.notifications(app) }
        MenuEntry(Sym.LANGUAGE, stringResource(R.string.appmenu_language)) { host.close(); windows.language(app) }
        MenuEntry(Sym.SHIELD, stringResource(R.string.appmenu_permissions)) { host.close(); windows.permissions(app) }
        MenuEntry(Sym.LINK, stringResource(R.string.appmenu_open_by_default)) { host.close(); windows.openByDefault(app) }
    }
}

/** What the Tabs menu read: Chrome's tabs, or null when the bridge to Chrome's debugging socket isn't there. */
private class ReadTabs(val tabs: List<LocalBridge.Tab>?)

/**
 * The tabs Chrome has loaded in its windows (PROTOTYPE: through Chrome's debugging socket, see [LocalBridge]); a tab
 * Chrome hasn't loaded since it started is missing. Chrome doesn't say which window a tab is in, so it is every
 * window's, in the order the tabs were opened; the one marked is the front window's current tab (the window's title
 * names it). A click brings that tab to the front; Next and Previous step along the list.
 */
@Composable
fun TabMenu(current: String?, host: MenuHost) {
    val read by produceState<ReadTabs?>(null) { value = ReadTabs(withContext(Dispatchers.IO) { LocalBridge.chromeTabs() }) }
    val tabs = read?.tabs?.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
    // The list is most recently used first: of several tabs with the window's title, the first is the one in front.
    val currentId = read?.tabs?.firstOrNull { it.title == current }?.id
    fun go(id: String) { host.close(); Thread { LocalBridge.activateChromeTab(id) }.start() }
    fun step(by: Int) {
        val list = tabs ?: return
        val at = list.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        go(list[(at + by).mod(list.size)].id)
    }
    MenuCard(Sym.TAB, stringResource(R.string.appmenu_tab), subtitle = when {
        read == null -> stringResource(R.string.appmenu_tab_loading)
        tabs == null -> stringResource(R.string.appmenu_tab_unreachable)
        else -> stringResource(R.string.appmenu_tab_scope)
    }) {
        if (tabs.isNullOrEmpty()) return@MenuCard
        MenuEntry(Sym.ARROW_FORWARD, stringResource(R.string.appmenu_tab_next)) { step(1) }
        MenuEntry(Sym.ARROW_BACK, stringResource(R.string.appmenu_tab_previous)) { step(-1) }
        MenuDivider()
        for (t in tabs) {
            MenuEntry(if (t.id == currentId) Sym.CHECK else Sym.TAB, t.title.ifBlank { t.url }.take(70),
                sub = Uri.parse(t.url).host?.removePrefix("www.")) { go(t.id) }
        }
    }
}

/** The quick actions [app] publishes, as its icon's right-click menu shows them; a click starts one. */
@Composable
fun ActionsMenu(app: FrontApp, actions: List<AppAction>, run: (AppAction) -> Unit, host: MenuHost) {
    MenuCard(Sym.BOLT, stringResource(R.string.appmenu_actions), subtitle = app.label) {
        for (a in actions) MenuEntry(Sym.BOLT, a.label, image = a.icon) { host.afterClose { run(a) } }
    }
}
