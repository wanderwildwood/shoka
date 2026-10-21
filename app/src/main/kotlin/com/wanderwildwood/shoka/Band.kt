package com.wanderwildwood.shoka

import java.nio.ByteBuffer

/** A small greyscale picture: one byte a pixel, row after row. */
class Grey(val pixels: ByteArray, val width: Int, val height: Int)

/**
 * The strip across the middle of a camera frame where the ISBN line is held, cut out of the
 * frame's brightness plane (which is already greyscale), turned upright and, when it is small,
 * doubled in size for the OCR.
 */
object Band {

    /**
     * [fracW] and [fracH] of the frame's upright width and height, centred. [rotation] is how
     * far the frame has to turn clockwise to stand upright (CameraX's rotationDegrees).
     */
    fun cut(
        plane: ByteBuffer, rowStride: Int, width: Int, height: Int, rotation: Int,
        fracW: Float, fracH: Float, scale: Int = 1,
    ): Grey {
        val sideways = rotation == 90 || rotation == 270
        val uw = if (sideways) height else width
        val uh = if (sideways) width else height
        val cw = (uw * fracW).toInt().coerceIn(1, uw)
        val ch = (uh * fracH).toInt().coerceIn(1, uh)
        val x0 = (uw - cw) / 2
        val y0 = (uh - ch) / 2

        // The frame's byte for an upright pixel.
        fun at(ux: Int, uy: Int): Int {
            val (sx, sy) = when (rotation) {
                90 -> uy to height - 1 - ux
                180 -> width - 1 - ux to height - 1 - uy
                270 -> width - 1 - uy to ux
                else -> ux to uy
            }
            return plane.get(sy * rowStride + sx).toInt() and 0xFF
        }

        val ow = cw * scale
        val oh = ch * scale
        val out = ByteArray(ow * oh)
        if (scale == 1) {
            for (y in 0 until oh) for (x in 0 until ow) out[y * ow + x] = at(x0 + x, y0 + y).toByte()
        } else {
            // Bilinear, so doubled strokes stay smooth rather than stepped.
            for (y in 0 until oh) {
                val fy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, (ch - 1).toFloat())
                val iy = fy.toInt(); val dy = fy - iy; val iy2 = minOf(iy + 1, ch - 1)
                for (x in 0 until ow) {
                    val fx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, (cw - 1).toFloat())
                    val ix = fx.toInt(); val dx = fx - ix; val ix2 = minOf(ix + 1, cw - 1)
                    val top = at(x0 + ix, y0 + iy) * (1 - dx) + at(x0 + ix2, y0 + iy) * dx
                    val bottom = at(x0 + ix, y0 + iy2) * (1 - dx) + at(x0 + ix2, y0 + iy2) * dx
                    out[y * ow + x] = (top * (1 - dy) + bottom * dy + 0.5f).toInt().toByte()
                }
            }
        }
        return Grey(out, ow, oh)
    }

    /**
     * Black ink on white, for Tesseract: a light blur first, so the camera's grain is not
     * taken for print, then each pixel set against the mean of the [radius] around it, so
     * uneven light (a torch, a shadow across the page) does not swallow a side of the line.
     * A pixel is ink when it is [margin] darker than its surroundings, and a speck of one or
     * two pixels on its own is grain, not ink.
     */
    fun ink(g: Grey, radius: Int = 20, margin: Int = 16): Grey {
        val w = g.width
        val h = g.height
        val src = g.pixels
        // 3 x 3 box blur.
        val soft = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -1..1) sum += src[yy * w + (x + dx).coerceIn(0, w - 1)].toInt() and 0xFF
            }
            soft[y * w + x] = sum
        }
        // Integral image of the blurred picture (values nine times the brightness).
        val sums = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += soft[y * w + x]
                sums[(y + 1) * (w + 1) + x + 1] = sums[y * (w + 1) + x + 1] + row
            }
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - radius); val y1 = minOf(h, y + radius + 1)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - radius); val x1 = minOf(w, x + radius + 1)
                val total = sums[y1 * (w + 1) + x1] - sums[y0 * (w + 1) + x1] - sums[y1 * (w + 1) + x0] + sums[y0 * (w + 1) + x0]
                val mean = total.toFloat() / ((y1 - y0) * (x1 - x0))
                out[y * w + x] = if (soft[y * w + x] < mean - margin * 9) 0 else 255.toByte()
            }
        }
        val clean = out.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            if (out[y * w + x].toInt() != 0) continue
            var near = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy; val xx = x + dx
                if ((dy != 0 || dx != 0) && yy in 0 until h && xx in 0 until w && out[yy * w + xx].toInt() == 0) near++
            }
            if (near <= 2) clean[y * w + x] = 255.toByte()
        }
        return Grey(clean, w, h)
    }

    /**
     * How much of the analysis frame the guide box covers, as fractions of its upright width
     * and height. The preview fills its view and is cropped to it, and the preview and the
     * analysis frame may differ in shape: both are taken to span the sensor's full height, so
     * a narrower analysis frame sees only the middle of what the preview shows.
     *
     * [viewW]/[viewH] are the preview view's size, [boxW] the box's share of its width and
     * [boxH] of its height; [previewW]/[previewH] and [frameW]/[frameH] are upright sizes.
     */
    fun share(
        viewW: Int, viewH: Int, boxW: Float, boxH: Float,
        previewW: Int, previewH: Int, frameW: Int, frameH: Int,
    ): Pair<Float, Float> {
        if (viewW <= 0 || viewH <= 0 || previewW <= 0 || previewH <= 0) return 1f to 1f
        val fill = maxOf(viewW.toFloat() / previewW, viewH.toFloat() / previewH)
        val shownW = viewW / fill / previewW   // share of the preview's width the view shows
        val shownH = viewH / fill / previewH
        val widthRatio = (previewW.toFloat() / previewH) / (frameW.toFloat() / frameH)
        val w = boxW * shownW * widthRatio
        val h = boxH * shownH
        return w.coerceIn(0.05f, 1f) to h.coerceIn(0.05f, 1f)
    }
}
