package dev.pocketwheel.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SteeringMathTest {
    private fun axis(degrees: Double, x: Double = 0.0, y: Double = 0.0, z: Double = 1.0): Quaternion {
        val half = Math.toRadians(degrees) / 2
        return Quaternion(cos(half), x * sin(half), y * sin(half), z * sin(half))
    }
    private val immediate = SteeringSettings(deadZoneDegrees = 0.0, smoothingMs = 0.0)

    @Test fun `clockwise turns produce positive right steering from tilted landscape center`() {
        val center = axis(67.0, x = 1.0, z = 0.0) * axis(90.0)
        val math = SteeringMath()
        assertTrue(math.calibrate(center))
        assertEquals(0.5, math.update(center * axis(-45.0), 0.01, immediate)!!, 1e-8)
        assertEquals(-1.0, math.update(center * axis(90.0), 0.01, immediate)!!, 1e-8)
    }

    @Test fun `out of plane tilt without wheel twist does not steer`() {
        val center = axis(40.0, y = 1.0, z = 0.0)
        val math = SteeringMath()
        math.calibrate(center)
        assertEquals(0.0, math.update(center * axis(30.0, x = 1.0, z = 0.0), 0.01, immediate)!!, 1e-8)
    }

    @Test fun `quaternion sign ambiguity does not flip steering`() {
        val math = SteeringMath()
        math.calibrate(axis(0.0))
        val q = axis(-70.0)
        val first = math.update(q, 0.01, immediate)!!
        val opposite = Quaternion(-q.w, -q.x, -q.y, -q.z)
        assertEquals(first, math.update(opposite, 0.01, immediate)!!, 1e-8)
    }

    @Test fun `dead zone is continuous and range saturates`() {
        val settings = immediate.copy(deadZoneDegrees = 5.0)
        assertEquals(0.0, SteeringMath.mapAngle(4.99, settings), 0.0)
        assertEquals(0.5, SteeringMath.mapAngle(47.5, settings), 1e-9)
        assertEquals(-1.0, SteeringMath.mapAngle(-145.0, settings), 0.0)
    }

    @Test fun `smoothing is independent of sensor sample rate`() {
        fun result(steps: Int): Double {
            val math = SteeringMath()
            math.calibrate(axis(0.0))
            var value = 0.0
            repeat(steps) { value = math.update(axis(-90.0), 0.1 / steps, immediate.copy(smoothingMs = 80.0))!! }
            return value
        }
        assertEquals(result(10), result(20), 1e-9)
    }

    @Test fun `recalibration resets smoothing and inversion reverses output`() {
        val math = SteeringMath()
        math.calibrate(axis(0.0))
        assertEquals(-0.5, math.update(axis(-45.0), 0.01, immediate.copy(inverted = true))!!, 1e-9)
        math.calibrate(axis(-45.0))
        assertEquals(0.0, math.update(axis(-45.0), 0.01, immediate)!!, 1e-9)
        math.clearCalibration()
        assertFalse(math.calibrated)
    }

    @Test fun `invalid or singular sensor orientation is rejected`() {
        val math = SteeringMath()
        math.calibrate(axis(0.0))
        assertNull(math.update(Quaternion(Double.NaN, 0.0, 0.0, 0.0), 0.01, immediate))
        assertNull(math.update(axis(180.0, x = 1.0, z = 0.0), 0.01, immediate))
    }

    @Test fun `turning past upside down keeps full steering until wheel turns back`() {
        val math = SteeringMath()
        math.calibrate(axis(0.0))
        for (degrees in listOf(-80.0, -150.0, -179.0, -181.0, -220.0)) {
            val value = math.update(axis(degrees), 0.01, immediate)!!
            assertTrue("Right turn $degrees must not suddenly become left", value > 0.0)
        }
        assertEquals(220.0, math.angleDegrees, 1e-8)
        for (degrees in listOf(-160.0, -90.0, -30.0, 0.0)) math.update(axis(degrees), 0.01, immediate)
        assertEquals(0.0, math.angleDegrees, 1e-8)
    }
}
