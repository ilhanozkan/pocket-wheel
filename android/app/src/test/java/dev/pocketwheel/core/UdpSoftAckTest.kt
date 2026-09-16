package dev.pocketwheel.core

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class UdpSoftAckTest {
    private val key = "0123456789abcdef"
    private val firstSession = "aabbccddeeff0011"
    private val secondSession = "1122334455667788"

    private data class Received(val fields: List<String>, val packet: DatagramPacket)

    private inner class Connection : Closeable {
        val server = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1500 }
        val input = ControlState()
        val statuses = CopyOnWriteArrayList<ConnectionStatus>()
        val client = UdpController("127.0.0.1", key, input, statuses::add, server.localPort)
        lateinit var originalNonce: String

        fun receive(): Received {
            input.motion(0.2, System.nanoTime())
            val packet = DatagramPacket(ByteArray(513), 513)
            server.receive(packet)
            return Received(Protocol.verifiedFields(String(packet.data, 0, packet.length, Charsets.US_ASCII), key)
                ?: error("Unauthenticated packet"), packet)
        }
        fun reply(payload: String, target: Received) {
            val bytes = Protocol.sign(payload, key).toByteArray(Charsets.US_ASCII)
            server.send(DatagramPacket(bytes, bytes.size, target.packet.address, target.packet.port))
        }
        fun ack(frame: Received, armed: Boolean) =
            reply("PWA1|${frame.fields[1]}|${frame.fields[2]}|${if (armed) 1 else 0}", frame)

        fun pairAndArm() {
            client.start()
            val hello = receive()
            originalNonce = hello.fields[1]
            reply("PWC1|$originalNonce|$firstSession", hello)
            val initial = receive()
            assertEquals(listOf("PW1", firstSession, "0", "0", "0", "0", "0", "0"), initial.fields)
            ack(initial, false)
            input.motion(0.2, System.nanoTime())
            input.arm()
            input.pedals(throttle = 0.6)
        }
        fun waitForProbe(): Received {
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                val packet = receive()
                if (packet.fields[0] == "PWH1") return packet
                assertEquals("1", packet.fields[7])
            }
            error("No receiver-restart probe arrived")
        }
        override fun close() { client.close(); assertTrue(client.awaitStopped(1500)); server.close() }
    }

    @Test fun `restart challenge adopts a new neutral session and delayed old challenges cannot switch it`() {
        Connection().use { connection ->
            connection.pairAndArm()
            val probe = connection.waitForProbe()
            // A reply bound to an earlier nonce is ignored, even when its HMAC is valid.
            connection.reply("PWC1|${connection.originalNonce}|$secondSession", probe)
            val stillActive = connection.receive()
            assertEquals(firstSession, stillActive.fields[1])
            assertEquals("1", stillActive.fields[7])

            connection.reply("PWC1|${probe.fields[1]}|$secondSession", probe)
            var neutral: Received
            do { neutral = connection.receive() } while (neutral.fields[0] != "PW1" || neutral.fields[1] != secondSession)
            assertEquals(listOf("PW1", secondSession, "0", "0", "0", "0", "0", "0"), neutral.fields)
            assertFalse(connection.input.snapshot(System.nanoTime()).armed)
            assertEquals(DisarmReason.CONNECTION_LOST, connection.input.disarmReason)
            connection.ack(neutral, false)
            repeat(8) {
                val frame = connection.receive()
                assertEquals(secondSession, frame.fields[1])
                assertEquals("Reconnection requires an explicit Arm", "0", frame.fields[7])
                connection.ack(frame, false)
            }
            assertTrue(connection.statuses.last().connected)
            assertFalse(connection.statuses.last().repliesDelayed)

            connection.input.arm()
            connection.input.pedals(throttle = 0.7)
            connection.reply("PWC1|${probe.fields[1]}|$firstSession", probe)
            connection.reply("PWC1|${probe.fields[1]}|$secondSession", probe)
            repeat(8) {
                val frame = connection.receive()
                assertEquals("Old or duplicate challenges cannot change the adopted session", secondSession, frame.fields[1])
                assertEquals("1", frame.fields[7])
                connection.ack(frame, true)
            }
        }
    }

    @Test fun `fresh ACK clears delayed state and cancels an in flight probe challenge`() {
        Connection().use { connection ->
            connection.pairAndArm()
            val probe = connection.waitForProbe()
            val current = connection.receive()
            assertEquals("PW1", current.fields[0])
            connection.ack(current, true)
            repeat(8) {
                val frame = connection.receive()
                assertEquals(firstSession, frame.fields[1])
                assertEquals("1", frame.fields[7])
                connection.ack(frame, true)
            }
            assertFalse(connection.statuses.last().repliesDelayed)
            connection.reply("PWC1|${probe.fields[1]}|$secondSession", probe)
            repeat(8) {
                val frame = connection.receive()
                assertEquals("Canceled probe replies are ignored", firstSession, frame.fields[1])
                assertEquals("1", frame.fields[7])
                connection.ack(frame, true)
            }
            assertTrue(connection.input.snapshot(System.nanoTime()).armed)
        }
    }

    @Test fun `fresh receiver disarm ACK still releases pedals and requires explicit rearm`() {
        Connection().use { connection ->
            connection.pairAndArm()
            var armed: Received
            do { armed = connection.receive() } while (armed.fields[7] != "1")
            connection.ack(armed, true)
            val next = connection.receive()
            // ReceiverState sends armed=0 after its own input watchdog latches off.
            connection.ack(next, false)
            var neutral: Received
            do { neutral = connection.receive() } while (neutral.fields[7] != "0")
            assertEquals(listOf("0", "0", "0", "0", "0"), neutral.fields.drop(3))
            assertEquals(DisarmReason.MAC_DISABLED, connection.input.disarmReason)
            repeat(8) {
                connection.ack(neutral, false)
                neutral = connection.receive()
                assertEquals("0", neutral.fields[7])
            }
        }
    }
}
