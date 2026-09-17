package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.FlightPhotoOrientation
import org.junit.Assert.*
import org.junit.Test

class FlightPhotoOrientationTest {
    @Test fun allEightExifOrientationsMapAnAsymmetricImageCorrectly() {
        // Pixel centres of a two-by-three image: AB / CD / EF.
        val expected = listOf("ABCDEF", "BADCFE", "FEDCBA", "EFCDAB", "ACEBDF", "ECAFDB", "FDBECA", "BDFACE")
        for (orientation in 1..8) {
            val matrix = FlightPhotoOrientation.matrix(orientation, 2, 3)
            val width = if (FlightPhotoOrientation.swapsAxes(orientation)) 3 else 2
            val result = CharArray(6)
            for (index in 0 until 6) {
                val x = index % 2 + 0.5f
                val y = index / 2 + 0.5f
                val outX = (matrix[0] * x + matrix[1] * y + matrix[2]).toInt()
                val outY = (matrix[3] * x + matrix[4] * y + matrix[5]).toInt()
                result[outY * width + outX] = ('A'.code + index).toChar()
            }
            assertEquals("EXIF $orientation", expected[orientation - 1], String(result))
        }
    }

    @Test fun UndefinedOrientationDoesNotRotateOrMirror() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), FlightPhotoOrientation.matrix(0, 2, 3), 0f)
        assertFalse(FlightPhotoOrientation.swapsAxes(0))
    }
}
