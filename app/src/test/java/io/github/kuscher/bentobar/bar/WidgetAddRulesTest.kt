package io.github.kuscher.bentobar.bar

import io.github.kuscher.bentobar.bar.WidgetAddRules.Phase
import io.github.kuscher.bentobar.bar.WidgetAddRules.Progress
import io.github.kuscher.bentobar.bar.WidgetAddRules.Seen
import io.github.kuscher.bentobar.bar.WidgetAddRules.Step
import io.github.kuscher.bentobar.bar.WidgetAddRules.Why
import org.junit.Assert.assertEquals
import org.junit.Test

/** Adding a widget through the launcher's picker, step by step, as the Googlebook's picker answered (2026-10-21). */
class WidgetAddRulesTest {
    private val up = Seen(picker = true)

    /** Runs [seen] (one look every 100 ms from 0) and returns the steps that weren't waits, with when they came. */
    private fun run(vararg seen: Seen): List<Pair<Long, Step>> {
        var p = Progress()
        val out = mutableListOf<Pair<Long, Step>>()
        seen.forEachIndexed { i, s ->
            val (q, step) = WidgetAddRules.next(p, s, i * 100L)
            p = q
            if (step != Step.Wait) out += i * 100L to step
        }
        return out
    }

    @Test fun theLaunchersOwnWordsNameTheAction() = assertEquals("Add Calendar schedule widget", WidgetAddRules.addLabel("Calendar schedule"))

    @Test fun anAppInViewIsClickedAMomentAfterThePickerIsUp() {
        // Calendar: in the list as the picker opens, its widget there once clicked, the picker gone after the add.
        // Its row, then its page, each stay a moment before the click, so the picker can be followed.
        val steps = run(Seen(false), *Array(5) { up.copy(row = true) }, *Array(4) { up.copy(add = true) }, Seen(false))
        assertEquals(listOf(500L to Step.ClickRow, 900L to Step.Add, 1000L to Step.Done(closed = true)), steps)
    }

    @Test fun anAppFurtherDownIsScrolledToALittleAtATime() {
        // WhatsApp: four scrolls down the list, each after the list has settled.
        val steps = run(*Array(7) { up.copy(listScrolls = true) }, *Array(4) { up.copy(row = true, listScrolls = true) })
        assertEquals(listOf(300L to Step.ScrollList, 600L to Step.ScrollList, 1000L to Step.ClickRow), steps)
    }

    @Test fun thePickerNeverComing() {
        // Another launcher, or one that dropped the way in: three seconds, then the add gives up.
        val steps = run(*Array(32) { Seen(false) })
        assertEquals(listOf(3100L to Step.GiveUp(Why.NO_PICKER)), steps)
    }

    @Test fun anAppNotInTheListGivesUpOnceTheListEnds() {
        val steps = run(*Array(4) { up.copy(listScrolls = true) }, *Array(16) { up })
        assertEquals(Step.ScrollList, steps.first().second)
        assertEquals(1900L to Step.GiveUp(Why.NO_APP), steps.last())
    }

    @Test fun aWidgetLowerOnTheAppsPageIsScrolledTo() {
        // Calendar month view sits below Calendar schedule: the app's widgets scroll once before its Add is there.
        val steps = run(*Array(5) { up.copy(row = true) }, *Array(3) { up.copy(widgetsScroll = true) }, *Array(4) { up.copy(add = true) })
        assertEquals(listOf(400L to Step.ClickRow, 700L to Step.ScrollWidgets, 1100L to Step.Add), steps)
    }

    @Test fun aWidgetThatNeverShowsGivesUp() {
        val steps = run(*Array(5) { up.copy(row = true) }, *Array(21) { up })
        assertEquals(listOf(400L to Step.ClickRow, 2500L to Step.GiveUp(Why.NO_WIDGET)), steps)
    }

    @Test fun thePickerClosedBeforeTheAddIsNoAdd() {
        // Someone pressed Escape: nothing was added, so it isn't reported as added.
        assertEquals(listOf(400L to Step.ClickRow, 500L to Step.GiveUp(Why.NO_WIDGET)), run(*Array(5) { up.copy(row = true) }, Seen(false)))
    }

    @Test fun aPickerThatStaysUpAfterTheAddStillCountsAsAdded() {
        val steps = run(*Array(5) { up.copy(row = true) }, *Array(5) { up.copy(add = true) }, *Array(25) { up })
        assertEquals(Step.Done(closed = false), steps.last().second)
        assertEquals(3400L, steps.last().first)
    }

    @Test fun scrollingStopsAtItsLimit() {
        var p = Progress(phase = Phase.FINDING, since = 0, scrolls = WidgetAddRules.MAX_SCROLLS)
        val (q, step) = WidgetAddRules.next(p, up.copy(listScrolls = true), 1000)
        assertEquals(Step.Wait, step)
        p = q
        assertEquals(Step.GiveUp(Why.NO_APP), WidgetAddRules.next(p, up.copy(listScrolls = true), 1600).second)
    }
}
