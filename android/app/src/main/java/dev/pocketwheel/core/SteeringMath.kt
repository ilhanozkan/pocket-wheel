package dev.pocketwheel.core

import kotlin.math.*

/** Unit quaternion mapping the device's coordinates into Android's world frame. */
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Quaternion? {
        val length = sqrt(w * w + x * x + y * y + z * z)
        if (!length.isFinite() || length < 1e-8) return null
        return Quaternion(w / length, x / length, y / length, z / length)
    }
    fun conjugate() = Quaternion(w, -x, -y, -z)
    operator fun times(b: Quaternion) = Quaternion(
        w * b.w - x * b.x - y * b.y - z * b.z,
        w * b.x + x * b.w + y * b.z - z * b.y,
        w * b.y - x * b.z + y * b.w + z * b.x,
        w * b.z + x * b.y - y * b.x + z * b.w,
    )
}

data class SteeringSettings(
    val degreesEachSide: Double = 90.0,
    val deadZoneDegrees: Double = 2.0,
    val smoothingMs: Double = 45.0,
    val responseExponent: Double = 1.0,
    val inverted: Boolean = false,
)

/** Relative screen-normal twist: works with the phone held upright or tilted.
 * Landscape display rotation does not change the physical screen-normal z axis.
 * Android's counter-clockwise positive rotation is negated for right-positive steering.
 */
class SteeringMath {
    private var center: Quaternion? = null
    private var filtered = 0.0
    private var previousWrappedDegrees = 0.0
    private var continuousDegrees = 0.0
    var angleDegrees = 0.0
        private set
    val calibrated get() = center != null

    fun clearCalibration() { center = null; filtered = 0.0; angleDegrees = 0.0; previousWrappedDegrees = 0.0; continuousDegrees = 0.0 }
    fun calibrate(current: Quaternion): Boolean {
        center = current.normalized()
        filtered = 0.0
        angleDegrees = 0.0
        previousWrappedDegrees = 0.0
        continuousDegrees = 0.0
        return calibrated
    }

    fun update(current: Quaternion, elapsedSeconds: Double, settings: SteeringSettings): Double? {
        val baseline = center ?: return 0.0
        val q = current.normalized() ?: return null
        val relative = baseline.conjugate() * q
        if (hypot(relative.w, relative.z) < 1e-6) return null // Singular 180° out-of-plane flip.
        var degrees = -Math.toDegrees(2.0 * atan2(relative.z, relative.w))
        while (degrees > 180.0) degrees -= 360.0
        while (degrees < -180.0) degrees += 360.0
        // Crossing the ±180° representation boundary must never reverse full steering.
        var delta = degrees - previousWrappedDegrees
        while (delta > 180.0) delta -= 360.0
        while (delta < -180.0) delta += 360.0
        continuousDegrees += delta
        previousWrappedDegrees = degrees
        angleDegrees = if (settings.inverted) -continuousDegrees else continuousDegrees
        val target = mapAngle(angleDegrees, settings)
        val timeConstant = settings.smoothingMs.coerceIn(0.0, 200.0) / 1000.0
        val alpha = if (timeConstant == 0.0) 1.0
            else 1.0 - exp(-elapsedSeconds.coerceIn(0.001, 0.25) / timeConstant)
        filtered += alpha * (target - filtered)
        return filtered.coerceIn(-1.0, 1.0)
    }

    companion object {
        fun mapAngle(angle: Double, settings: SteeringSettings): Double {
            val range = settings.degreesEachSide.coerceIn(30.0, 160.0)
            val deadZone = settings.deadZoneDegrees.coerceIn(0.0, min(15.0, range - 1.0))
            val magnitude = ((abs(angle) - deadZone) / (range - deadZone)).coerceIn(0.0, 1.0)
            return sign(angle) * magnitude.pow(settings.responseExponent.coerceIn(0.5, 2.0))
        }
    }
}
