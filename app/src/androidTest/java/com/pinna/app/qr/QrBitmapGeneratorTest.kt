package com.pinna.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrBitmapGeneratorTest {
    @Test
    fun generateReturnsSquareNonEmptyBitmap() {
        val bitmap = QrBitmapGenerator.generate("pinna://join?room=room-1", sizePx = 256)

        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.distinct().size > 1)
    }
}
