package dev.simdlabs.simdref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    @Test
    fun notificationTextIsExact() {
        val message = NOT_FOUND_MESSAGE
        println("C4-NOTIFICATION: $message")
        assertEquals(
            "simdref not found. Install it: uv tool install simdref (or pip install simdref), then run isa update. Instructions: https://github.com/simd-labs/simdref",
            message
        )
        assertTrue(message.contains(DOCS_URL))
    }
}
