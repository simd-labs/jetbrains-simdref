package dev.simdlabs.simdref

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionTest {
    @Test
    fun supportsAsmAndCExtensions() {
        for (e in SUPPORTED_EXTENSIONS) assertTrue(e, isSupportedExtension(e))
    }

    @Test
    fun rejectsOthers() {
        assertFalse(isSupportedExtension("py"))
        assertFalse(isSupportedExtension(null))
    }
}
