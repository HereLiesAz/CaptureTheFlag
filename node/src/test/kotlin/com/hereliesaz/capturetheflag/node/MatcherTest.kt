package com.hereliesaz.capturetheflag.node

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatcherTest {
    /** A "statue": a dark block on a sky, placed at [dx] and lit by [light]. */
    private fun shot(dx: Int, light: Int, statue: Color = Color(60, 50, 40)): ByteArray {
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(minOf(255, 120 + light), minOf(255, 170 + light), 230); g.fillRect(0, 0, 320, 240)
        g.color = statue; g.fillRect(120 + dx, 60, 80, 180)
        g.color = Color(40, 120, 40); g.fillRect(0, 200, 320, 40)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun noise(seed: Int): ByteArray {
        val r = java.util.Random(seed.toLong())
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) for (x in 0 until 320) img.setRGB(x, y, r.nextInt(0xffffff))
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test fun theSameThingScoresHighAndSomethingElseLow() {
        val reference = shot(0, 0)
        val later = Matcher.score(reference, shot(6, 15))!!
        val elsewhere = Matcher.score(reference, noise(1))!!
        assertTrue(later > 0.8, "same statue, a step over, brighter: $later")
        assertTrue(elsewhere < com.hereliesaz.capturetheflag.rules.GameRules.VISUAL_MATCH_MIN, "static: $elsewhere")
        assertTrue(Matcher.score(reference, reference) == 1.0)
    }

    @Test fun notAnImageIsNoScore() = assertNull(Matcher.score(shot(0, 0), byteArrayOf(1, 2, 3)))
}
