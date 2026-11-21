package com.anisync.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySizeTest {
    @Test
    fun `legacy saved names still load`() {
        assertEquals(0.9f, DisplaySize.valueOf("SMALL").scale)
        assertEquals(1f, DisplaySize.valueOf("DEFAULT").scale)
        assertEquals(1.1f, DisplaySize.valueOf("LARGE").scale)
        assertEquals(1.2f, DisplaySize.valueOf("LARGER").scale)
    }

    @Test
    fun `percentage values load and stay in supported range`() {
        assertEquals(0.1f, DisplaySize.valueOf("10").scale)
        assertEquals(2f, DisplaySize.valueOf("200").scale)
        assertTrue(DisplaySize(1.5f).scale in DisplaySize.MIN_SCALE..DisplaySize.MAX_SCALE)
    }
}
