package com.payriff.android.card

import com.payriff.android.internal.card.SecureFlagHolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CONTRACT §16.0 item 4: the card form's `FLAG_SECURE` is counted per window. Every window that shows a form (the
 * activity's and, for a form in a dialog or bottom sheet, that window too) stays secure while any form is attached,
 * and only a flag the SDK added is removed afterwards.
 */
class ScreenProtectionTest {

    private class FakeWindow(var secure: Boolean = false) {
        val changes = mutableListOf<Boolean>()
    }

    private val holds = SecureFlagHolds<FakeWindow>(
        isSecure = { it.secure },
        setSecure = { window, on ->
            window.secure = on
            window.changes += on
        },
    )

    @Test
    fun theFlagStaysOnUntilTheLastFormOfAWindowLeaves() {
        val window = FakeWindow()
        holds.acquire(window)
        holds.acquire(window)
        assertTrue(window.secure)
        assertEquals("set once", listOf(true), window.changes)

        holds.release(window)
        assertTrue("another form still shows card data", window.secure)
        holds.release(window)
        assertFalse("restored after the last form", window.secure)
        assertFalse(holds.isHeld(window))

        holds.release(window)
        assertEquals("an extra release changes nothing", listOf(true, false), window.changes)
    }

    @Test
    fun aWindowTheAppHadMadeSecureStaysSecure() {
        val window = FakeWindow(secure = true)
        holds.acquire(window)
        holds.release(window)
        assertTrue(window.secure)
        assertTrue("never touched", window.changes.isEmpty())
    }

    @Test
    fun eachWindowIsCountedOnItsOwn() {
        val activity = FakeWindow()
        val bottomSheet = FakeWindow()
        holds.acquire(activity)
        holds.acquire(bottomSheet)
        assertTrue(activity.secure && bottomSheet.secure)

        holds.release(bottomSheet)
        assertFalse(bottomSheet.secure)
        assertTrue(activity.secure)
        holds.release(activity)
        assertFalse(activity.secure)
    }
}
