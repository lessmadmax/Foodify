package com.example.foodify.ar

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * M11 capture rules: hold the phone level above the food (tilt <= 10 degrees from straight down)
 * and move it slowly sideways so ARCore can build depth. Pure logic for unit tests.
 *
 * 2026-11-04 S23 records: food depth confidence was 0% at 20~42 degree tilt and 42~87% when held
 * level over a matte plate, so tilt is the main gate.
 */
object ArGuidance {
    const val VERSION = 1
    const val MAX_TILT_DEGREES = 10.0
    const val MIN_SPEED_MPS = 0.01   // below this the phone is effectively still: no parallax for depth
    const val MAX_SPEED_MPS = 0.15   // above this frames blur and depth lags
    const val SPEED_WINDOW_MS = 600L

    /**
     * Angle between the camera viewing direction and straight down.
     * ARCore camera looks along -Z of its pose, world +Y is up, so cos(tilt) = zAxis.y.
     */
    fun tiltDegrees(zAxis: FloatArray): Double {
        val length = sqrt(zAxis.sumOf { (it * it).toDouble() })
        if (length == 0.0) return 180.0
        return Math.toDegrees(acos((zAxis[1] / length).coerceIn(-1.0, 1.0)))
    }

    /** Camera speed from pose translations over a short sliding window. */
    class MotionWindow(private val windowMs: Long = SPEED_WINDOW_MS) {
        private val samples = ArrayDeque<Pair<Long, FloatArray>>()

        fun add(timeMs: Long, translation: FloatArray) {
            samples.addLast(timeMs to translation.copyOf())
            while (samples.size > 2 && timeMs - samples[1].first >= windowMs) samples.removeFirst()
        }

        fun clear() = samples.clear()

        /** Metres per second, or null until the window spans at least half its length. */
        fun speed(): Double? {
            if (samples.size < 2) return null
            val (t0, p0) = samples.first()
            val (t1, p1) = samples.last()
            val dt = (t1 - t0) / 1000.0
            if (dt * 1000 < windowMs / 2) return null
            var sum = 0.0
            for (i in 0 until 3) { val d = (p1[i] - p0[i]).toDouble(); sum += d * d }
            return sqrt(sum) / dt
        }
    }

    data class Advice(val tiltOk: Boolean, val motion: Motion, val messages: List<String>) {
        val ok get() = tiltOk && motion == Motion.OK
    }

    enum class Motion { UNKNOWN, STILL, OK, FAST }

    fun motion(speed: Double?): Motion = when {
        speed == null -> Motion.UNKNOWN
        speed < MIN_SPEED_MPS -> Motion.STILL
        speed > MAX_SPEED_MPS -> Motion.FAST
        else -> Motion.OK
    }

    /** [recording]: still is only a problem while frames are being collected. */
    fun advise(tilt: Double, speed: Double?, recording: Boolean): Advice {
        val messages = mutableListOf<String>()
        val tiltOk = tilt <= MAX_TILT_DEGREES
        if (!tiltOk) messages.add("기울기 %.0f° · 음식 바로 위에서 휴대폰을 수평으로 들어 주세요(10° 이내)".format(tilt))
        val motion = motion(speed)
        when (motion) {
            Motion.FAST -> messages.add("너무 빠릅니다 · 더 천천히 옆으로 움직여 주세요")
            Motion.STILL -> if (recording) messages.add("멈춰 있습니다 · 좌우로 천천히 움직여야 깊이가 생깁니다")
            else -> {}
        }
        return Advice(tiltOk, motion, messages)
    }
}
