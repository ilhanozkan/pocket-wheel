package dev.pocketwheel.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Protocol {
    const val PORT = 26760
    private val hex16 = Regex("[0-9a-f]{16}")
    private val hex64 = Regex("[0-9a-f]{64}")
    fun validKey(key: String) = hex16.matches(key)
    fun nonce(): String = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun sign(payload: String, key: String): String {
        require(validKey(key))
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = hmac.doFinal(payload.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "$payload|$signature"
    }

    fun verifiedFields(message: String, key: String): List<String>? {
        if (message.length > 512 || message.any { it.code !in 32..126 } || !validKey(key)) return null
        val split = message.lastIndexOf('|')
        if (split < 0 || !hex64.matches(message.substring(split + 1))) return null
        val expected = sign(message.substring(0, split), key).substringAfterLast('|')
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), message.substring(split + 1).toByteArray(Charsets.US_ASCII))) return null
        return message.substring(0, split).split('|')
    }

    fun handshake(nonce: String, key: String) = sign("PWH1|$nonce", key)
    fun challenge(message: String, clientNonce: String, key: String): String? {
        val fields = verifiedFields(message, key) ?: return null
        return challengeFields(fields, clientNonce)
    }
    internal fun challengeFields(fields: List<String>, clientNonce: String): String? {
        return if (fields.size == 3 && fields[0] == "PWC1" && fields[1] == clientNonce && hex16.matches(fields[2])) fields[2] else null
    }

    data class Ack(val sequence: Int, val armed: Boolean)
    fun ack(message: String, session: String, key: String): Ack? {
        val fields = verifiedFields(message, key) ?: return null
        return ackFields(fields, session)
    }
    internal fun ackFields(fields: List<String>, session: String): Ack? {
        if (fields.size != 4 || fields[0] != "PWA1" || fields[1] != session) return null
        val seq = fields[2].toIntOrNull() ?: return null
        if (seq < 0 || fields[2] != seq.toString() || fields[3] !in listOf("0", "1")) return null
        return Ack(seq, fields[3] == "1")
    }

    fun frame(session: String, sequence: Int, input: Controls, key: String): String {
        require(hex16.matches(session) && sequence >= 0)
        val s = if (input.armed) input else Controls()
        return sign("PW1|$session|$sequence|${s.steering.coerceIn(-10000, 10000)}|${s.throttle.coerceIn(0, 10000)}|${s.brake.coerceIn(0, 10000)}|${s.buttons and 63}|${if (s.armed) 1 else 0}", key)
    }
}

data class Controls(val steering: Int = 0, val throttle: Int = 0, val brake: Int = 0, val buttons: Int = 0, val armed: Boolean = false)

/** The first release cause remains available until the next explicit Arm. */
enum class DisarmReason {
    MANUAL, CONNECTION_LOST, NETWORK_ERROR, RECEIVER_UNAVAILABLE, MAC_DISABLED, MOTION_PAUSED,
    INVALID_MOTION, PAUSED, FOCUS_LOST, CALIBRATING, SETTINGS_CHANGED, CONNECTING,
}

/** UI and network thread share latest state, never a queue of old pedal samples. */
class ControlState {
    private var controls = Controls()
    private var automaticGear = 0
    private val pulseEnds = LongArray(6)
    var lastMotionNanos: Long = 0L
        @Synchronized get
        private set
    var disarmReason: DisarmReason? = null
        @Synchronized get
        private set
    @Synchronized fun motion(value: Double, nowNanos: Long) {
        if (!value.isFinite()) { disarm(DisarmReason.INVALID_MOTION); return }
        controls = controls.copy(steering = (value.coerceIn(-1.0, 1.0) * 10000).toInt())
        lastMotionNanos = nowNanos
    }
    @Synchronized fun pedals(throttle: Double? = null, brake: Double? = null) {
        controls = controls.copy(
            throttle = if (controls.armed) ((throttle ?: controls.throttle / 10000.0).coerceIn(0.0, 1.0) * 10000).toInt() else 0,
            brake = if (controls.armed) ((brake ?: controls.brake / 10000.0).coerceIn(0.0, 1.0) * 10000).toInt() else 0,
        )
    }
    @Synchronized fun pulse(bit: Int, nowNanos: Long) {
        if (!controls.armed || bit !in 3..5) return
        // A sequential shift releases the automatic selector before sending a pulse.
        if (bit == 3 || bit == 4) { automaticGear = 0; for (index in 0..2) pulseEnds[index] = 0 }
        if (pulseEnds[bit] <= nowNanos) pulseEnds[bit] = nowNanos + 180_000_000L
    }
    @Synchronized fun selectAutomaticGear(bit: Int, nowNanos: Long) {
        if (!controls.armed || bit !in 0..2) return
        for (index in 0..4) pulseEnds[index] = 0
        // ETS2's automatic Drive/Reverse bindings are selector positions held continuously.
        automaticGear = when (bit) { 0 -> 1; 2 -> 4; else -> 0 }
        if (bit == 1) pulseEnds[1] = nowNanos + 180_000_000L
    }
    @Synchronized fun arm() {
        controls = controls.copy(armed = true, throttle = 0, brake = 0, buttons = 0)
        automaticGear = 0
        pulseEnds.fill(0)
        disarmReason = null
    }
    @Synchronized fun disarm(reason: DisarmReason = DisarmReason.MANUAL) {
        if (controls.armed || disarmReason == null) disarmReason = reason
        controls = Controls()
        automaticGear = 0
        pulseEnds.fill(0)
    }
    @Synchronized fun snapshot(nowNanos: Long): Controls {
        var buttons = automaticGear
        for (bit in 0..5) if (pulseEnds[bit] > nowNanos) buttons = buttons or (1 shl bit)
        return if (controls.armed) controls.copy(buttons = buttons) else Controls()
    }
}
