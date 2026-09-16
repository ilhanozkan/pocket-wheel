package dev.pocketwheel.core

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val key = "0123456789abcdef"
    private val session = "aabbccddeeff0011"

    @Test fun `HMAC interoperates with UTF8 key golden vector`() {
        assertEquals("PWH1|0011223344556677|a678e911d5abffb9b1f655010d3b926c06934f59c083729d6a9a3793ef9713c1",
            Protocol.handshake("0011223344556677", key))
    }

    @Test fun `tampering wrong keys truncated signatures and newlines fail verification`() {
        val signed = Protocol.sign("PWA1|$session|23|1", key)
        assertNotNull(Protocol.ack(signed, session, key))
        assertNull(Protocol.ack(signed.replace("|23|", "|24|"), session, key))
        assertNull(Protocol.ack(signed, session, "fedcba9876543210"))
        assertNull(Protocol.ack(signed.dropLast(1), session, key))
        assertNull(Protocol.ack("$signed\n", session, key))
        assertNull(Protocol.ack(signed, "0000000000000000", key))
    }

    @Test fun `challenge binds to current unpredictable client nonce`() {
        val challenge = Protocol.sign("PWC1|0011223344556677|$session", key)
        assertEquals(session, Protocol.challenge(challenge, "0011223344556677", key))
        assertNull(Protocol.challenge(challenge, "1122334455667788", key))
    }

    @Test fun `malformed ack never refreshes liveness`() {
        for (payload in listOf("PWA1|$session|-1|1", "PWA1|$session|2147483648|1", "PWA1|$session|0001|1", "PWA1|$session|2|2", "PWA1|$session|2|1|extra")) {
            assertNull(payload, Protocol.ack(Protocol.sign(payload, key), session, key))
        }
    }

    @Test fun `disarmed frame physically zeros all output fields`() {
        val frame = Protocol.frame(session, 9, Controls(-8000, 7000, 2000, 63, false), key)
        assertEquals(listOf("PW1", session, "9", "0", "0", "0", "0", "0"), Protocol.verifiedFields(frame, key))
    }

    @Test fun `first disarm cause survives lifecycle cleanup and reconnect until explicit arm`() {
        val state = ControlState()
        state.arm()
        state.pedals(throttle = 0.8, brake = 0.2)
        state.disarm(DisarmReason.CONNECTION_LOST)
        for (cleanup in listOf(DisarmReason.PAUSED, DisarmReason.MANUAL, DisarmReason.CONNECTING)) {
            state.disarm(cleanup)
            assertEquals(DisarmReason.CONNECTION_LOST, state.disarmReason)
            assertEquals(Controls(), state.snapshot(System.nanoTime()))
        }
        state.arm()
        assertNull(state.disarmReason)
        state.disarm(DisarmReason.FOCUS_LOST)
        assertEquals(DisarmReason.FOCUS_LOST, state.disarmReason)
    }

    @Test fun `buttons pulse once then release and disarm clears held pedals`() {
        val state = ControlState()
        state.arm()
        state.pedals(throttle = 0.8, brake = 0.3)
        state.pulse(3, 1_000_000_000L)
        state.pulse(3, 1_100_000_000L) // A second callback cannot lengthen this pulse.
        assertEquals(8, state.snapshot(1_179_000_000L).buttons)
        assertEquals(0, state.snapshot(1_180_000_000L).buttons)
        assertEquals(8000, state.snapshot(1_200_000_000L).throttle)
        assertEquals(3000, state.snapshot(1_200_000_000L).brake)
        state.disarm()
        state.pedals(throttle = 1.0, brake = 1.0)
        state.pulse(0, 2_000_000_000L)
        assertEquals(Controls(), state.snapshot(2_000_000_000L))
        state.arm()
        assertEquals(Controls(armed = true), state.snapshot(2_000_000_000L))
    }

    @Test fun `automatic selectors latch exclusively and neutral releases them`() {
        val state = ControlState()
        state.arm()
        state.selectAutomaticGear(0, 1_000_000_000L)
        assertEquals(1, state.snapshot(9_000_000_000L).buttons)
        state.selectAutomaticGear(2, 10_000_000_000L)
        assertEquals(4, state.snapshot(15_000_000_000L).buttons)
        state.selectAutomaticGear(1, 16_000_000_000L)
        assertEquals(2, state.snapshot(16_100_000_000L).buttons)
        assertEquals(0, state.snapshot(17_000_000_000L).buttons)
        state.selectAutomaticGear(0, 18_000_000_000L)
        state.pulse(3, 19_000_000_000L)
        assertEquals(8, state.snapshot(19_100_000_000L).buttons)
        assertEquals(0, state.snapshot(20_000_000_000L).buttons)
        state.selectAutomaticGear(2, 21_000_000_000L)
        state.disarm()
        state.arm()
        assertEquals(0, state.snapshot(22_000_000_000L).buttons)
    }
}
