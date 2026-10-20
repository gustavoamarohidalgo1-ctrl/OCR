package com.facturaclara.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceChecksTest {
    @Test fun taxTotalOnThermalReceiptsIsAnActualTaxAmount() {
        assertTrue(InvoiceChecks.inspect("SUBTOTAL $ 20.00\nTAX TOTAL $ 2.00\nTOTAL $ 22.00").blockers.isEmpty())
        assertEquals(1, InvoiceChecks.inspect("SUBTOTAL $ 20.00\nTAX TOTAL $ 2.00\nTOTAL $ 23.00").blockers.size)
    }
    @Test fun spanishAndEnglishDecimalFormatsDoNotCreateRoundingErrors() {
        for (text in listOf(
            "Subtotal: 1.000,00\nIGV 18%: 180,00\nTOTAL: 1.180,00",
            "Subtotal: 1,000.00\nTax: 180.00\nGrand Total: 1,180.00",
            "Subtotal: 0,10\nIVA: 0,20\nTotal: 0,30"
        )) assertTrue(InvoiceChecks.inspect(text).blockers.isEmpty())
    }

    @Test fun mismatchAboveTwoCentsBlocksButToleranceDoesNot() {
        assertTrue(InvoiceChecks.inspect("Subtotal: 100.00\nIGV: 18.00\nTOTAL: 118.02").blockers.isEmpty())
        assertEquals(1, InvoiceChecks.inspect("Subtotal: 100.00\nIGV: 18.00\nTOTAL: 118.03").blockers.size)
    }

    @Test fun ambiguousThreeDigitSeparatorsWarnWithoutInventingAmount() {
        for (value in listOf("1,000", "1.000")) {
            val result = InvoiceChecks.inspect("Subtotal: $value\nIGV: 180.00\nTotal: 1180.00")
            assertTrue(result.blockers.isEmpty())
            assertFalse(result.warnings.isEmpty())
        }
    }

    @Test fun repeatedEquivalentTotalsAreFineButDifferentGlobalTotalsBlock() {
        assertTrue(InvoiceChecks.inspect("TOTAL: 118.00\nTotal a pagar: 118,00").blockers.isEmpty())
        assertEquals(1, InvoiceChecks.inspect("TOTAL: 118.00\nTotal a pagar: 119,00").blockers.size)
    }

    @Test fun subtotalTaxAndItemTotalsAreNotGlobalTotals() {
        val result = InvoiceChecks.inspect("Subtotal: 100.00\nTotal impuestos: 18.00\nTotal artículos: 2\nTotal de unidades: 2\nTOTAL: 118.00")
        assertTrue(result.blockers.isEmpty())
    }

    @Test fun adjustmentsDisableSimpleArithmetic() {
        for (adjustment in listOf("Descuento", "Retención", "Percepción", "Anticipo", "Detracción", "ISC", "ICBPER", "Flete", "IGV incluido")) {
            val result = InvoiceChecks.inspect("Subtotal: 100.00\nIGV: 18.00\n$adjustment: 10.00\nTOTAL: 108.00")
            assertTrue("No debe aplicar suma simple con $adjustment", result.blockers.isEmpty())
            assertFalse(result.warnings.isEmpty())
        }
    }

    @Test fun clearlySeparatedColumnsAreCheckedWithoutInventingAssociations() {
        assertEquals(1, InvoiceChecks.inspect("Subtotal: 100,00   IGV: 18,00   TOTAL: 119,00").blockers.size)
        assertTrue(InvoiceChecks.inspect("Subtotal 100,00 IGV 18,00 TOTAL 119,00").blockers.isEmpty())
        assertTrue(InvoiceChecks.inspect("Subtotal: 100,00  99,00\nIGV: 18,00\nTOTAL: 119,00").blockers.isEmpty())
    }

    @Test fun differentCurrenciesDoNotCreateFalseContradictions() {
        val result = InvoiceChecks.inspect("Total USD 100.00\nTotal PEN 375.00")
        assertTrue(result.blockers.isEmpty())
        assertFalse(result.warnings.isEmpty())
    }

    @Test fun missingEvidenceNeverClaimsSuccess() {
        val result = InvoiceChecks.inspect("Una factura sin etiquetas monetarias claras")
        assertTrue(result.blockers.isEmpty())
        // The skipped arithmetic check is visible instead of silent.
        assertEquals(1, result.warnings.count { it.contains("No se comprobó") })
    }

    @Test fun skippedArithmeticNamesTheMissingAmounts() {
        val onlyTotal = InvoiceChecks.inspect("IMPORTE TOTAL S/ 118.00")
        assertTrue(onlyTotal.blockers.isEmpty())
        assertTrue(onlyTotal.warnings.single { it.contains("No se comprobó") }.contains("subtotal ni impuesto"))
        val noTax = InvoiceChecks.inspect("Subtotal: 100.00\nTOTAL: 118.00")
        assertTrue(noTax.warnings.single { it.contains("No se comprobó") }.contains("impuesto"))
        // Two amounts after one label are not interpreted, and no longer silently.
        val twoAmounts = InvoiceChecks.inspect("Subtotal: 100,00  99,00\nIGV: 18,00\nTOTAL: 119,00")
        assertTrue(twoAmounts.blockers.isEmpty())
        assertTrue(twoAmounts.warnings.single { it.contains("No se comprobó") }.contains("subtotal"))
    }

    @Test fun completeArithmeticEvidenceAddsNoSkippedCheckWarning() {
        for (text in listOf("Subtotal: 100.00\nIGV: 18.00\nTOTAL: 118.00",
            "OP. GRAVADA S/ 100.00\nOP. EXONERADA S/ 0.00\nI.G.V. 18% S/ 18.00\nIMPORTE TOTAL S/ 118.00")) {
            val result = InvoiceChecks.inspect(text)
            assertTrue(text, result.blockers.isEmpty())
            assertTrue(text, result.warnings.isEmpty())
        }
    }

    @Test fun peruvianTaxableOperationsAreCheckedAsTaxableBase() {
        val receipt = "OP. GRAVADA S/ 100.00\nOP. EXONERADA S/ 0.00\nOP. INAFECTA S/ 0.00\nI.G.V. 18% S/ 18.00\nIMPORTE TOTAL S/ 119.00"
        assertEquals(1, InvoiceChecks.inspect(receipt).blockers.size)
        assertTrue(InvoiceChecks.inspect(receipt.replace("119.00", "118.00")).blockers.isEmpty())
        for (label in listOf("Op. Gravada:", "Op. Gravadas:", "OP.GRAVADA", "Operación gravada:", "OPERACIÓN GRAVADA",
            "Operaciones gravadas:", "Total Op. Gravadas:", "TOTAL GRAVADO:")) {
            assertEquals(label, 1, InvoiceChecks.inspect("$label 100.00\nIGV: 18.00\nImporte Total: 119.00").blockers.size)
            assertTrue(label, InvoiceChecks.inspect("$label 100.00\nIGV: 18.00\nImporte Total: 118.00").blockers.isEmpty())
        }
    }

    @Test fun taxableOperationsWithExemptOrUntaxedAmountsAreNotAFullSubtotal() {
        for (other in listOf("Op. Exonerada: 50.00", "Op. Inafecta: 50.00", "Op. No Gravada: 50.00")) {
            val result = InvoiceChecks.inspect("Op. Gravada: 100.00\n$other\nIGV: 18.00\nImporte Total: 168.00")
            assertTrue(other, result.blockers.isEmpty())
            assertTrue(other, result.warnings.any { it.contains("base imponible") })
        }
        val zero = "Op. Gravada: 100.00\nOp. Exonerada: 0.00\nOp. Inafecta: 0.00\nIGV: 18.00\nImporte Total: 119.00"
        assertEquals(1, InvoiceChecks.inspect(zero).blockers.size)
        val explicit = "Sub Total: 150.00\nOp. Gravada: 100.00\nOp. Exonerada: 50.00\nIGV: 18.00\nImporte Total: 169.00"
        assertEquals(1, InvoiceChecks.inspect(explicit).blockers.size)
    }

    @Test fun attachedSolesPrefixAndSentencePeriodStillYieldAmounts() {
        assertEquals(1, InvoiceChecks.inspect("TOTAL: S/.118.00\nTotal a pagar: S/ 119.00").blockers.size)
        assertEquals(1, InvoiceChecks.inspect("TOTAL: 118.00.\nTotal a pagar: 119.00").blockers.size)
        assertEquals(1, InvoiceChecks.inspect("Subtotal: S/.100.00\nIGV: S/.18.00\nTOTAL: S/.119.00").blockers.size)
        assertEquals(1, InvoiceChecks.inspect("Subtotal: 1.000,00.\nIGV 18%: 180,00.\nTOTAL: 1.181,00.").blockers.size)
        val valid = InvoiceChecks.inspect("Subtotal: s/.100.00\nIGV: S/.18.00\nTOTAL: S/.118.00.")
        assertTrue(valid.blockers.isEmpty())
        assertTrue(valid.warnings.isEmpty())
    }

    @Test fun leadingDecimalPointIsNeverDroppedToInventAnAmount() {
        // ".50" must not be read as 50; a dot is only skipped when it belongs to «S/.».
        assertTrue(InvoiceChecks.inspect("TOTAL: .50\nTotal a pagar: 0.50").blockers.isEmpty())
        assertTrue(InvoiceChecks.inspect("TOTAL: 0.50\nTotal a pagar: 0.50.").blockers.isEmpty())
    }

    @Test fun mixedExemptOperationsDoNotTurnTaxableBaseIntoFullSubtotal() {
        val result = InvoiceChecks.inspect("Base imponible: 100.00\nIGV: 18.00\nOp. Exoneradas: 50.00\nTOTAL: 168.00")
        assertTrue(result.blockers.isEmpty())
        assertTrue(result.warnings.any { it.contains("base imponible") })
        val unresolved = InvoiceChecks.inspect("Base imponible: 100.00\nIGV: 18.00\nOp. Inafectas:\nTOTAL: 168.00")
        assertTrue(unresolved.blockers.isEmpty())
        assertTrue(unresolved.warnings.any { it.contains("base imponible") })
    }

    @Test fun zeroNonTaxableCategoriesStillAllowDetectionOfWrongTotal() {
        val result = InvoiceChecks.inspect("Base imponible: 100.00\nIGV: 18.00\nOp. Exoneradas: 0.00\nTOTAL: 119.00")
        assertEquals(1, result.blockers.size)
    }

    @Test fun explicitFullSubtotalRemainsCheckableWithExemptOperations() {
        val valid = "Subtotal: 150.00\nBase imponible: 100.00\nIGV: 18.00\nOp. Exoneradas: 50.00\nTOTAL: 168.00"
        assertTrue(InvoiceChecks.inspect(valid).blockers.isEmpty())
        assertEquals(1, InvoiceChecks.inspect(valid.replace("TOTAL: 168.00", "TOTAL: 169.00")).blockers.size)
    }

    @Test fun advancePaymentCanReducePayableWithoutContradictingInvoiceTotal() {
        val result = InvoiceChecks.inspect("Importe Total: 118.00\nAnticipo: 18.00\nTotal a pagar: 100.00")
        assertTrue(result.blockers.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(InvoiceChecks.inspect("Importe Total: 118.00\nAnticipo: 18.00\nTOTAL   A PAGAR: 100.00").blockers.isEmpty())
    }

    @Test fun adjustmentsNeverHideContradictionsWithinOneTotalRole() {
        val conflictingInvoice = "Importe Total: 118.00\nTOTAL: 119.00\nAnticipo: 18.00\nTotal a pagar: 100.00"
        val conflictingPayable = "Importe Total: 118.00\nAnticipo: 18.00\nTotal a pagar: 100.00\nAmount due: 101.00"
        assertEquals(1, InvoiceChecks.inspect(conflictingInvoice).blockers.size)
        assertEquals(1, InvoiceChecks.inspect(conflictingPayable).blockers.size)
    }

    @Test fun otherCurrenciesNeverHideContradictionsInTheSameCurrency() {
        assertEquals(1, InvoiceChecks.inspect("Total USD 100.00\nTotal USD 101.00\nTotal PEN 375.00").blockers.size)
    }
}
