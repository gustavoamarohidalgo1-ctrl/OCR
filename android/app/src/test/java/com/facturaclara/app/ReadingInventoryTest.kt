package com.facturaclara.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingInventoryTest {
    @Test fun acceptsSpatialReorderingWithoutLosingOccurrences() {
        assertTrue(ReadingInventory.matches("1 Tea 2.00\n1 Tea 2.00", listOf("1 Tea", "1 Tea", "2.00", "2.00"),
            listOf("1", "Tea", "2.00", "1", "Tea", "2.00")))
    }
    @Test fun missingWordGeometryCannotEraseObservedQuantity() {
        assertFalse(ReadingInventory.matches("1 Tea 2.00", listOf("Tea 2.00"), listOf("Tea", "2.00")))
    }
    @Test fun missingRepeatedRowIsNotSetEquality() {
        assertFalse(ReadingInventory.matches("Tea\nTea", listOf("Tea"), listOf("Tea")))
    }
    @Test fun numericGroupingCannotBeChangedByJoiningWords() {
        assertFalse(ReadingInventory.matches("2/60.00", listOf("2/60.00"), listOf("2", "/", "60.00")))
    }
}
