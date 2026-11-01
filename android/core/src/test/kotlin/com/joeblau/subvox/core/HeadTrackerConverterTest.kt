package com.joeblau.subvox.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadTrackerConverterTest {
    private fun event(rv: Triple<Float, Float, Float>, w: Triple<Float, Float, Float> = Triple(0f, 0f, 0f), count: Int? = null) =
        buildList {
            add(rv.first); add(rv.second); add(rv.third)
            add(w.first); add(w.second); add(w.third)
            if (count != null) add(count.toFloat())
        }.toFloatArray()

    @Test fun eulerAxesMatchHeadFrame() {
        val c = HeadTrackerConverter()
        val nod = assertNotNull(c.convert(0, event(Triple(0.3f, 0f, 0f)))).sample
        assertEquals(0.3, nod.pitch, 1e-6); assertEquals(0.0, nod.roll, 1e-6); assertEquals(0.0, nod.yaw, 1e-6)
        val tilt = assertNotNull(c.convert(0, event(Triple(0f, 0.3f, 0f)))).sample
        assertEquals(0.3, tilt.roll, 1e-6); assertEquals(0.0, tilt.pitch, 1e-6)
        val turn = assertNotNull(c.convert(0, event(Triple(0f, 0f, 0.3f)))).sample
        assertEquals(0.3, turn.yaw, 1e-6); assertEquals(0.0, turn.pitch, 1e-6)
    }

    @Test fun copiesAngularVelocityAndTimestamp() {
        val s = assertNotNull(HeadTrackerConverter().convert(2_500_000_000, event(Triple(0f, 0f, 0f), Triple(0.1f, -0.2f, 0.3f)))).sample
        assertEquals(2.5, s.t, 1e-12)
        assertEquals(0.1, s.rx, 1e-6); assertEquals(-0.2, s.ry, 1e-6); assertEquals(0.3, s.rz, 1e-6)
        assertEquals(0.0, s.ax); assertEquals(0.0, s.ay); assertEquals(0.0, s.az)
    }

    @Test fun rejectsShortEvents() {
        assertNull(HeadTrackerConverter().convert(0, floatArrayOf(0f, 0f, 0f)))
    }

    @Test fun discontinuityKeepsAttitudeContinuousAndIsFlagged() {
        val c = HeadTrackerConverter()
        val before = assertNotNull(c.convert(0, event(Triple(0f, 0f, 0.5f), count = 0)))
        assertFalse(before.discontinuity)
        // Tracker re-centres: raw orientation snaps back to identity.
        val after = assertNotNull(c.convert(1, event(Triple(0f, 0f, 0f), count = 1)))
        assertTrue(after.discontinuity)
        assertEquals(before.sample.yaw, after.sample.yaw, 1e-6)
        // Subsequent motion is relative to the re-based frame.
        val next = assertNotNull(c.convert(2, event(Triple(0f, 0f, 0.1f), count = 1)))
        assertFalse(next.discontinuity)
        assertEquals(0.6, next.sample.yaw, 1e-6)
    }

    @Test fun firstEventAfterDiscontinuityFlagRebases() {
        val c = HeadTrackerConverter()
        val before = assertNotNull(c.convert(0, event(Triple(0.4f, 0f, 0f))))
        val after = assertNotNull(c.convert(1, event(Triple(0f, 0f, 0f)), firstAfterDiscontinuity = true))
        assertTrue(after.discontinuity)
        assertEquals(before.sample.pitch, after.sample.pitch, 1e-6)
    }

    @Test fun csvMatchesMacHeader() {
        assertEquals("t,ax,ay,az,rx,ry,rz,pitch,roll,yaw,gx,gy,gz,qw,qx,qy,qz", MotionSample.CSV_HEADER)
        val row = MotionSample(t = 1.5, rx = 0.25).csvRow(1.0)
        assertEquals(17, row.split(",").size)
        assertTrue(row.startsWith("0.500000,0.000000,0.000000,0.000000,0.250000"))
    }
}
