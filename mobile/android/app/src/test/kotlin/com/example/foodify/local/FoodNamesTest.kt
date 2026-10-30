package com.example.foodify.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FoodNamesTest {
    @Test fun preservesSeparateFoodInstances() {
        assertEquals(listOf("밥", "밥"), FoodNames.parse("""{"foods":[{"name":" 밥 "},{"name":"밥"}]}"""))
    }
    @Test fun acceptsFencedJson() {
        val fence = 96.toChar().toString().repeat(3)
        assertEquals(listOf("김치"), FoodNames.parse(fence + "json\n" + """{"foods":[{"name":"김치"}]}""" + "\n" + fence))
    }
    @Test fun rejectsNutritionAndInvalidNames() {
        for (json in listOf(
            """{"foods":[{"name":"밥","calories":200}]}""",
            """{"foods":[{"name":123}]}""",
            """{"foods":[{"name":""}]}""",
            """{"foods":[]}""",
            """{"foods":[{"name":"밥"}],"extra":"text"}"""
        )) assertThrows(Exception::class.java) { FoodNames.parse(json) }
    }
}
