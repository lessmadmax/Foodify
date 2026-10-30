package com.example.foodify.local

import com.google.gson.JsonParser

object FoodNames {
    fun parse(raw: String): List<String> {
        val fence = 96.toChar().toString().repeat(3)
        var text = raw.trim()
        if (text.startsWith(fence) && text.endsWith(fence)) {
            text = text.removePrefix(fence).removeSuffix(fence).trim()
            if (text.startsWith("json")) text = text.removePrefix("json").trim()
        }
        val root = JsonParser.parseString(text).asJsonObject
        require(root.keySet() == setOf("foods")) { "음식 목록 형식을 확인해 주세요." }
        val foods = root["foods"].asJsonArray
        require(foods.size() in 1..30) { "음식을 식별하지 못했습니다." }
        return foods.map {
            val item = it.asJsonObject
            require(item.keySet() == setOf("name"))
            val name = item["name"]
            require(name.isJsonPrimitive && name.asJsonPrimitive.isString)
            name.asString.trim().also { value -> require(value.length in 1..120) }
        }
    }
}
