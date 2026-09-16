package dev.pocketwheel.core

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class UdpControllerTest {
    private val key = "0123456789abcdef"
    private val session = "aabbccddeeff0011"

    @Test fun `address resolution failure is not mislabeled as a reply timeout`() {
        val input = ControlState()
        val statuses = CopyOnWriteArrayList<ConnectionStatus>()
        val client = UdpController("unresolvable", key, input, statuses::add, resolveAddress = {
            throw java.net.UnknownHostException("Test receiver unavailable")
        })
        client.start()
        assertTrue(client.awaitStopped(1500))
        assertFalse(input.snapshot(System.nanoTime()).armed)
        assertEquals(DisarmReason.NETWORK_ERROR, input.disarmReason)
        assertTrue(statuses.last().message.startsWith("Network unavailable"))
    }

    @Test fun `delayed armed acknowledgement cannot hide a motion timeout`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2500
            val input = ControlState()
            val statuses = CopyOnWriteArrayList<ConnectionStatus>()
            val client = UdpController("127.0.0.1", key, input, statuses::add, server.localPort)
            fun receive(): Pair<List<String>, DatagramPacket> {
                val packet = DatagramPacket(ByteArray(513), 513)
                server.receive(packet)
                val fields = Protocol.verifiedFields(String(packet.data, 0, packet.length, Charsets.US_ASCII), key)
                    ?: error("Unauthenticated client frame")
                return fields to packet
            }
            fun reply(payload: String, to: DatagramPacket) {
                val bytes = Protocol.sign(payload, key).toByteArray(Charsets.US_ASCII)
                server.send(DatagramPacket(bytes, bytes.size, to.address, to.port))
            }
            try {
                client.start()
                val (hello, origin) = receive()
                reply("PWC1|${hello[1]}|$session", origin)
                val (initial, initialPacket) = receive()
                reply("PWA1|$session|${initial[2]}|0", initialPacket)
                input.motion(0.2, System.nanoTime())
                input.arm()
                var armedFrame: List<String>
                var armedPacket: DatagramPacket
                do {
                    input.motion(0.2, System.nanoTime())
                    val received = receive()
                    armedFrame = received.first
                    armedPacket = received.second
                } while (armedFrame[7] != "1")

                // The sensor stops delivering samples while the receiver's positive ACK
                // is still in flight. Expire motion before delivering that older ACK.
                input.motion(0.2, System.nanoTime() - 300_000_000L)
                var neutralFrame: List<String>
                do { neutralFrame = receive().first } while (neutralFrame[7] != "0")
                assertFalse("Motion timeout releases controls", input.snapshot(System.nanoTime()).armed)
                assertEquals(DisarmReason.MOTION_PAUSED, input.disarmReason)
                reply("PWA1|$session|${armedFrame[2]}|1", armedPacket)
                repeat(12) {
                    val (frame, packet) = receive()
                    assertEquals("A late ACK must never rearm input", "0", frame[7])
                    reply("PWA1|$session|${frame[2]}|0", packet)
                }
                assertTrue("Actual disarm cause must survive the late positive ACK: ${statuses.lastOrNull()}",
                    statuses.last().message.startsWith("Motion sensor paused"))
            } finally {
                client.close()
                assertTrue(client.awaitStopped(1500))
            }
        }
    }

    @Test fun `missing return ACKs for over two seconds keeps sending latest armed controls`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 2500
            val input = ControlState()
            val statuses = CopyOnWriteArrayList<ConnectionStatus>()
            val client = UdpController("127.0.0.1", key, input, statuses::add, server.localPort)
            fun receive(): Pair<List<String>, DatagramPacket> {
                val packet = DatagramPacket(ByteArray(513), 513)
                try { server.receive(packet) }
                catch (error: java.net.SocketTimeoutException) { throw AssertionError("Client sent no datagram; statuses=$statuses", error) }
                return (Protocol.verifiedFields(String(packet.data, 0, packet.length, Charsets.US_ASCII), key)
                    ?: error("Unauthenticated client frame")) to packet
            }
            fun reply(payload: String, to: DatagramPacket) {
                val bytes = Protocol.sign(payload, key).toByteArray(Charsets.US_ASCII)
                server.send(DatagramPacket(bytes, bytes.size, to.address, to.port))
            }
            try {
                client.start()
                val (hello, origin) = receive()
                assertEquals("PWH1", hello[0])
                reply("PWC1|${hello[1]}|$session", origin)
                val (first, firstPacket) = receive()
                assertEquals(listOf("PW1", session, "0", "0", "0", "0", "0", "0"), first)
                reply("PWA1|$session|${first[2]}|0", firstPacket)
                input.motion(0.5, System.nanoTime())
                input.arm()
                input.pedals(throttle = 0.6, brake = 0.2)
                input.selectAutomaticGear(0, System.nanoTime())
                var sawArmed = false
                var updatedDuringOutage = false
                var sawUpdatedControls = false
                var probes = 0
                val probeNonces = mutableSetOf<String>()
                var frames = 0
                val deadline = System.nanoTime() + 2_100_000_000L
                val changeAt = System.nanoTime() + 1_100_000_000L
                while (System.nanoTime() < deadline) {
                    if (!updatedDuringOutage && System.nanoTime() >= changeAt) {
                        updatedDuringOutage = true
                        input.pedals(throttle = 0.8, brake = 0.1)
                        input.selectAutomaticGear(2, System.nanoTime())
                    }
                    input.motion(if (updatedDuringOutage) -0.4 else 0.5, System.nanoTime())
                    val (frame, _) = receive()
                    if (frame[0] == "PWH1") {
                        assertNotEquals(hello[1], frame[1])
                        probeNonces += frame[1]
                        probes++
                        continue
                    }
                    frames++
                    if (sawArmed) assertEquals("Missing ACKs alone must not release controls", "1", frame[7])
                    if (frame[7] == "1") {
                        sawArmed = true
                        if (frame[4] == "8000") {
                            sawUpdatedControls = true
                            assertEquals("-4000", frame[3])
                            assertEquals("1000", frame[5])
                            assertEquals("Reverse remains selected during reply loss", "4", frame[6])
                        } else {
                            assertEquals("6000", frame[4])
                            assertEquals("2000", frame[5])
                            assertEquals("Drive remains selected during reply loss", "1", frame[6])
                        }
                    }
                    // The control path reaches the Mac, while every return ACK is lost.
                }
                assertTrue("Armed control frame reached receiver", sawArmed)
                assertTrue("New steering, pedal and selector values reach the receiver during the outage", sawUpdatedControls)
                assertTrue("Repeated receiver-restart probes do not interrupt control traffic", probes >= 2)
                assertEquals("Retries reuse one nonce per outage", 1, probeNonces.size)
                assertTrue("Controls keep streaming throughout reply loss", frames > 90)
                assertTrue(input.snapshot(System.nanoTime()).armed)
                assertNull(input.disarmReason)
                assertTrue(statuses.any { it.connected && it.repliesDelayed })
                val timeout = statuses.firstNotNullOf { it.diagnostics.lastTimeout }
                assertTrue(timeout.replyWaitMs >= 750)
                assertTrue("Dropped replies age out at the raw socket boundary", timeout.transport.rawReplyAgeMs!! >= 750)
                assertTrue(timeout.transport.validAckAgeMs!! >= 750)
                assertTrue("The worker kept running while replies were absent", timeout.transport.maximumWorkerGapMs < 750)
                assertEquals(0L, timeout.transport.discardedReplies)
                assertEquals(0L, timeout.transport.sendErrors)
                assertEquals("One immutable diagnostic is captured per outage", 1,
                    statuses.mapNotNull { it.diagnostics.lastTimeout?.transport?.capturedAtNanos }.distinct().size)
            } finally { client.close(); assertTrue(client.awaitStopped(1500)) }
        }
    }

    @Test fun `receiver can start late and restart without killing retry worker or rearming`() {
        val address = InetAddress.getByName("127.0.0.1")
        val port = DatagramSocket(0, address).use { it.localPort }
        val input = ControlState()
        val statuses = CopyOnWriteArrayList<ConnectionStatus>()
        val unreachable = CountDownLatch(1)
        val paired = CountDownLatch(1)
        val client = UdpController("127.0.0.1", key, input, {
            statuses += it
            if (it.message.startsWith("Receiver unavailable")) unreachable.countDown()
            if (it.connected) paired.countDown()
        }, port)
        var server: DatagramSocket? = null
        fun receive(socket: DatagramSocket): Pair<List<String>, DatagramPacket> {
            val packet = DatagramPacket(ByteArray(513), 513)
            val deadline = System.nanoTime() + 2_500_000_000L
            socket.soTimeout = 20
            while (true) {
                // Sensor samples continue independently while a receiver waits for the
                // next 500ms probe, including platforms that do not report ICMP errors.
                input.motion(0.0, System.nanoTime())
                try { socket.receive(packet); break }
                catch (error: java.net.SocketTimeoutException) {
                    if (System.nanoTime() >= deadline) throw error
                }
            }
            return (Protocol.verifiedFields(String(packet.data, 0, packet.length, Charsets.US_ASCII), key)
                ?: error("Unauthenticated client datagram")) to packet
        }
        fun pair(socket: DatagramSocket, receiverSession: String): List<String> {
            socket.soTimeout = 2500
            var hello: List<String>
            var origin: DatagramPacket
            do {
                val received = receive(socket)
                hello = received.first
                origin = received.second
            } while (hello[0] != "PWH1")
            val challenge = Protocol.sign("PWC1|${hello[1]}|$receiverSession", key).toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(challenge, challenge.size, origin.address, origin.port))
            var received: Pair<List<String>, DatagramPacket>
            do { received = receive(socket) } while (received.first[0] != "PW1" || received.first[1] != receiverSession)
            val (frame, packet) = received
            assertEquals(listOf("PW1", receiverSession, "0", "0", "0", "0", "0", "0"), frame)
            val ack = Protocol.sign("PWA1|$receiverSession|0|0", key).toByteArray(Charsets.US_ASCII)
            socket.send(DatagramPacket(ack, ack.size, packet.address, packet.port))
            return hello
        }
        try {
            client.start()
            // On hosts that deliver ICMP this observes PortUnreachableException. Otherwise
            // the wait still leaves the receiver absent long enough for a handshake retry.
            unreachable.await(700, TimeUnit.MILLISECONDS)
            server = DatagramSocket(port, address)
            val firstHello = pair(server, session)
            assertTrue("Late receiver must become connected: $statuses", paired.await(1000, TimeUnit.MILLISECONDS))
            input.motion(0.0, System.nanoTime())
            input.arm()
            input.pedals(throttle = 0.7)
            var sawArmed = false
            while (!sawArmed) {
                val (frame, _) = receive(server)
                sawArmed = frame[0] == "PW1" && frame[7] == "1"
            }
            server.close()
            server = null
            val deadline = System.nanoTime() + 1_500_000_000L
            while (input.snapshot(System.nanoTime()).armed && System.nanoTime() < deadline) {
                input.motion(0.0, System.nanoTime()) // Isolate network loss from motion timeout.
                Thread.sleep(10)
            }
            // ICMP is optional. Without it, lost replies remain a soft outage until
            // the restarted receiver confirms a new session or rejects active input.
            if (input.snapshot(System.nanoTime()).armed) {
                assertTrue(statuses.any { it.repliesDelayed })
            } else assertEquals(DisarmReason.RECEIVER_UNAVAILABLE, input.disarmReason)
            val releaseCause = input.disarmReason ?: DisarmReason.CONNECTION_LOST
            server = DatagramSocket(port, address)
            val nextHello = pair(server, "1122334455667788")
            assertNotEquals("Reconnect creates a fresh challenge nonce", firstHello[1], nextHello[1])
            assertFalse("Restart cannot arm automatically", input.snapshot(System.nanoTime()).armed)
            assertEquals("A new handshake must preserve the cause of the previous disarm", releaseCause, input.disarmReason)
            assertFalse("Port unreachable must not terminate the worker", statuses.any { it.message.startsWith("Network unavailable") })
        } finally {
            client.close()
            assertTrue(client.awaitStopped(1500))
            server?.close()
        }
    }

    @Test fun `closed worker returning from slow DNS cannot clear replacement input`() {
        val resolverEntered = CountDownLatch(1)
        val releaseResolver = CountDownLatch(1)
        val input = ControlState()
        val oldClient = UdpController("slow-resolver", key, input, {}, resolveAddress = {
            resolverEntered.countDown()
            check(releaseResolver.await(2, TimeUnit.SECONDS))
            InetAddress.getByName("127.0.0.1")
        })
        oldClient.start()
        try {
            assertTrue(resolverEntered.await(1000, TimeUnit.MILLISECONDS))
            oldClient.close() // Must return while DNS remains blocked; no UI-thread join.
            input.arm()
            input.motion(0.4, System.nanoTime())
            input.pedals(throttle = 0.6, brake = 0.1)
            input.selectAutomaticGear(0, System.nanoTime())
            val replacementState = input.snapshot(System.nanoTime())
            releaseResolver.countDown()
            assertTrue(oldClient.awaitStopped(1000))
            assertEquals(replacementState, input.snapshot(System.nanoTime()))
            oldClient.close() // Repeated close is also unable to disarm the replacement.
            assertEquals(replacementState, input.snapshot(System.nanoTime()))
        } finally {
            releaseResolver.countDown()
            oldClient.close()
        }
    }
}
