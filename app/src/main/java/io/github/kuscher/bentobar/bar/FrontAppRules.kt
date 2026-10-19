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
     * (Settings) is listed as its right pane only, under a caption as wide as the whole window: then the caption is
     * the window-control window just above it in the list (captions lie directly over their window), if it covers it.
     */
    fun caption(windows: List<SeenWindow>, app: SeenWindow): SeenWindow? {
        windows.firstOrNull { it.type == WINDOW_CONTROL && it.left == app.left && it.right == app.right && it.top == app.top }?.let { return it }
        val above = windows.getOrNull(windows.indexOf(app) - 1) ?: return null
        return above.takeIf { it.type == WINDOW_CONTROL && it.top == app.top && it.left <= app.left && it.right >= app.right }
    }

    /**
     * The window the bar follows after a change: the new front window, or with none (our popup took the focus,
     * or the list ended early under a dialog) the one it followed before. A window missing from the list has
     * not necessarily gone (the list can end early), so the old one is kept rather than dropped.
     */
    fun next(windows: List<SeenWindow>, before: Int?): Int? = front(windows)?.id ?: before
}
