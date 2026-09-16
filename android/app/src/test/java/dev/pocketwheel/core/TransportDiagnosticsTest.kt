package dev.pocketwheel.core

import org.junit.Assert.*
import org.junit.Test

class TransportDiagnosticsTest {
    @Test fun `timeout preserves a worker stall while later traffic updates only the live snapshot`() {
        val diagnostics = TransportDiagnostics()
        diagnostics.loop(1_000_000_000L)
        diagnostics.received(1_000_000_000L)
        diagnostics.authenticated()
        diagnostics.acknowledgementAccepted(1_000_000_000L)
        diagnostics.loop(1_900_000_000L)
        diagnostics.sent(controlFrame = true, successful = true, durationNanos = 4_000_000L)
        diagnostics.timeout(1_900_000_000L, 900_000_000L)
        val frozen = diagnostics.status(1_900_000_000L).lastTimeout!!
        assertEquals(900L, frozen.replyWaitMs)
        assertEquals(900L, frozen.transport.maximumWorkerGapMs)
        assertEquals(900L, frozen.transport.rawReplyAgeMs)
        assertEquals(900L, frozen.transport.validAckAgeMs)

        diagnostics.loop(1_904_000_000L)
        diagnostics.received(1_904_000_000L)
        diagnostics.authenticated()
        diagnostics.challengeAccepted()
        diagnostics.sent(controlFrame = false, successful = false, durationNanos = 8_000_000L)
        diagnostics.received(1_906_000_000L)
        diagnostics.authenticated()
        diagnostics.acknowledgementAccepted(1_906_000_000L)
        val resumed = diagnostics.status(1_910_000_000L)
        assertEquals("Reconnecting must never erase or mutate the incident", frozen, resumed.lastTimeout)
        assertEquals(4L, resumed.current.rawReplyAgeMs)
        assertEquals(4L, resumed.current.validAckAgeMs)
        assertEquals(1L, resumed.current.sendErrors)
        assertEquals(8L, resumed.current.maximumSendDurationMs)
        assertEquals(3L, resumed.current.receivedDatagrams)
        assertEquals(1L, frozen.transport.receivedDatagrams)
    }
}
