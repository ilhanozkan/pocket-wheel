package dev.pocketwheel.core

/** Bounded, payload-free measurements. All mutable counters belong to the UDP worker. */
data class TransportSnapshot(
    val capturedAtNanos: Long = 0L,
    val sentDatagrams: Long = 0,
    val sentControlFrames: Long = 0,
    val receivedDatagrams: Long = 0,
    val authenticatedReplies: Long = 0,
    val acceptedAcknowledgements: Long = 0,
    val acceptedChallenges: Long = 0,
    val discardedReplies: Long = 0,
    val sendErrors: Long = 0,
    val rawReplyAgeMs: Long? = null,
    val validAckAgeMs: Long? = null,
    val maximumWorkerGapMs: Long = 0,
    val maximumSendDurationMs: Long = 0,
)

data class TimeoutDiagnostic(val replyWaitMs: Long, val transport: TransportSnapshot)

data class ConnectionDiagnostics(
    val current: TransportSnapshot = TransportSnapshot(),
    val lastTimeout: TimeoutDiagnostic? = null,
)

internal class TransportDiagnostics {
    private var sentDatagrams = 0L
    private var sentControlFrames = 0L
    private var receivedDatagrams = 0L
    private var authenticatedReplies = 0L
    private var acceptedAcknowledgements = 0L
    private var acceptedChallenges = 0L
    private var discardedReplies = 0L
    private var sendErrors = 0L
    private var maximumWorkerGapMs = 0L
    private var maximumSendDurationMs = 0L
    private var previousLoopNanos: Long? = null
    private var lastRawReplyNanos: Long? = null
    private var lastValidAckNanos: Long? = null
    private var lastTimeout: TimeoutDiagnostic? = null

    fun loop(now: Long) {
        previousLoopNanos?.let {
            maximumWorkerGapMs = maxOf(maximumWorkerGapMs, milliseconds(now - it))
        }
        previousLoopNanos = now
    }

    fun sent(controlFrame: Boolean, successful: Boolean, durationNanos: Long) {
        if (successful) {
            sentDatagrams++
            if (controlFrame) sentControlFrames++
        } else sendErrors++
        maximumSendDurationMs = maxOf(maximumSendDurationMs, milliseconds(durationNanos))
    }

    fun received(now: Long) {
        lastRawReplyNanos = now
        receivedDatagrams++
    }

    fun authenticated() { authenticatedReplies++ }
    fun discarded() { discardedReplies++ }
    fun challengeAccepted() { acceptedChallenges++ }
    fun acknowledgementAccepted(now: Long) {
        lastValidAckNanos = now
        acceptedAcknowledgements++
    }

    fun timeout(now: Long, replyWaitNanos: Long) {
        lastTimeout = TimeoutDiagnostic(milliseconds(replyWaitNanos), snapshot(now))
    }

    fun snapshot(now: Long): TransportSnapshot = TransportSnapshot(
        capturedAtNanos = now,
        sentDatagrams = sentDatagrams, sentControlFrames = sentControlFrames,
        receivedDatagrams = receivedDatagrams, authenticatedReplies = authenticatedReplies,
        acceptedAcknowledgements = acceptedAcknowledgements, acceptedChallenges = acceptedChallenges,
        discardedReplies = discardedReplies, sendErrors = sendErrors,
        maximumWorkerGapMs = maximumWorkerGapMs, maximumSendDurationMs = maximumSendDurationMs,
        rawReplyAgeMs = lastRawReplyNanos?.let { milliseconds(now - it) },
        validAckAgeMs = lastValidAckNanos?.let { milliseconds(now - it) },
    )

    fun status(now: Long) = ConnectionDiagnostics(snapshot(now), lastTimeout)
    private fun milliseconds(nanos: Long): Long = nanos.coerceAtLeast(0L) / 1_000_000L
}

/** Only measurements enter this report; addresses, keys, session IDs and payloads cannot appear. */
object TransportReport {
    fun timeout(diagnostic: TimeoutDiagnostic): String =
        "Mac reply wait: ${diagnostic.replyWaitMs} ms\n" + snapshot(diagnostic.transport)

    fun snapshot(snapshot: TransportSnapshot): String {
        fun age(value: Long?) = value?.let { "$it ms earlier" } ?: "none received"
        return "Last received packet: ${age(snapshot.rawReplyAgeMs)}\n" +
            "Last valid ACK: ${age(snapshot.validAckAgeMs)}\n" +
            "Longest worker pause: ${snapshot.maximumWorkerGapMs} ms\n" +
            "Longest send call: ${snapshot.maximumSendDurationMs} ms\n" +
            "Sent: ${snapshot.sentDatagrams} (${snapshot.sentControlFrames} controls)\n" +
            "Received: ${snapshot.receivedDatagrams}\n" +
            "Authenticated: ${snapshot.authenticatedReplies}\n" +
            "Accepted: ${snapshot.acceptedAcknowledgements} ACKs, ${snapshot.acceptedChallenges} pairing replies\n" +
            "Discarded: ${snapshot.discardedReplies} · Send errors: ${snapshot.sendErrors}"
    }
}
