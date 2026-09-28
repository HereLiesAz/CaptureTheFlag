package com.hereliesaz.capturetheflag.node

import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The photo matcher: how much a capture or jailbreak photo looks like the reference the leader
 * registered, from 0 (nothing alike) to 1 (the same picture). Coarse on purpose: two shots of the
 * same statue from the same spot, minutes or days apart, in different light, should still agree.
 *
 * Half of it is shape (a 64-bit difference hash of the grayscale image, which ignores exposure),
 * half is colour (how much two 64-bin chromaticity histograms overlap). Pure arithmetic on the decoded
 * pixels: every node with the same two files gets the same score.
 */
object Matcher {
    /** Null when either file isn't an image this JVM can decode. */
    fun score(a: ByteArray, b: ByteArray): Double? {
        val x = decode(a) ?: return null
        val y = decode(b) ?: return null
        val shape = 1.0 - java.lang.Long.bitCount(dHash(x) xor dHash(y)) / 64.0
        val colour = histogram(x).zip(histogram(y)).sumOf { (p, q) -> minOf(p, q) }
        return (shape + colour) / 2
    }

    private fun decode(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()

    /** Scales [img] to [w]×[h] by averaging each cell, so the result doesn't depend on a resampler. */
    private fun cells(img: BufferedImage, w: Int, h: Int): Array<IntArray> = Array(h) { cy ->
        IntArray(w) { cx ->
            val x0 = cx * img.width / w; val x1 = maxOf(x0 + 1, (cx + 1) * img.width / w)
            val y0 = cy * img.height / h; val y1 = maxOf(y0 + 1, (cy + 1) * img.height / h)
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = img.getRGB(x, y); r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff; n++
            }
            (((r / n) shl 16) or ((g / n) shl 8) or (b / n)).toInt()
        }
    }

    private fun gray(p: Int) = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000

    /** Each bit: is this cell brighter than the one to its right? */
    private fun dHash(img: BufferedImage): Long {
        val c = cells(img, 9, 8)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) h = (h shl 1) or (if (gray(c[y][x]) > gray(c[y][x + 1])) 1L else 0L)
        return h
    }

    /**
     * Chromaticity, not raw colour: each cell's share of red and of green in its total, 8 bins
     * each, over a 32×32 reduction, normalised to sum to 1. The same wall at noon and at dusk
     * lands in the same bins.
     */
    private fun histogram(img: BufferedImage): DoubleArray {
        val bins = DoubleArray(64)
        val c = cells(img, 32, 32)
        for (row in c) for (p in row) {
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            val sum = maxOf(1, r + g + b)
            bins[minOf(7, r * 8 / sum) * 8 + minOf(7, g * 8 / sum)] += 1.0
        }
        return bins.map { it / (32 * 32) }.toDoubleArray()
    }
}
