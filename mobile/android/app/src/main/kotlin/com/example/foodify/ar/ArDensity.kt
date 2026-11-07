package com.example.foodify.ar

/**
 * Published food densities for weight = AR volume x density, without per-food scale calibration.
 *
 * Source: FAO/INFOODS Density Database Version 2.0 (2012), Table 2 "Densities and specific gravities
 * of foods" (https://www.fao.org/4/ap815e/ap815e.pdf). Values include bulk density.
 * Experimental (M11): evaluated against scale labels before any use in the meal flow.
 */
object ArDensity {
    const val TABLE_VERSION = 1

    data class Entry(val keywords: List<String>, val density: Double, val sourceFood: String,
                     val sourceRef: String, val range: Pair<Double, Double>? = null)

    // Longer keywords first so "초코칩 쿠키" or "커피땅콩" never fall through to a broader entry.
    val TABLE = listOf(
        Entry(listOf("커피땅콩", "땅콩"), 0.69, "Peanuts, shelled", "FAO/INFOODS Density v2.0 (ASI)"),
        Entry(listOf("흰쌀밥", "쌀밥", "밥"), 0.73, "Rice, boiled", "FAO/INFOODS Density v2.0 (KEN)"),
        Entry(listOf("초코칩 쿠키", "쿠키", "비스킷"), 0.47, "Biscuits", "FAO/INFOODS Density v2.0 (FNDDS 4.1)",
            range = 0.41 to 0.53),
    )

    /** Exact name, or a name ending with a keyword (e.g. "현미밥" -> 밥). */
    fun lookup(foodName: String): Entry? {
        val name = foodName.replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) return null
        val candidates = TABLE.flatMap { e -> e.keywords.map { it to e } }.sortedByDescending { it.first.length }
        return candidates.firstOrNull { (k, _) -> name == k }?.second
            ?: candidates.firstOrNull { (k, _) -> name.endsWith(k) || name.replace(" ", "").endsWith(k.replace(" ", "")) }?.second
    }

    /** Fields added to an estimate result; scale labels never enter this calculation. */
    fun fields(foodName: String, volumeMl: Double?, observedVolumeMl: Double?): Map<String, Any?> {
        val entry = lookup(foodName)
            ?: return mapOf("tableDensityGPerMl" to null, "tableDensityVersion" to TABLE_VERSION,
                "tableDensityMessage" to "밀도표에 없는 음식입니다.", "tableEstimatedGrams" to null,
                "tableEstimatedObservedGrams" to null)
        fun grams(v: Double?) = v?.takeIf { it.isFinite() && it > 0 }?.let { it * entry.density }
        return mapOf(
            "tableDensityGPerMl" to entry.density,
            "tableDensitySourceFood" to entry.sourceFood,
            "tableDensitySource" to entry.sourceRef,
            "tableDensityRange" to entry.range?.let { listOf(it.first, it.second) },
            "tableDensityVersion" to TABLE_VERSION,
            "tableEstimatedGrams" to grams(volumeMl),               // quality-passed volume only
            "tableEstimatedObservedGrams" to grams(observedVolumeMl), // includes held results, research only
        )
    }
}
