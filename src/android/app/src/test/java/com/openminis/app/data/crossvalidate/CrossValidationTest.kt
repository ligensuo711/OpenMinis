package com.openminis.app.data.crossvalidate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossValidationTest {

    @Test
    fun identicalTextsAreSimilar() {
        assertEquals(1.0, CrossValidation.similarity("kotlin compose android", "android compose kotlin"), 0.001)
    }

    @Test
    fun disjointTextsAreDissimilar() {
        assertEquals(0.0, CrossValidation.similarity("kotlin android", "baking cookies recipes"), 0.001)
    }

    @Test
    fun emptyHandling() {
        assertEquals(1.0, CrossValidation.similarity("", ""), 0.001)
        assertEquals(0.0, CrossValidation.similarity("", "some text"), 0.001)
    }

    @Test
    fun divergencesFlagLowOverlapPairs() {
        val answers = listOf(
            "modelA" to "kotlin android jetpack",
            "modelB" to "android jetpack kotlin",
            "modelC" to "cooking baking recipes",
        )
        val divs = CrossValidation.divergences(answers)
        assertEquals(3, divs.size) // three pairs
        // A≈B (high overlap) → not diverged; A/C and B/C diverged.
        val ab = divs.first { it.modelA == "modelA" && it.modelB == "modelB" }
        assertTrue(!ab.diverged)
        val ac = divs.first { it.modelA == "modelA" && it.modelB == "modelC" }
        assertTrue(ac.diverged)
    }
}