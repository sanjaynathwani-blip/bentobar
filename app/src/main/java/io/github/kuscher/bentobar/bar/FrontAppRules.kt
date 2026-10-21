package io.github.kuscher.bentobar.bar

/**
 * One window from the accessibility window list, as the app menu bar sees it: its id, type, whether it is the
 * active one, and where it is. Nothing of what it shows.
 */
data class SeenWindow(
    val id: Int,
    val type: Int,
    val active: Boolean,
    val focused: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Which app the app menu bar is for (pure, unit-tested). On a Googlebook every desktop window has a caption: a
 * window of SystemUI's own, of type TYPE_WINDOW_CONTROL, lying along the app window's top edge, with the same
 * left and right (its buttons minimize, maximize, snap and close the app window). The app in front is the
 * active application window.
 */
object FrontAppRules {
    /** AccessibilityWindowInfo.TYPE_APPLICATION. */
    const val APPLICATION = 1
    /** AccessibilityWindowInfo.TYPE_WINDOW_CONTROL: a desktop window's caption. */
    const val WINDOW_CONTROL = 7

    /**
     * The app window in front: the active application window, else the focused one. Null when neither is one
     * (BentoBar's own popup has the focus, a dialog of the system's is on top): the bar then keeps the app it had,
     * see [next].
     */
    fun front(windows: List<SeenWindow>): SeenWindow? =
        windows.firstOrNull { it.type == APPLICATION && it.active } ?: windows.firstOrNull { it.type == APPLICATION && it.focused }

    /**
     * [app]'s caption: a window-control window along its top edge, as wide as it. A window that shows two panes
     * (Settings) is listed as one app window per pane, under one caption as wide as the whole window: then the caption
     * is the first window-control window above it in the list (captions lie directly over their window), past the
     * other panes (app windows at the same top), if it covers it and every pane passed.
     */
    fun caption(windows: List<SeenWindow>, app: SeenWindow): SeenWindow? {
        windows.firstOrNull { it.type == WINDOW_CONTROL && it.left == app.left && it.right == app.right && it.top == app.top }?.let { return it }
        val panes = mutableListOf(app)
        for (above in windows.subList(0, windows.indexOf(app).coerceAtLeast(0)).asReversed()) {
            if (above.type == APPLICATION && above.top == app.top) { panes += above; continue }
            return above.takeIf { c -> c.type == WINDOW_CONTROL && c.top == app.top && panes.all { c.left <= it.left && c.right >= it.right } }
        }
        return null
    }

    /**
     * The window the bar follows after a change: the new front window, or with none (our popup took the focus,
     * or the list ended early under a dialog) the one it followed before. A window missing from the list has
     * not necessarily gone (the list can end early), so the old one is kept rather than dropped.
     */
    fun next(windows: List<SeenWindow>, before: Int?): Int? = front(windows)?.id ?: before

    /** What the app menu bar does after a look at the window list. */
    sealed interface Pick {
        /** Menus for the app of window [id], package [pkg]. */
        data class Show(val id: Int, val pkg: String) : Pick
        /** No menus: no app in front, the home screen, or the window the bar had has gone. */
        data object Hide : Pick
        /** The menus stay with the app they were for (our own window or popup in front, or the list ended early). */
        data object Keep : Pick
        /** No menus until the window in front can be read: it was just added and its tree isn't there yet. Look again soon. */
        data object Unreadable : Pick
    }

    /**
     * Which app the menus are for. [following] is the window the bar followed before, [shownId] the window whose
     * menus are shown now, [pkgOf] the package of a window (its root node's), null when it can't be read yet,
     * [home] the home screen's package and [self] BentoBar's own.
     * - The window followed but missing from a list that holds other app windows has gone: hide. With no app window
     *   listed at all, the list ended early (a dialog on top): keep.
     * - A window that can't be read yet never gets the old app's menus (a Close there would close the window
     *   behind): [Pick.Unreadable].
     * - The home screen (no caption) gets none; a full-screen app (no caption either) is still an app.
     * - BentoBar's own settings window keeps the app before it, if that app's window is still listed.
     */
    fun pick(windows: List<SeenWindow>, following: Int?, shownId: Int?, pkgOf: (Int) -> String?, home: String?, self: String): Pick {
        val id = next(windows, following) ?: return Pick.Hide
        val w = windows.firstOrNull { it.id == id }
            ?: return if (windows.any { it.type == APPLICATION }) Pick.Hide else Pick.Keep
        val pkg = pkgOf(id) ?: return Pick.Unreadable
        if (pkg == self) return if (shownId != null && windows.any { it.id == shownId }) Pick.Keep else Pick.Hide
        if (pkg == home && caption(windows, w) == null) return Pick.Hide
        return Pick.Show(id, pkg)
    }
}
