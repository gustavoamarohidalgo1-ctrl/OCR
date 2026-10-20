package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DocumentLabelsTest {
    private val lima = TimeZone.getTimeZone("America/Lima")
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        Calendar.getInstance(lima).apply { clear(); set(year, month - 1, day, hour, minute) }.timeInMillis

    @Test fun technicalNameLosesExtensionSeparatorsAndIds() {
        assertEquals("QA perf final 22 espana mercadona ticket largo",
            DocumentLabels.readableName("QA-perf-final-1792371728848-22_espana_mercadona_ticket_largo.jpg"))
    }

    @Test fun descriptiveNameKeepsShortNumbersAndCase() {
        assertEquals("Factura luz enero 2026", DocumentLabels.readableName("factura luz enero 2026.pdf"))
        assertEquals("A4 Proforma 3", DocumentLabels.readableName("A4-Proforma_3.PNG"))
    }

    @Test fun electronicInvoiceKeepsSeriesAndNumber() {
        assertEquals("01 F001 00000123", DocumentLabels.readableName("20601234567-01-F001-00000123.pdf"))
        assertEquals("Factura F001 00012345", DocumentLabels.readableName("Factura F001-00012345.pdf"))
    }

    @Test fun cameraAndMessagingNamesHaveNoReadableName() {
        assertNull(DocumentLabels.readableName("IMG_20261018_201200.jpg"))
        assertNull(DocumentLabels.readableName("PXL_20261018_201200123.jpg"))
        assertNull(DocumentLabels.readableName("IMG-20261018-WA0003.jpeg"))
        assertNull(DocumentLabels.readableName("IMG_20261018_201200 (1).jpg"))
        assertNull(DocumentLabels.readableName("WhatsApp Image 2026-10-18 at 20.12.00.jpeg"))
        assertNull(DocumentLabels.readableName("1792371728848.jpg"))
        assertNull(DocumentLabels.readableName(""))
    }

    @Test fun fallbackTitleUsesTheDateTheDocumentWasAdded() {
        assertEquals("Foto · 18 oct, 20:12", DocumentLabels.fallbackTitle(false, at(2026, 10, 18, 20, 12), true, lima))
        assertEquals("PDF · 5 ene, 9:05 a. m.", DocumentLabels.fallbackTitle(true, at(2026, 1, 5, 9, 5), false, lima))
    }

    @Test fun shortDateIsRelativeForRecentDays() {
        val now = at(2026, 10, 19, 9, 42)
        assertEquals("Hoy, 08:03", DocumentLabels.shortDate(at(2026, 10, 19, 8, 3), now, true, lima))
        assertEquals("Ayer, 8:12 p. m.", DocumentLabels.shortDate(at(2026, 10, 18, 20, 12), now, false, lima))
        assertEquals("Ayer, 12:00 a. m.", DocumentLabels.shortDate(at(2026, 10, 18, 0, 0), now, false, lima))
        assertEquals("2 oct, 12:30", DocumentLabels.shortDate(at(2026, 10, 2, 12, 30), now, true, lima))
        assertEquals("31 dic 2025", DocumentLabels.shortDate(at(2025, 12, 31, 23, 59), now, true, lima))
    }

    @Test fun yesterdayCrossesTheYearBoundary() {
        assertEquals("Ayer, 23:59", DocumentLabels.shortDate(at(2025, 12, 31, 23, 59), at(2026, 1, 1, 0, 5), true, lima))
    }
}
