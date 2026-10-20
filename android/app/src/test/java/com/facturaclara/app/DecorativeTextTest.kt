package com.facturaclara.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecorativeTextTest {
    @Test fun asteriskRulesReadAsGarbageAreDecoration() {
        assertTrue(DecorativeText.isDecoration("XX***XXXXXXXXXXXXXXXX**x****XX*XXX"))
        assertTrue(DecorativeText.isDecoration("XX**X*******K****"))
        assertTrue(DecorativeText.isDecoration("k*************"))
    }

    @Test fun receiptContentIsKept() {
        assertFalse(DecorativeText.isDecoration("************1234"))
        assertFalse(DecorativeText.isDecoration("XXXXXXXXXXXX"))
        assertFalse(DecorativeText.isDecoration("**TOTAL**"))
        assertFalse(DecorativeText.isDecoration("XXL"))
        assertFalse(DecorativeText.isDecoration("2 X 1.50 *"))
    }
}
