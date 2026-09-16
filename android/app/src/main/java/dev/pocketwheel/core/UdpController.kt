package dev.pocketwheel.core

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ConnectionStatus(
    val connected: Boolean = false,
    val receiverArmed: Boolean = false,
    val rttMs: Int? = null,
    val message: String = "Disconnected",
    val diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    val repliesDelayed: Boolean = false,
)

/** The socket and all session state belong to one worker; Android UI never performs I/O. */
class UdpController(
    private val host: String,
    private val key: String,
    private val input: ControlState,
    private val onStatus: (ConnectionStatus) -> Unit,
    private val port: Int = Protocol.PORT,
    private val resolveAddress: (String) -> InetAddress = InetAddress::getByName,
) : Closeable {
    private val lifecycle = Any()
    private val stopped = CountDownLatch(1)
    @Volatile private var running = true
    private val worker = thread(name = "PocketWheel-UDP", isDaemon = true, start = false) { runConnection() }

    fun start() { worker.start() }
    override fun close() {
        // No DNS/socket operation or thread join runs under this lock or on the UI thread.
        // After close returns, this worker can never mutate the replacement client's input.
        synchronized(lifecycle) {
            if (running) { running = false; input.disarm() }
        }
    }

    private fun disarmIfRunning(reason: DisarmReason) = synchronized(lifecycle) { if (running) input.disarm(reason) }
    private fun currentInput(now: Long): Controls = synchronized(lifecycle) {
        if (running) input.snapshot(now) else Controls()
    }
    private fun expireStaleMotion(now: Long): Boolean = synchronized(lifecycle) {
        if (running && input.snapshot(now).armed && now - input.lastMotionNanos > 250_000_000L) {
            input.disarm(DisarmReason.MOTION_PAUSED)
            true
        } else false
    }

    /** Deterministic worker completion for host tests; never called by Android UI. */
    internal fun awaitStopped(timeoutMillis: Long): Boolean = stopped.await(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun runConnection() {
        var socket: DatagramSocket? = null
        var session: String? = null
        var sequence = 0
        var repliesDelayed = false
        val diagnostics = TransportDiagnostics()
        fun publish(connected: Boolean = false, receiverArmed: Boolean = false, rtt: Int? = null, message: String) {
            onStatus(ConnectionStatus(connected, receiverArmed, rtt, message, diagnostics.status(System.nanoTime()), repliesDelayed))
        }
        try {
            if (!running) return
            require(Protocol.validKey(key)) { "Pairing key must contain 16 hexadecimal characters." }
            val address = resolveAddress(host)
            if (!running) return
            socket = DatagramSocket().apply {
                connect(address, this@UdpController.port)
                soTimeout = 4
            }
            val channel = socket
            var transportUnavailable = false
            fun send(text: String): Boolean {
                if (!running) return false
                val bytes = text.toByteArray(Charsets.US_ASCII)
                val started = System.nanoTime()
                var successful = false
                return try {
                    channel.send(DatagramPacket(bytes, bytes.size))
                    successful = true
                    true
                } catch (_: PortUnreachableException) {
                    transportUnavailable = true
                    false
                } finally {
                    diagnostics.sent(text.startsWith("PW1|"), successful, System.nanoTime() - started)
                }
            }
            var nonce = Protocol.nonce()
            var lastHandshake = 0L
            var lastAck = 0L
            var sessionStarted = 0L
            var nextSend = 0L
            var lastStatus = 0L
            var ackSequence = -1
            var probeNonce: String? = null
            var lastProbeSent = 0L
            var firstArmedSequence: Int? = null
            var receiverArmed = false
            var connected = false
            var rtt: Int? = null
            var reason = "Pairing… check Mac IP, key, and Start receiver"
            val sentTimes = LinkedHashMap<Int, Long>()
            val buffer = ByteArray(513)
            disarmIfRunning(DisarmReason.CONNECTING)

            fun adoptSession(challenge: String, restarted: Boolean) {
                disarmIfRunning(if (restarted) DisarmReason.CONNECTION_LOST else DisarmReason.CONNECTING)
                session = challenge
                sequence = 0
                ackSequence = -1
                lastAck = 0L
                sessionStarted = System.nanoTime()
                nextSend = 0L
                firstArmedSequence = null
                receiverArmed = false
                connected = false
                rtt = null
                repliesDelayed = false
                probeNonce = null
                lastProbeSent = 0L
                sentTimes.clear()
                reason = "Paired • waiting for receiver"
                publish(message = reason)
            }

            while (running) {
                val now = System.nanoTime()
                diagnostics.loop(now)
                if (transportUnavailable) {
                    disarmIfRunning(DisarmReason.RECEIVER_UNAVAILABLE)
                    if (session != null) {
                        session = null
                        nonce = Protocol.nonce()
                        lastHandshake = 0L
                    }
                    connected = false
                    receiverArmed = false
                    repliesDelayed = false
                    probeNonce = null
                    lastProbeSent = 0L
                    firstArmedSequence = null
                    sentTimes.clear()
                    rtt = null
                    reason = "Receiver unavailable • start your Mac receiver; retrying"
                    transportUnavailable = false
                    publish(message = reason)
                }
                val activeSession = session
                if (activeSession == null) {
                    if (now - lastHandshake >= 500_000_000L) {
                        send(Protocol.handshake(nonce, key))
                        lastHandshake = now
                    }
                } else if (sequence >= Int.MAX_VALUE - 1) {
                    // Sequence exhaustion requires a new session, independently of reply liveness.
                    disarmIfRunning(DisarmReason.CONNECTION_LOST)
                    send(Protocol.frame(activeSession, sequence, Controls(), key))
                    session = null
                    nonce = Protocol.nonce()
                    connected = false
                    receiverArmed = false
                    repliesDelayed = false
                    probeNonce = null
                    lastProbeSent = 0L
                    firstArmedSequence = null
                    lastHandshake = 0L
                    sentTimes.clear()
                    rtt = null
                    reason = "Connection reset • reconnecting; Arm again when ready"
                    publish(message = reason)
                } else {
                    val replyAge = now - (if (lastAck == 0L) sessionStarted else lastAck)
                    if (replyAge > 750_000_000L && !repliesDelayed) {
                        diagnostics.timeout(now, replyAge)
                        repliesDelayed = true
                        probeNonce = Protocol.nonce()
                        lastProbeSent = 0L
                        reason = "Mac replies delayed • still sending controls"
                        publish(connected, receiverArmed, rtt, reason)
                    }
                    if (now >= nextSend) {
                        if (expireStaleMotion(now)) {
                            reason = "Motion sensor paused • recalibrate and Arm"
                        }
                        val state = currentInput(now)
                        if (!state.armed) firstArmedSequence = null
                        else if (firstArmedSequence == null) firstArmedSequence = sequence
                        if (send(Protocol.frame(activeSession, sequence, state, key))) sentTimes[sequence] = now
                        while (sentTimes.size > 128) sentTimes.remove(sentTimes.keys.first())
                        sequence++
                        nextSend = now + 16_666_667L
                    }
                    // Probe for a restarted receiver without interrupting a healthy inbound
                    // control stream. A live receiver rejects takeover while frames arrive.
                    if (repliesDelayed && now - lastProbeSent >= 500_000_000L) {
                        probeNonce?.let { send(Protocol.handshake(it, key)) }
                        lastProbeSent = now
                    }
                }

                try {
                    // Wake near the next frame deadline instead of rounding every interval up to 20 ms.
                    channel.soTimeout = if (session == null) 4 else
                        (((nextSend - System.nanoTime()).coerceAtLeast(0L) + 999_999L) / 1_000_000L).toInt().coerceIn(1, 4)
                    val packet = DatagramPacket(buffer, buffer.size)
                    channel.receive(packet)
                    diagnostics.received(System.nanoTime())
                    if (packet.length > 512) { diagnostics.discarded(); continue }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.US_ASCII)
                    val fields = Protocol.verifiedFields(text, key)
                    if (fields != null) diagnostics.authenticated()
                    if (session == null) {
                        val challenge = fields?.let { Protocol.challengeFields(it, nonce) }
                        if (challenge != null) {
                            diagnostics.challengeAccepted()
                            adoptSession(challenge, restarted = false)
                        } else diagnostics.discarded()
                    } else {
                        val challenge = probeNonce?.let { probe -> fields?.let { Protocol.challengeFields(it, probe) } }
                        if (challenge != null && challenge != session) {
                            diagnostics.challengeAccepted()
                            adoptSession(challenge, restarted = true)
                            continue
                        }
                        val ack = fields?.let { Protocol.ackFields(it, session!!) }
                        val sent = ack?.let { sentTimes[it.sequence] }
                        if (ack != null && sent != null && ack.sequence > ackSequence) {
                            lastAck = System.nanoTime()
                            diagnostics.acknowledgementAccepted(lastAck)
                            ackSequence = ack.sequence
                            rtt = ((lastAck - sent) / 1_000_000).toInt()
                            connected = true
                            receiverArmed = ack.armed
                            repliesDelayed = false
                            probeNonce = null
                            lastProbeSent = 0L
                            if (!ack.armed && firstArmedSequence?.let { ack.sequence >= it } == true) {
                                disarmIfRunning(DisarmReason.MAC_DISABLED)
                                firstArmedSequence = null
                                reason = "Disarmed by Mac • enable controls there, then Arm"
                            } else if (ack.armed && currentInput(lastAck).armed) reason = "Controls active"
                            else if (reason.startsWith("Paired") || reason.startsWith("Connection reset") || reason.startsWith("Mac replies delayed")) reason = "Connected • calibrate, then Arm"
                        } else diagnostics.discarded()
                    }
                } catch (_: SocketTimeoutException) {
                    // Poll current inputs on the next frame; never replay a backlog.
                } catch (_: PortUnreachableException) {
                    // A stopped receiver may report ICMP errors through send OR receive.
                    // Keep the worker alive and retry pairing, always requiring a new Arm.
                    transportUnavailable = true
                }
                if (now - lastStatus >= 100_000_000L) {
                    publish(connected, receiverArmed, rtt, reason)
                    lastStatus = now
                }
            }
        } catch (error: Exception) {
            repliesDelayed = false
            disarmIfRunning(DisarmReason.NETWORK_ERROR)
            if (running) publish(message = "Network unavailable: ${error.message ?: "check Wi-Fi and pair again"}")
        } finally {
            synchronized(lifecycle) {
                if (running) { input.disarm(DisarmReason.CONNECTION_LOST); running = false }
            }
            // Best effort immediate release, with independent receiver watchdog as backup.
            if (socket != null && !socket.isClosed && session != null) {
                runCatching {
                    val bytes = Protocol.frame(session!!, sequence, Controls(), key).toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size))
                }
            }
            socket?.close()
            stopped.countDown()
        }
    }
}
