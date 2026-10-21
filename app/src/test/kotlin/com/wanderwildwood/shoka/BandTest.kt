package com.wanderwildwood.shoka

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class BandTest {
    // A 4 x 2 frame (row stride 5, one byte of padding a row):
    //   1 2 3 4
    //   5 6 7 8
    private val frame = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 0, 5, 6, 7, 8, 0))

    private fun cut(rotation: Int) = Band.cut(frame, 5, 4, 2, rotation, 1f, 1f)

    @Test fun upright() {
        val g = cut(0)
        assertEquals(4, g.width); assertEquals(2, g.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), g.pixels)
    }

    @Test fun quarterTurn() {
        // Turned clockwise: the bottom row becomes the left column.
        val g = cut(90)
        assertEquals(2, g.width); assertEquals(4, g.height)
        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4), g.pixels)
    }

    @Test fun halfTurn() = assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), cut(180).pixels)

    @Test fun threeQuarterTurn() = assertArrayEquals(byteArrayOf(4, 8, 3, 7, 2, 6, 1, 5), cut(270).pixels)

    @Test fun middleOnly() {
        val g = Band.cut(frame, 5, 4, 2, 0, 0.5f, 0.5f)
        assertEquals(2, g.width); assertEquals(1, g.height)
        assertArrayEquals(byteArrayOf(2, 3), g.pixels)
    }

    @Test fun doubled() {
        val g = Band.cut(frame, 5, 4, 2, 0, 1f, 1f, scale = 2)
        assertEquals(8, g.width); assertEquals(4, g.height)
        // Corners keep their values; between them the values blend.
        assertEquals(1, g.pixels[0].toInt())
        assertEquals(8, g.pixels[g.pixels.size - 1].toInt())
    }

    @Test fun shareSameShape() {
        // View, preview and frame all 16:9-shaped portrait: the box's share passes straight through.
        val (w, h) = Band.share(900, 1600, 0.8f, 0.1f, 720, 1280, 720, 1280)
        assertEquals(0.8f, w, 1e-4f); assertEquals(0.1f, h, 1e-4f)
    }

    @Test fun shareWideViewNarrowFrame() {
        // A wide strip of a 3:4 preview; the analysed frame is 9:16, so it sees the middle 3/4 of
        // the preview's width. The view shows the whole width and a quarter of the height.
        val (w, h) = Band.share(1200, 400, 0.5f, 0.25f, 1200, 1600, 720, 1280)
        assertEquals(0.5f / 0.75f, w, 1e-3f)
        assertEquals(0.25f * 0.25f, h, 1e-3f)
    }

    private fun paper(w: Int, h: Int, at: (Int, Int) -> Int) =
        Grey(ByteArray(w * h) { at(it % w, it / w).toByte() }, w, h)

    @Test fun inkIsInkAndGrainIsNot() {
        // Paper at 200 with a lone dark pixel (grain) and a 6 x 6 dark block (print).
        val g = paper(40, 40) { x, y ->
            when {
                x == 5 && y == 5 -> 60
                x in 20..25 && y in 20..25 -> 50
                else -> 200
            }
        }
        val ink = Band.ink(g)
        fun black(x: Int, y: Int) = ink.pixels[y * 40 + x].toInt() == 0
        assertTrue("grain kept", !black(5, 5))
        assertTrue("print lost", black(22, 22) && black(23, 23))
        assertTrue("paper marked", !black(35, 35))
    }

    @Test fun unevenLightIsNotInk() {
        // A page lit from the left: 230 fading to 90 across, nothing printed.
        val ink = Band.ink(paper(200, 30) { x, _ -> 230 - x * 140 / 199 })
        assertEquals(0, ink.pixels.count { it.toInt() == 0 })
    }
}
