import Foundation
import Darwin

public enum BridgeDiagnostics {
    /// The GUI opts in; headless test receivers do not overwrite the running GUI's evidence.
    public static var defaultFileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Pocket Wheel", isDirectory: true)
            .appendingPathComponent("diagnostics.json")
    }
}

struct DiagnosticMoment: Codable, Sendable {
    let monotonicSeconds: TimeInterval
    let unixSeconds: TimeInterval

    init(monotonicSeconds: TimeInterval = ProcessInfo.processInfo.systemUptime,
         unixSeconds: TimeInterval = Date().timeIntervalSince1970) {
        self.monotonicSeconds = monotonicSeconds
        self.unixSeconds = unixSeconds
    }
}

struct SendDiagnostics: Codable, Sendable {
    var attempts = 0
    var successes = 0
    var failures = 0
    var lastAttempt: DiagnosticMoment?
    var lastSequence: Int?
    var lastReturnBytes: Int?
    var lastErrno: Int32?
    var lastFailure: DiagnosticMoment?
    var lastFailureErrno: Int32?

    mutating func record(returnBytes: Int, expectedBytes: Int, error: Int32?,
                         sequence: Int?, at moment: DiagnosticMoment) {
        attempts += 1
        lastAttempt = moment
        lastSequence = sequence
        lastReturnBytes = returnBytes
        lastErrno = error
        if returnBytes == expectedBytes {
            successes += 1
        } else {
            failures += 1
            lastFailure = moment
            lastFailureErrno = error
        }
    }
}

struct AcceptedInputDiagnostics: Codable, Sendable {
    let sequence: Int
    let receivedAt: DiagnosticMoment
    let requested: Controls
    let effectiveAtAcceptance: Controls
}

/// Only selected numeric control and transport state belongs here: no packet strings or auth material.
struct DiagnosticsSnapshot: Codable, Sendable {
    var schemaVersion = 1
    var updatedAt = DiagnosticMoment()
    var running = false
    var enabled = false
    var connected = false
    var requiresDisarmFrame = true
    // Private route-inspection data, persisted only in the user's 0600 diagnostics file.
    var phoneEndpoint: Endpoint?
    var rawDatagrams = 0
    var acceptedFrames = 0
    var rejectedPackets = 0
    var receiveErrors = 0
    var lastReceiveErrno: Int32?
    var lastRawDatagram: DiagnosticMoment?
    var lastAcceptedInput: AcceptedInputDiagnostics?
    var effectiveOutput = Controls.neutral
    var lastOutputTick: DiagnosticMoment?
    var maximumRawDatagramGapMilliseconds = 0.0
    var maximumAcceptedFrameGapMilliseconds = 0.0
    var maximumOutputTickGapMilliseconds = 0.0
    var acknowledgements = SendDiagnostics()
    var challenges = SendDiagnostics()
    var loopback = SendDiagnostics()

    mutating func receivedDatagram(at moment: DiagnosticMoment) {
        if let previous = lastRawDatagram {
            maximumRawDatagramGapMilliseconds = max(maximumRawDatagramGapMilliseconds,
                (moment.monotonicSeconds - previous.monotonicSeconds) * 1000)
        }
        rawDatagrams += 1
        lastRawDatagram = moment
    }
}

/// Bounded latest-only export. Disk work never runs on the receiver or main queue.
final class DiagnosticsWriter {
    private let destination: URL
    private let queue = DispatchQueue(label: "app.pocketwheel.diagnostics", qos: .utility)
    private let lock = NSLock()
    private var pending: DiagnosticsSnapshot?
    private var scheduled = false

    init(destination: URL) { self.destination = destination }

    func submit(_ snapshot: DiagnosticsSnapshot) {
        lock.lock()
        pending = snapshot
        let mustSchedule = !scheduled
        scheduled = true
        lock.unlock()
        if mustSchedule { scheduleWrite() }
    }

    private func scheduleWrite() {
        queue.asyncAfter(deadline: .now() + .milliseconds(200)) { [self] in
            lock.lock()
            let snapshot = pending
            pending = nil
            lock.unlock()
            if let snapshot { try? write(snapshot) }
            lock.lock()
            let again = pending != nil
            if !again { scheduled = false }
            lock.unlock()
            if again { scheduleWrite() }
        }
    }

    private func write(_ snapshot: DiagnosticsSnapshot) throws {
        let directory = destination.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let bytes = try encoder.encode(snapshot)
        let temporary = directory.appendingPathComponent(".diagnostics-\(UUID().uuidString).tmp")
        let descriptor = Darwin.open(temporary.path, O_WRONLY | O_CREAT | O_EXCL, mode_t(0o600))
        guard descriptor >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
        defer {
            Darwin.close(descriptor)
            Darwin.unlink(temporary.path)
        }
        try bytes.withUnsafeBytes { buffer in
            var offset = 0
            while offset < bytes.count {
                let written = Darwin.write(descriptor, buffer.baseAddress!.advanced(by: offset), bytes.count - offset)
                if written < 0 && errno == EINTR { continue }
                guard written > 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
                offset += written
            }
        }
        guard Darwin.rename(temporary.path, destination.path) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
    }
}
