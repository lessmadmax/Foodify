package com.example.foodify.ar

import kotlin.math.abs

object ArCalibration {
    data class Sample(val id:String,val food:String,val group:String,val split:String,val volume:Double?,val scale:Double)
    data class Model(val density:Double,val groups:Set<String>,val samples:List<String>)
    fun median(values:List<Double>):Double { require(values.isNotEmpty());val sorted=values.sorted();return if(sorted.size%2==1)sorted[sorted.size/2] else (sorted[sorted.size/2-1]+sorted[sorted.size/2])/2 }
    fun fit(food:String,samples:List<Sample>):Model {
        val relevant=samples.filter { it.food==food }
        require(relevant.groupBy { it.group }.values.none { group -> group.map { it.split }.toSet().size>1 }) { "같은 제공량 그룹의 보정·평가 자료가 섞여 있습니다." }
        val grouped=relevant.filter { it.split=="CALIBRATION" && it.volume!=null && it.volume.isFinite() && it.volume>0 && it.scale.isFinite() && it.scale>0 }.groupBy { it.group }
        require(grouped.size>=3) { "품질을 통과한 서로 다른 보정 제공량 그룹 3개 이상이 필요합니다." }
        val points=grouped.values.map { group -> Pair(median(group.map { it.volume!! }),median(group.map { it.scale })) }
        val density=points.sumOf { it.first*it.second }/points.sumOf { it.first*it.first }
        require(density.isFinite() && density>0 && density<=5) { "환산 밀도가 유효 범위를 벗어났습니다." }
        return Model(density,grouped.keys,grouped.values.flatten().map { it.id })
    }
    fun evaluate(food:String,samples:List<Sample>,model:Model):Map<String,Any?> {
        val groups=samples.filter { it.food==food && it.split=="EVALUATION" }.groupBy { it.group }
        require(groups.keys.none { it in model.groups }) { "보정에 사용한 제공량은 평가에 사용할 수 없습니다." }
        val errors=groups.values.mapNotNull { group ->
            val valid=group.filter { it.volume!=null && it.volume.isFinite() && it.volume>0 && it.scale.isFinite() && it.scale>0 }
            if(valid.isEmpty()) null else {val truth=median(valid.map { it.scale });val predicted=median(valid.map { it.volume!! })*model.density; Pair(abs(predicted-truth),abs(predicted-truth)/truth*100)}
        }
        return mapOf("evaluationGroups" to groups.size,"validGroups" to errors.size,"resultCoverage" to if(groups.isEmpty())null else errors.size.toDouble()/groups.size,
            "maeGrams" to if(errors.isEmpty())null else errors.map { it.first }.average(),"medianRelativeErrorPercent" to if(errors.isEmpty())null else median(errors.map { it.second }))
    }
}
