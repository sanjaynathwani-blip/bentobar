package io.github.kuscher.bentobar.bar

/**
 * How the Widgets menu adds another app's widget to the home screen (fork). Android lets only the home screen add
 * another app's widget, so the launcher does it: its own widget picker is opened, the app is found in the picker's list
 * (scrolling down as needed) and clicked, the widget's own "Add … widget" action is pressed, and the picker closes by
 * itself. [WidgetAdder] looks at the picker and carries out each [Step]; which step comes next, and how long each may
 * take, is decided here. The picker is on screen throughout, so each move waits for the last to settle and holds a
 * moment before the next: the app's row before it is clicked, its page before Add. Pure, unit-tested.
 */
object WidgetAddRules {
    /** How long the picker may take to come up after it was asked for. */
    const val OPEN_MS = 3000L
    /** How long the app's row may take to turn up once nothing more can be scrolled into view. */
    const val FIND_MS = 1500L
    /** How long the widget may take to turn up in the app's page once nothing more can be scrolled into view. */
    const val ADD_MS = 2000L
    /** How long the picker may stay up after the widget was added (it closes by itself in about half a second). */
    const val CLOSE_MS = 2500L
    /** How long a list is left to settle (the picker coming up, a scroll) before it is scrolled again. */
    const val SETTLE_MS = 300L
    /** How long the app's row, then its page, are shown before they are clicked: long enough to see where it went. */
    const val PAUSE_MS = 400L
    /** Scrolls of the app list (each moves it about a page), and of an app's widgets, before giving up. */
    const val MAX_SCROLLS = 30
    const val MAX_WIDGET_SCROLLS = 10

    /** The picker's accessibility action on a widget, in the launcher's words (English, as the Window menu's are). */
    fun addLabel(widget: String) = "Add $widget widget"

    /**
     * What the picker shows now: whether it is up at all, whether the app's row is in its list, whether its list
     * could scroll further, whether the widget's Add action is there, and whether the app's widgets could scroll further.
     */
    data class Seen(
        val picker: Boolean,
        val row: Boolean = false,
        val listScrolls: Boolean = false,
        val add: Boolean = false,
        val widgetsScroll: Boolean = false,
    )

    enum class Phase { OPENING, FINDING, ADDING, CLOSING }

    /** Where an add stands: its [phase], since when ([since], ms on any steady clock) and how far it has scrolled. */
    data class Progress(val phase: Phase = Phase.OPENING, val since: Long = 0, val scrolls: Int = 0, val widgetScrolls: Int = 0)

    /** Why an add stopped short: the picker never came, the app or its widget wasn't in it. */
    enum class Why { NO_PICKER, NO_APP, NO_WIDGET }

    sealed interface Step {
        data object Wait : Step
        data object ScrollList : Step
        data object ClickRow : Step
        data object ScrollWidgets : Step
        data object Add : Step
        /** Added. [closed]: the picker closed by itself, as it does after an add. */
        data class Done(val closed: Boolean) : Step
        data class GiveUp(val why: Why) : Step
    }

    /** The next step after seeing [seen] at [now], and where the add stands then. */
    fun next(p: Progress, seen: Seen, now: Long): Pair<Progress, Step> = when (p.phase) {
        Phase.OPENING -> when {
            seen.picker -> next(p.copy(phase = Phase.FINDING, since = now), seen, now)
            now - p.since > OPEN_MS -> p to Step.GiveUp(Why.NO_PICKER)
            else -> p to Step.Wait
        }
        Phase.FINDING -> when {
            !seen.picker -> p to Step.GiveUp(Why.NO_PICKER)
            seen.row -> if (now - p.since < PAUSE_MS) p to Step.Wait else p.copy(phase = Phase.ADDING, since = now) to Step.ClickRow
            // Each scroll starts the clock again: the list is still moving, and what it brings has to be read first.
            seen.listScrolls && p.scrolls < MAX_SCROLLS -> if (now - p.since < SETTLE_MS) p to Step.Wait
                else p.copy(since = now, scrolls = p.scrolls + 1) to Step.ScrollList
            now - p.since > FIND_MS -> p to Step.GiveUp(Why.NO_APP)
            else -> p to Step.Wait
        }
        Phase.ADDING -> when {
            !seen.picker -> p to Step.GiveUp(Why.NO_WIDGET)
            seen.add -> if (now - p.since < PAUSE_MS) p to Step.Wait else p.copy(phase = Phase.CLOSING, since = now) to Step.Add
            seen.widgetsScroll && p.widgetScrolls < MAX_WIDGET_SCROLLS -> if (now - p.since < SETTLE_MS) p to Step.Wait
                else p.copy(since = now, widgetScrolls = p.widgetScrolls + 1) to Step.ScrollWidgets
            now - p.since > ADD_MS -> p to Step.GiveUp(Why.NO_WIDGET)
            else -> p to Step.Wait
        }
        Phase.CLOSING -> when {
            !seen.picker -> p to Step.Done(closed = true)
            now - p.since > CLOSE_MS -> p to Step.Done(closed = false)
            else -> p to Step.Wait
        }
    }
}
