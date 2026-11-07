package com.example.foodify.ar

import kotlin.math.abs

/** Raw depth may retain its observation timestamp after ARCore reprojection. */
object ArTiming {
    const val MAX_SOURCE_AGE_MS=1500.0 // Experimental bound; recorded separately from alignment.
    fun reasons(f:ArVolume.Frame):List<String> {
        val reasons=mutableListOf<String>()
        if(!f.tracking) reasons.add("카메라 추적 불안정")
        if(listOf(f.frameNs,f.depthNs,f.confidenceNs,f.imageNs).any {it<=0}) reasons.add("촬영 시각 정보 확인 필요")
        if(abs(f.frameNs.toDouble()-f.imageNs)/1e6>50) reasons.add("사진과 AR 프레임 시간차 초과")
        val age=(f.frameNs.toDouble()-f.depthNs)/1e6
        if(age< -5 || age>MAX_SOURCE_AGE_MS) reasons.add("깊이 원본 관측 시각의 허용 범위 초과")
        if(f.sameFramePair) {
            // Confidence may use either the observation timestamp or the current image timestamp.
            val distance=minOf(abs(f.confidenceNs.toDouble()-f.imageNs),abs(f.confidenceNs.toDouble()-f.depthNs))/1e6
            if(distance>50) reasons.add("신뢰도 영상의 프레임 대응 확인 필요")
        } else if(f.depthNs!=f.confidenceNs) reasons.add("깊이·신뢰도의 동일 프레임 획득 근거 확인 필요")
        return reasons
    }
}
