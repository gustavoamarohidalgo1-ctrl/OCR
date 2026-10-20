package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Test

class EnlargementTest {
    @Test fun smallPhotosAimForAboutAThousandPixelsOnTheShortSide() {
        assertEquals(3, OfflineOcr.enlargement(299, 400))
        assertEquals(3, OfflineOcr.enlargement(348, 348))
        assertEquals(2, OfflineOcr.enlargement(645, 1000))
        assertEquals(2, OfflineOcr.enlargement(500, 700))
    }

    @Test fun largeOrHugePhotosAreReadAtNativeSize() {
        assertEquals(1, OfflineOcr.enlargement(750, 1000))
        assertEquals(1, OfflineOcr.enlargement(1290, 2252))
        // A long, narrow photo would make a copy beyond the memory bound.
        assertEquals(1, OfflineOcr.enlargement(699, 12000))
        assertEquals(1, OfflineOcr.enlargement(0, 100))
    }

    @Test fun stepsDownToTwiceWhenThreeTimesDoesNotFit() {
        assertEquals(3, OfflineOcr.enlargement(400, 2222))
        assertEquals(2, OfflineOcr.enlargement(400, 2223))
        assertEquals(1, OfflineOcr.enlargement(400, 5100))
    }
}
