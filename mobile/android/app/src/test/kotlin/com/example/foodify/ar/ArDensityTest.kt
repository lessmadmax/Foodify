package com.example.foodify.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArDensityTest {
    @Test fun exactNamesUseTableValues() {
        assertEquals(0.69, ArDensity.lookup("커피땅콩")!!.density, 1e-9)
        assertEquals(0.73, ArDensity.lookup("밥")!!.density, 1e-9)
        assertEquals(0.47, ArDensity.lookup("초코칩 쿠키")!!.density, 1e-9)
    }

    @Test fun suffixMatchesKnownFood() {
        assertEquals("Rice, boiled", ArDensity.lookup("현미밥")!!.sourceFood)
        assertEquals("Peanuts, shelled", ArDensity.lookup(" 볶은  땅콩 ")!!.sourceFood)
    }

    @Test fun unknownFoodHasNoDensity() {
        assertNull(ArDensity.lookup("김치찌개"))
        assertNull(ArDensity.lookup(""))
        val fields = ArDensity.fields("김치찌개", 100.0, 100.0)
        assertNull(fields["tableEstimatedGrams"])
        assertNull(fields["tableDensityGPerMl"])
    }

    @Test fun gramsUseVolumeTimesDensity() {
        val fields = ArDensity.fields("커피땅콩", 20.0, 30.0)
        assertEquals(13.8, fields["tableEstimatedGrams"] as Double, 1e-9)
        assertEquals(20.7, fields["tableEstimatedObservedGrams"] as Double, 1e-9)
    }

    @Test fun heldVolumeKeepsOnlyObservedEstimate() {
        val fields = ArDensity.fields("밥", null, 10.0)
        assertNull(fields["tableEstimatedGrams"])
        assertEquals(7.3, fields["tableEstimatedObservedGrams"] as Double, 1e-9)
        assertNull(ArDensity.fields("밥", -1.0, 0.0)["tableEstimatedObservedGrams"])
    }
}
