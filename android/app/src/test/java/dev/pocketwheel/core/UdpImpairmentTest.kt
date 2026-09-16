package dev.pocketwheel.core

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/** Exercise the real client worker with deterministic loss, delay and packet reordering. */
class UdpImpairmentTest {
    private val key = "0123456789abcdef"
    private val session = "aabbccddeeff0011"

    @Test fun `invalid and stale reply traffic is distinguished from a silent return path`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1500
            val input = ControlState()
            val statuses = CopyOnWriteArrayList<ConnectionStatus>()
            val client = UdpController("127.0.0.1", key, input, statuses::add, server.localPort)
            fun receive(): Pair<List<String>, DatagramPacket> {
                val packet = DatagramPacket(ByteArray(513), 513)
                server.receive(packet)
                return (Protocol.verifiedFields(String(packet.data, 0, packet.length, Charsets.US_ASCII), key)
                    ?: error("Unauthenticated client packet")) to packet
            }
            fun reply(payload: String, target: DatagramPacket, signingKey: String = key) {
                val bytes = Protocol.sign(payload, signingKey).toByteArray(Charsets.US_ASCII)
                server.send(DatagramPacket(bytes, bytes.size, target.address, target.port))
            }
            try {
                client.start()
                val (hello, origin) = receive()
                reply("PWC1|${hello[1]}|$session", origin)
                val (initial, initialPacket) = receive()
                reply("PWA1|$session|${initial[2]}|0", initialPacket)
                input.motion(0.2, System.nanoTime())
                input.arm()
                val deadline = System.nanoTime() + 2_000_000_000L
                var reconnect = false
                while (System.nanoTime() < deadline) {
                    input.motion(0.2, System.nanoTime())
                    val (frame, packet) = receive()
                    if (frame[0] == "PWH1") { reconnect = true; break }
                    // Mix unauthenticated, authenticated-but-wrong-session and duplicate
                    // replies. Raw socket traffic must not refresh valid-ACK liveness.
                    reply("PWA1|$session|${frame[2]}|1", packet, "fedcba9876543210")
                    reply("PWA1|1122334455667788|${frame[2]}|1", packet)
                    reply("PWA1|$session|${initial[2]}|0", packet)
                }
                assertTrue(reconnect)
                val timeout = statuses.firstNotNullOf { it.diagnostics.lastTimeout }
                val snapshot = timeout.transport
                assertTrue(timeout.replyWaitMs >= 750)
                assertTrue("Raw replies were still arriving at timeout", snapshot.rawReplyAgeMs!! < 100)
                assertTrue("No recent reply was a usable ACK", snapshot.validAckAgeMs!! >= 750)
                assertTrue(snapshot.maximumWorkerGapMs < 750)
                assertTrue(snapshot.receivedDatagrams > 10)
                assertTrue(snapshot.authenticatedReplies > 2)
                assertTrue(snapshot.discardedReplies > 5)
                assertTrue("Bad signatures are counted separately from authenticated replies",
                    snapshot.receivedDatagrams > snapshot.authenticatedReplies)
                assertEquals(1L, snapshot.acceptedAcknowledgements)
                assertEquals(0L, snapshot.sendErrors)
                assertTrue("Unusable replies report delay without disabling the control path", input.snapshot(System.nanoTime()).armed)
                assertNull(input.disarmReason)
                assertTrue(statuses.last().repliesDelayed)
            } finally {
                client.close()
                assertTrue(client.awaitStopped(1500))
            }
        }
    }

    @Test fun `active receiver rejects probes while controls continue without a gap during return loss`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10
            val input = ControlState()
            val statuses = CopyOnWriteArrayList<ConnectionStatus>()
            val client = UdpController("127.0.0.1", key, input, statuses::add, server.localPort)
            var receiverNonce: String? = null
            var lastControlAt = 0L
            var lastRawAt = 0L
            var largestRawGap = 0L
            var largestControlGap = 0L
            var controlsReceived = 0
            var rawReceived = 0
            var rejected = 0
            var challenges = 0
            fun reply(payload: String, target: DatagramPacket) {
                val bytes = Protocol.sign(payload, key).toByteArray(Charsets.US_ASCII)
                server.send(DatagramPacket(bytes, bytes.size, target.address, target.port))
            }
            try {
                client.start()
                val deadline = System.nanoTime() + 2_100_000_000L
                while (System.nanoTime() < deadline) {
                    input.motion(0.2, System.nanoTime())
                    val packet = DatagramPacket(ByteArray(513), 513)
                    try { server.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val now = System.nanoTime()
                    rawReceived++
                    if (lastRawAt != 0L) largestRawGap = maxOf(largestRawGap, now - lastRawAt)
                    lastRawAt = now
                    val fields = Protocol.verifiedFields(String(packet.data, 0, packet.length, Charsets.US_ASCII), key)
                        ?: error("Unauthenticated client packet")
                    if (fields[0] == "PWH1") {
                        if (receiverNonce != null && fields[1] != receiverNonce) {
                            // Match ReceiverState's rule: a replacement nonce is refused
                            // until the previous stream has been quiet for more than 300ms.
                            if (now - lastControlAt <= 300_000_000L) { rejected++; continue }
                            fail("The client unexpectedly allowed its control stream to expire")
                        }
                        receiverNonce = fields[1]
                        challenges++
                        reply("PWC1|${fields[1]}|$session", packet)
                    } else {
                        if (lastControlAt != 0L) largestControlGap = maxOf(largestControlGap, now - lastControlAt)
                        lastControlAt = now
                        controlsReceived++
                        assertEquals("Active receiver session must remain unchanged", session, fields[1])
                        if (controlsReceived == 1) {
                            reply("PWA1|$session|${fields[2]}|0", packet)
                            input.arm()
                            input.pedals(throttle = 0.6)
                        } else if (controlsReceived < 5) {
                            reply("PWA1|$session|${fields[2]}|1", packet)
                        }
                        if (controlsReceived > 1) assertEquals("1", fields[7])
                        // After four healthy frames, all ACK return traffic is lost.
                    }
                }
                assertTrue(input.snapshot(System.nanoTime()).armed)
                assertNull(input.disarmReason)
                assertTrue("No receiver watchdog gap is introduced", largestControlGap < 300_000_000L)
                assertTrue("Active receiver rejected takeover probes", rejected >= 2)
                assertEquals("There is only the initial session challenge", 1, challenges)
                assertEquals("Every additional raw packet is a rejected HELLO or answered challenge",
                    rawReceived, controlsReceived + rejected + challenges)
                println("Return-path loss: raw=$rawReceived controls=$controlsReceived rejected=$rejected challenges=$challenges " +
                    "maxRawGapMs=${largestRawGap / 1_000_000.0} maxControlGapMs=${largestControlGap / 1_000_000.0}")
            } finally {
                client.close()
                assertTrue(client.awaitStopped(1500))
            }
        }
    }

    @Test fun `bursty reordered duplicate ACKs below timeout do not halt sending or disarm`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 10
            val input = ControlState()
            val statuses = CopyOnWriteArrayList<ConnectionStatus>()
            val client = UdpController("127.0.0.1", key, input, statuses::add, server.localPort)
            val delayed = mutableListOf<DatagramPacket>()
            val intervals = listOf(100L, 250L, 500L, 100L, 600L, 100L)
            var intervalIndex = 0
            var releaseAt = Long.MAX_VALUE
            var firstFrameSeen = false
            var frames = 0
            var previousFrame = 0L
            var largestFrameGap = 0L
            var signTotalNanos = 0L
            var signCount = 0
            fun response(payload: String, target: DatagramPacket): DatagramPacket {
                val started = System.nanoTime()
                val bytes = Protocol.sign(payload, key).toByteArray(Charsets.US_ASCII)
                signTotalNanos += System.nanoTime() - started
                signCount++
                return DatagramPacket(bytes, bytes.size, target.address, target.port)
            }
            try {
                client.start()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (intervalIndex < intervals.size && System.nanoTime() < deadline) {
                    input.motion(0.2, System.nanoTime())
                    val packet = DatagramPacket(ByteArray(513), 513)
                    try {
                        server.receive(packet)
                        val fields = Protocol.verifiedFields(String(packet.data, 0, packet.length, Charsets.US_ASCII), key)
                            ?: error("Unauthenticated client frame")
                        if (fields[0] == "PWH1") {
                            assertFalse("ACK bursts must not trigger a new handshake", firstFrameSeen)
                            server.send(response("PWC1|${fields[1]}|$session", packet))
                        } else {
                            val now = System.nanoTime()
                            if (previousFrame != 0L) largestFrameGap = maxOf(largestFrameGap, now - previousFrame)
                            previousFrame = now
                            frames++
                            if (!firstFrameSeen) {
                                server.send(response("PWA1|$session|${fields[2]}|0", packet))
                                firstFrameSeen = true
                                input.arm()
                                input.pedals(throttle = 0.6)
                                releaseAt = now + intervals[0] * 1_000_000L
                            } else {
                                assertEquals("Client unexpectedly disarmed: ${input.disarmReason}; ${statuses.lastOrNull()}", "1", fields[7])
                                assertEquals("6000", fields[4])
                                delayed += response("PWA1|$session|${fields[2]}|1", packet)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Keep sensor liveness independent of ACK delivery and packet arrivals.
                    }
                    if (System.nanoTime() >= releaseAt) {
                        // Deliver newest first, then duplicate and stale ACKs. The client's
                        // monotonic ACK rule must reject stale replies without pausing output.
                        for (reply in delayed.asReversed()) { server.send(reply); server.send(reply) }
                        delayed.clear()
                        intervalIndex++
                        if (intervalIndex < intervals.size) releaseAt = System.nanoTime() + intervals[intervalIndex] * 1_000_000L
                    }
                }
                assertEquals("Every impairment phase completed", intervals.size, intervalIndex)
                assertTrue("Controller remains armed during sub-timeout ACK bursts", input.snapshot(System.nanoTime()).armed)
                assertTrue("Frame traffic continues at a useful rate: $frames frames", frames >= 60)
                assertNull("Healthy delayed replies produce no timeout incident", statuses.last().diagnostics.lastTimeout)
                assertTrue("Reordered duplicates are counted without resetting liveness",
                    statuses.last().diagnostics.current.discardedReplies > 0)
                assertTrue("No client send stall crosses Mac's 300ms watchdog: ${largestFrameGap / 1_000_000.0}ms",
                    largestFrameGap < 300_000_000L)
                println("UDP impairment: frames=$frames maxFrameGapMs=${largestFrameGap / 1_000_000.0} " +
                    "meanHmacMs=${signTotalNanos / signCount / 1_000_000.0} intervalsMs=$intervals")
            } finally {
                client.close()
                assertTrue(client.awaitStopped(1500))
            }
        }
    }
}
