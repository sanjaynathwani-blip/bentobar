package io.github.kuscher.bentobar.bar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The window list as a Googlebook gave it (2026-10-19): Terminal in front with its caption, Text maximized behind. */
class FrontAppRulesTest {
    private val overlay = SeenWindow(129, 4, active = false, focused = false, 2407, 0, 2601, 59)
    private val statusBar = SeenWindow(7, 3, active = false, focused = false, 0, 0, 2880, 59)
    private val terminalCaption = SeenWindow(105, 7, active = false, focused = false, 0, 59, 1720, 124)
    private val terminal = SeenWindow(99, 1, active = true, focused = true, 0, 59, 1720, 1709)
    private val textCaption = SeenWindow(113, 7, active = false, focused = false, 0, 59, 2880, 124)
    private val text = SeenWindow(114, 1, active = false, focused = false, 0, 59, 2880, 1709)
    private val all = listOf(overlay, statusBar, terminalCaption, terminal, textCaption, text)

    @Test fun theActiveAppWindowIsInFront() = assertEquals(terminal, FrontAppRules.front(all))

    @Test fun eachWindowGetsItsOwnCaption() {
        assertEquals(terminalCaption, FrontAppRules.caption(all, terminal))
        // Text's caption starts at the same corner as Terminal's: the width tells them apart.
        assertEquals(textCaption, FrontAppRules.caption(all, text))
    }

    @Test fun aTwoPaneWindowIsListedAsItsRightPaneUnderItsWholeCaption() {
        val settingsCaption = SeenWindow(149, 7, active = false, focused = false, 721, 247, 2794, 312)
        val settings = SeenWindow(142, 1, active = true, focused = true, 1405, 247, 2794, 1543)
        assertEquals(settingsCaption, FrontAppRules.caption(listOf(statusBar, settingsCaption, settings, textCaption, text), settings))
        // Another window's caption above it is not taken for its own.
        assertNull(FrontAppRules.caption(listOf(statusBar, terminalCaption, settings.copy(top = 59)), settings.copy(top = 59, left = 900)))
    }

    @Test fun aFullScreenWindowHasNoCaption() =
        assertNull(FrontAppRules.caption(listOf(statusBar, terminal.copy(top = 0, bottom = 1800)), terminal.copy(top = 0, bottom = 1800)))

    @Test fun ourPopupTakingTheFocusKeepsTheAppItHad() {
        val popup = SeenWindow(140, 4, active = true, focused = true, 40, 70, 400, 500)
        val list = listOf(popup, statusBar, terminalCaption, terminal.copy(active = false, focused = false))
        assertEquals(99, FrontAppRules.next(list, before = 99))
    }

    @Test fun anotherAppComingForwardIsFollowed() {
        val list = listOf(statusBar, terminalCaption, terminal.copy(active = false, focused = false), textCaption, text.copy(active = true, focused = true))
        assertEquals(114, FrontAppRules.next(list, before = 99))
    }

    @Test fun nothingBeforeAndNothingActiveIsNothing() = assertNull(FrontAppRules.next(listOf(statusBar, overlay), before = null))
}
