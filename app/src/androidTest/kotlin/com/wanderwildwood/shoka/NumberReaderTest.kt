package com.wanderwildwood.shoka

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * Tesseract itself, on pictures of ISBN lines made to look photographed (small print, blur,
 * noise, a tilt, uneven light). Each picture is set into a camera-sized frame turned the way a
 * phone's sensor delivers it, and goes through the same cutting, turning and doubling as a
 * camera frame does. The file name says what should be read: an ISBN-13, or "none". A picture
 * marked "hard" may be missed (the next frame is another try), but never misread.
 */
@RunWith(AndroidJUnit4::class)
class NumberReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val reader = NumberReader(instrumentation.targetContext)

    @After fun close() = reader.close()

    @Test fun readsTheRightNumberAndNothingElse() {
        val assets = instrumentation.context.assets
        val names = assets.list("ocr")!!.filter { it.endsWith(".png") }.sorted()
        assertTrue(names.size >= 5)
        val report = StringBuilder()
        val wrong = mutableListOf<String>()
        for (name in names) {
            val bitmap = assets.open("ocr/$name").use { BitmapFactory.decodeStream(it) }
            val w = bitmap.width
            val h = bitmap.height
            val strip = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) strip[y * w + x] = Color.red(bitmap.getPixel(x, y)).toByte()

            // An upright 720 x 1280 frame of paper with the strip across its middle, stored
            // turned a quarter, as a sensor mounted sideways delivers it (rotationDegrees 90).
            val uw = w
            val uh = 1280
            val top = (uh - h) / 2
            val upright = ByteArray(uw * uh) { strip[0] }
            System.arraycopy(strip, 0, upright, top * uw, strip.size)
            val sensorW = uh
            val sensorH = uw
            val sensor = ByteArray(sensorW * sensorH)
            for (sy in 0 until sensorH) for (sx in 0 until sensorW) {
                sensor[sy * sensorW + sx] = upright[sx * uw + (sensorH - 1 - sy)]
            }
            val t0 = System.nanoTime()
            val band = Band.cut(ByteBuffer.wrap(sensor), sensorW, sensorW, sensorH, 90, 1f, h.toFloat() / uh, scale = 2)
            val text = reader.text(band)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val got = PrintedIsbn.find(text)
            val want = name.substringBefore("__").takeUnless { it == "none" }
            val ok = got == want || (got == null && "__hard_" in name)
            if (!ok) wrong += name
            report.append("${if (got == want) "ok  " else if (ok) "miss" else "FAIL"} $name  read=${text.replace("\n", " | ").trim()}  isbn=$got  ${ms}ms\n")
        }
        instrumentation.targetContext.getExternalFilesDir(null)?.let { File(it, "ocr-results.txt").writeText(report.toString()) }
        println(report)
        assertTrue("wrong: $wrong\n$report", wrong.isEmpty())
    }
}
