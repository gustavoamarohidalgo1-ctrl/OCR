package com.facturaclara.app

import org.junit.Assert.*
import org.junit.Test

class BoundaryConsensusTest {
    private fun row(text: String, confidence: Float = .9f) = listOf(OcrLine(text, .1f, .2f, .9f, .25f, confidence = confidence))
    private fun word(text: String, left: Float = .12f, right: Float = .4f, confidence: Float = .95f,
        top: Float = .205f, bottom: Float = .245f) = OcrLine(text, left, top, right, bottom, confidence = confidence)

    @Test fun joinsOnlyObservedWordBoundariesWithIndependentSpatialSupport() {
        val whole = listOf(row("Rooseve lt Field"), row("Rooseve lt Field"), row("Rooseve lt Field"))
        val exact = row("Roosevelt Field")
        assertTrue(BoundaryConsensus.canAttempt(whole, listOf(exact, exact)))
        assertEquals(exact, BoundaryConsensus.recovered(whole, listOf(exact, exact),
            listOf(listOf(word("Roosevelt"), word("Field", .5f, .7f)))))
    }

    @Test fun requiresTwoReliableRegionalObservationsButNoWholeWithCorrectSpaces() {
        val whole = listOf(row("Repr int #:1"), row("Repr int #:1"))
        val exact = row("Reprint #:1")
        val evidence = listOf(listOf(word("Reprint")))
        assertNull(BoundaryConsensus.recovered(whole, listOf(exact), evidence))
        assertNull(BoundaryConsensus.recovered(whole, listOf(exact, row("Reprint #:1", .64f)), evidence))
        assertEquals(exact, BoundaryConsensus.recovered(whole, listOf(exact, exact), evidence))
    }

    @Test fun unrelatedUnchangedSymbolFromIndependentEngineCannotAlterMlText() {
        val whole = listOf(row("Repr int #:1"), row("Repr int #:1"))
        val exact = row("Reprint #:1")
        val independent = listOf(word("Reprint"), word("4", .6f, .65f), word(":1", .7f, .75f))
        assertEquals(exact, BoundaryConsensus.recovered(whole, listOf(exact, exact), listOf(independent)))
    }

    @Test fun everyWholeObservationMustKeepIdenticalLettersCaseAndSymbols() {
        val exact = row("Roosevelt Field")
        for (contradiction in listOf("Rooseve1t Field", "roosevelt Field", "Roosevelt Field!", "Roosevelt")) {
            val whole = listOf(row("Rooseve lt Field"), row(contradiction))
            assertFalse(contradiction, BoundaryConsensus.canAttempt(whole, listOf(exact, exact)))
        }
    }

    @Test fun numericTokensCannotMergeChangeOrMoveEvenWhenCharactersMatch() {
        val evidence = listOf(listOf(word("Reprint")))
        for ((observed, candidate) in listOf("Repr int 12 34" to "Reprint 1234",
            "Repr int 14. 00" to "Reprint 14.00", "Repr int AB12" to "Reprint AB 12")) {
            val exact = row(candidate)
            assertNull(BoundaryConsensus.recovered(listOf(row(observed), row(observed)), listOf(exact, exact), evidence))
        }
    }

    @Test fun nfcComparisonDoesNotRewriteTheSelectedRegionalObservation() {
        val exact = row("Cafe\u0301 Central")
        assertEquals(exact, BoundaryConsensus.recovered(listOf(row("Ca fé Central"), row("Ca fé Central")),
            listOf(exact, exact), listOf(listOf(word("Café")))))
    }

    @Test fun independentConfidenceAndSourceLocationAreRequired() {
        val whole = listOf(row("Repr int #:1"), row("Repr int #:1"))
        val regions = listOf(row("Reprint #:1"), row("Reprint #:1"))
        assertNull(BoundaryConsensus.recovered(whole, regions, emptyList()))
        assertNull(BoundaryConsensus.recovered(whole, regions, listOf(listOf(word("Reprint", confidence = .849f)))))
        assertNull(BoundaryConsensus.recovered(whole, regions, listOf(listOf(word("Reprint", top = .7f, bottom = .75f)))))
        assertNull(BoundaryConsensus.recovered(whole, regions, listOf(listOf(word("Reprint"), word("Reprint", .5f, .7f)))))
    }

    @Test fun anotherConfidentIndependentPassCannotContradictSupportedWord() {
        val whole = listOf(row("Repr int #:1"), row("Repr int #:1"))
        val regions = listOf(row("Reprint #:1"), row("Reprint #:1"))
        assertNull(BoundaryConsensus.recovered(whole, regions,
            listOf(listOf(word("Reprint")), listOf(word("Repr1nt")))))
        assertNull(BoundaryConsensus.recovered(whole, regions,
            listOf(listOf(word("Reprint")), listOf(word("Repr", .12f, .25f), word("int", .27f, .4f)))))
    }

    @Test fun highConfidenceRegionalLetterConflictIsNotDiscardedAsSpacing() {
        val whole = listOf(row("Repr int #:1"), row("Repr int #:1"))
        val regions = listOf(row("Reprint #:1"), row("Reprint #:1"), row("Repr1nt #:1"))
        assertFalse(BoundaryConsensus.canAttempt(whole, regions))
    }

    @Test fun supportsAnObservedSplitButDoesNotReuseOneIndependentWord() {
        val exact = row("DE LA")
        assertEquals(exact, BoundaryConsensus.recovered(listOf(row("DELA"), row("DELA")), listOf(exact, exact),
            listOf(listOf(word("DE", .12f, .3f), word("LA", .4f, .6f)))))
        val repeated = row("la la")
        assertNull(BoundaryConsensus.recovered(listOf(row("lala"), row("lala")), listOf(repeated, repeated),
            listOf(listOf(word("la")))))
    }

    @Test fun stableMlRowsAreFlaggedWhenIndependentWordBoundariesDisagree() {
        assertTrue(BoundaryConsensus.hasBoundaryDisagreement(row("Cadillac Margar ita (2 @14. 00) 28.00"),
            listOf(listOf(word("Margarita", .3f, .6f)))))
        assertTrue(BoundaryConsensus.hasBoundaryDisagreement(row("DELA"),
            listOf(listOf(word("DE", .12f, .3f), word("LA", .4f, .6f)))))
        assertFalse(BoundaryConsensus.hasBoundaryDisagreement(row("Total 14. 00"),
            listOf(listOf(word("14.00", .3f, .6f)))))
        assertFalse(BoundaryConsensus.hasBoundaryDisagreement(row("Margar ita"),
            listOf(listOf(word("Margarita", .3f, .6f, confidence = .84f)))))
        assertFalse(BoundaryConsensus.hasBoundaryDisagreement(row("Margar ita"),
            listOf(listOf(word("Margarita", .3f, .6f, top = .7f, bottom = .75f)))))
    }
}
