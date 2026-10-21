package io.github.kuscher.bentobar.bar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.Toast
import io.github.kuscher.bentobar.R
import io.github.kuscher.bentobar.bar.WidgetAddRules.Step
import io.github.kuscher.bentobar.bar.WidgetAddRules.Why

/**
 * Adds another app's widget to the home screen through the home screen's own widget picker ([WidgetAddRules] has the
 * steps). The picker is seen doing it, at a pace that can be followed: it comes up (sending every window away, as the
 * home screen does), turns to the app's page, adds the widget and closes onto the home screen with the widget on it.
 * A toast then says how the windows come back.
 *
 * The picker is the launcher's own (Pixel's, on a Googlebook), opened the way its home screen's menu opens it: a
 * HOME intent with `extra_open_widget_picker`, which any app may send but which no document promises. Everything read
 * here is the picker's: its list's names, and its widgets' Add actions.
 */
class WidgetAdder(private val service: AccessibilityService, private val main: Handler) {
    private val tag = "BentoBarApps"
    /** An add is under way: a second one waits for it. */
    @Volatile var busy = false
        private set
    @Volatile private var stopped = false
    private val home: String? by lazy {
        service.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }

    /** Adds [w], one of [app]'s widgets. */
    fun add(app: FrontApp, w: AppWidget) {
        if (busy || stopped) return
        busy = true
        Thread { run(app, w) }.apply { name = "BentoBar widget add" }.start()
    }

    /** The service is going: an add under way stops at its next look. */
    fun stop() { stopped = true }

    /** Ends the add with [message] as a toast. */
    private fun finish(message: String) = main.post {
        busy = false
        if (!stopped) Toast.makeText(service, message, Toast.LENGTH_LONG).show()
    }

    /** The steps, on a thread of their own: each look at the picker is a fresh read of its window. */
    private fun run(app: FrontApp, w: AppWidget) {
        val t0 = SystemClock.uptimeMillis()
        val open = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).putExtra(OPEN_PICKER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        home?.let(open::setPackage)
        runCatching { service.startActivity(open) }.onFailure { Log.w(tag, "widget picker: can't open the home screen: ${it.javaClass.simpleName}") }
        val want = WidgetAddRules.addLabel(w.label)
        var p = WidgetAddRules.Progress(since = t0)
        while (true) {
            if (stopped) { busy = false; return }
            Thread.sleep(LOOK_MS)
            val now = SystemClock.uptimeMillis()
            service.clearCache()
            val picker = pickerRoot()
            val nodes = picker?.let(::all).orEmpty()
            val list = nodes.firstOrNull { it.viewIdResourceName?.endsWith("/$LIST") == true }
            val row = list?.let(::all)?.firstOrNull { it.viewIdResourceName == null && it.text?.toString() == app.label }
            val add = nodes.firstOrNull { n -> n.actionList.any { it.label?.toString() == want } }
            // The app's widgets are the other list that scrolls: the right-hand pane.
            val pane = nodes.firstOrNull { it != list && it.viewIdResourceName == null && it.canScrollForward() }
            val seen = WidgetAddRules.Seen(picker != null, row != null, list?.canScrollForward() == true, add != null, pane != null)
            val (q, step) = WidgetAddRules.next(p, seen, now)
            p = q
            when (step) {
                Step.Wait -> {}
                Step.ScrollList -> list?.performAction(AccessibilityAction.ACTION_SCROLL_FORWARD.id)
                Step.ClickRow -> generateSequence(row) { it.parent }.firstOrNull { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Step.ScrollWidgets -> pane?.performAction(AccessibilityAction.ACTION_SCROLL_FORWARD.id)
                Step.Add -> add?.performAction(add.actionList.first { it.label?.toString() == want }.id)
                is Step.Done -> {
                    Log.i(tag, "widget ${w.provider.flattenToShortString()} added in ${now - t0} ms (picker closed: ${step.closed})")
                    finish(service.getString(R.string.appmenu_widget_added, w.label))
                    return
                }
                is Step.GiveUp -> {
                    Log.w(tag, "widget ${w.provider.flattenToShortString()}: gave up (${step.why}) after ${now - t0} ms")
                    finish(service.getString(when (step.why) {
                        Why.NO_PICKER -> R.string.appmenu_widget_no_picker
                        Why.NO_APP, Why.NO_WIDGET -> R.string.appmenu_widget_pick_it
                    }, w.label))
                    return
                }
            }
        }
    }

    /** The picker's window: the launcher's, holding its widget catalogue. Null while it isn't up. */
    private fun pickerRoot(): AccessibilityNodeInfo? = runCatching { service.windows }.getOrDefault(emptyList())
        .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        .mapNotNull { runCatching { it.root }.getOrNull() }
        .firstOrNull { r -> (home == null || r.packageName?.toString() == home) && all(r).any { it.viewIdResourceName?.endsWith("/$CATALOG") == true } }

    private fun AccessibilityNodeInfo.canScrollForward() = actionList.any { it.id == AccessibilityAction.ACTION_SCROLL_FORWARD.id }

    private fun all(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    private companion object {
        /** What the launcher's home screen reads to open its widget picker (Launcher3's `Launcher.onNewIntent`). */
        const val OPEN_PICKER = "extra_open_widget_picker"
        /** The picker's view ids: its whole catalogue, and the list of apps on its left. */
        const val CATALOG = "widgets_catalog"
        const val LIST = "personal_widgets_list"
        const val LOOK_MS = 100L
        /** The picker has a few hundred nodes; this keeps a strange window from taking long to read. */
        const val MAX_NODES = 3000
    }
}
