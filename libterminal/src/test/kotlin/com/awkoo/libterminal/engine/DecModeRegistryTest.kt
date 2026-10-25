package com.awkoo.libterminal.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecModeRegistryTest {

    private val registry = DecModeRegistry()

    @Test
    fun setAndClear_singleBit() {
        registry.set(DECSET_BIT_AUTOWRAP, true)
        assertTrue(registry.isSet(DECSET_BIT_AUTOWRAP))

        registry.set(DECSET_BIT_AUTOWRAP, false)
        assertFalse(registry.isSet(DECSET_BIT_AUTOWRAP))
    }

    @Test
    fun mouseTrackingModes_areMutuallyExclusive() {
        registry.set(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, true)
        registry.set(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, true)

        assertFalse(registry.isSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE))
        assertTrue(registry.isSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT))

        registry.set(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT, true)

        assertFalse(registry.isSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT))
        assertTrue(registry.isSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT))
    }

    @Test
    fun clearingOneMouseMode_keepsOthersUntouched() {
        registry.set(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT, true)

        registry.set(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT, false)

        assertFalse(registry.isSet(DECSET_BIT_MOUSE_TRACKING_ANY_EVENT))
    }

    @Test
    fun mapExternalToInternal_knownModes() {
        assertEquals(DECSET_BIT_APPLICATION_CURSOR_KEYS, registry.mapExternalToInternal(1))
        assertEquals(DECSET_BIT_REVERSE_VIDEO, registry.mapExternalToInternal(5))
        assertEquals(DECSET_BIT_ORIGIN_MODE, registry.mapExternalToInternal(6))
        assertEquals(DECSET_BIT_AUTOWRAP, registry.mapExternalToInternal(7))
        assertEquals(DECSET_BIT_CURSOR_ENABLED, registry.mapExternalToInternal(25))
        assertEquals(DECSET_BIT_APPLICATION_KEYPAD, registry.mapExternalToInternal(66))
        assertEquals(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, registry.mapExternalToInternal(1000))
        assertEquals(DECSET_BIT_MOUSE_PROTOCOL_SGR, registry.mapExternalToInternal(1006))
        assertEquals(DECSET_BIT_BRACKETED_PASTE_MODE, registry.mapExternalToInternal(2004))
    }

    @Test
    fun mapExternalToInternal_unknownMode_isMinusOne() {
        assertEquals(-1, registry.mapExternalToInternal(999))
        assertEquals(-1, registry.mapExternalToInternal(0))
    }

    @Test
    fun saveAndRestoreModeBit_roundTrip() {
        registry.set(DECSET_BIT_AUTOWRAP, true)
        registry.saveModeBit(DECSET_BIT_AUTOWRAP)
        registry.set(DECSET_BIT_AUTOWRAP, false)

        assertTrue(registry.restoreModeBit(DECSET_BIT_AUTOWRAP))
    }

    @Test
    fun restoreModeBit_neverSaved_readsAsOff() {
        assertFalse(registry.restoreModeBit(DECSET_BIT_AUTOWRAP))
    }

    @Test
    fun resetDefault_enablesAutowrapAndCursorOnly() {
        registry.set(DECSET_BIT_ORIGIN_MODE, true)

        registry.resetDefault()

        assertTrue(registry.isSet(DECSET_BIT_AUTOWRAP))
        assertTrue(registry.isSet(DECSET_BIT_CURSOR_ENABLED))
        assertFalse(registry.isSet(DECSET_BIT_ORIGIN_MODE))
    }

    @Test
    fun restoreAutowrapAndOrigin_touchesOnlyThoseTwoBits() {
        registry.set(DECSET_BIT_REVERSE_VIDEO, true)
        val saved = DECSET_BIT_AUTOWRAP // 快照：autowrap 开、origin 关
        registry.set(DECSET_BIT_AUTOWRAP, false)
        registry.set(DECSET_BIT_ORIGIN_MODE, true)

        registry.restoreAutowrapAndOrigin(saved)

        assertTrue(registry.isSet(DECSET_BIT_AUTOWRAP))
        assertFalse(registry.isSet(DECSET_BIT_ORIGIN_MODE))
        assertTrue(registry.isSet(DECSET_BIT_REVERSE_VIDEO))
    }

    @Test
    fun current_exposesFullSnapshot() {
        registry.set(DECSET_BIT_SEND_FOCUS_EVENTS, true)

        assertEquals(DECSET_BIT_SEND_FOCUS_EVENTS, registry.current and DECSET_BIT_SEND_FOCUS_EVENTS)
    }
}
