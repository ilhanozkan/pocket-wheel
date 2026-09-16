import Foundation
import Darwin

public struct BridgeSnapshot: Sendable {
    public var running = false
    public var enabled = false
    public var connected = false
    public var controls = Controls.neutral
    public var phoneAddress: String?
    public var acceptedFrames = 0
    public var rejectedPackets = 0
    public var maximumReceiveGapMilliseconds = 0.0
    public var maximumTickGapMilliseconds = 0.0
    public var error: String?
    public init() {}
}

public enum BridgeError: LocalizedError {
    case invalidKey, socket(String), bind(UInt16, String)
    public var errorDescription: String? {
        switch self {
        case .invalidKey: return "Use a pairing key of 16 lowercase hexadecimal characters."
        case .socket(let detail): return "Could not open the network receiver: \(detail). Stop other copies and try again."
        case .bind(let port, let detail): return "UDP port \(port) is unavailable: \(detail). Stop the other receiver and try again."
        }
    }
}

/// All socket I/O and protocol state run off the main thread on a single serial queue.
public final class UDPBridge {
    private let queue = DispatchQueue(label: "app.pocketwheel.receiver", qos: .userInteractive)
    private var socketFD: Int32 = -1
    private var readSource: DispatchSourceRead?
    private var timer: DispatchSourceTimer?
    private var state: ReceiverState?
    private let receiverActivity = ReceiverActivity()
    private var timing = BridgeTiming()
    private var diagnostics = DiagnosticsSnapshot()
    private let diagnosticsWriter: DiagnosticsWriter?
    private var bridgeSession = Wire.randomIdentifier()
    private var outputSequence = 0
    private var snapshotTick = 0
    private var localDestination = sockaddr_in()
    private let onSnapshot: (BridgeSnapshot) -> Void

    public init(diagnosticsURL: URL? = nil, onSnapshot: @escaping (BridgeSnapshot) -> Void) {
        self.onSnapshot = onSnapshot
        diagnosticsWriter = diagnosticsURL.map { DiagnosticsWriter(destination: $0) }
        localDestination.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        localDestination.sin_family = sa_family_t(AF_INET)
        localDestination.sin_port = UInt16(26761).bigEndian
        localDestination.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
    }

    public func start(key: String, port: UInt16 = 26760, enabled: Bool = false) {
        queue.async { [weak self] in self?.startOnQueue(key: key, port: port, enabled: enabled) }
    }

    public func setEnabled(_ enabled: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            self.state?.setEnabled(enabled)
            self.publish()
        }
    }

    public func stop() { queue.async { [weak self] in self?.stopOnQueue() } }

    /// Used during application termination so final neutral state is emitted before exit.
    public func stopAndWait() { queue.sync { stopOnQueue() } }

    private func startOnQueue(key: String, port: UInt16, enabled: Bool) {
        stopOnQueue(publish: false)
        timing = BridgeTiming()
        diagnostics = DiagnosticsSnapshot()
        guard Wire.isIdentifier(key) else { publish(error: BridgeError.invalidKey.localizedDescription); return }
        let descriptor = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard descriptor >= 0 else { publish(error: BridgeError.socket(String(cString: strerror(errno))).localizedDescription); return }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr = in_addr(s_addr: INADDR_ANY)
        let result = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(descriptor, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else {
            let detail = String(cString: strerror(errno)); Darwin.close(descriptor)
            publish(error: BridgeError.bind(port, detail).localizedDescription); return
        }
        guard fcntl(descriptor, F_SETFL, O_NONBLOCK) >= 0 else {
            let detail = String(cString: strerror(errno)); Darwin.close(descriptor)
            publish(error: BridgeError.socket(detail).localizedDescription); return
        }
        socketFD = descriptor
        let receiver = ReceiverState(key: key)
        receiver.setEnabled(enabled)
        state = receiver
        bridgeSession = Wire.randomIdentifier(); outputSequence = 0; snapshotTick = 0
        receiverActivity.start()
        let source = DispatchSource.makeReadSource(fileDescriptor: descriptor, queue: queue)
        source.setEventHandler { [weak self] in self?.readPendingPackets() }
        readSource = source; source.resume()
        let clock = DispatchSource.makeTimerSource(queue: queue)
        clock.schedule(deadline: .now(), repeating: .nanoseconds(16_666_667), leeway: .milliseconds(1))
        clock.setEventHandler { [weak self] in self?.tick() }
        timer = clock; clock.resume()
        publish()
    }

    private func stopOnQueue(publish shouldPublish: Bool = true) {
        if socketFD >= 0 {
            state?.setEnabled(false)
            // Repeating a neutral packet a few times protects against loss at shutdown.
            for _ in 0..<3 { sendLocal(.neutral) }
        }
        timer?.cancel(); timer = nil
        readSource?.cancel(); readSource = nil
        // Reads, cancel, and close are serialized on this queue.
        if socketFD >= 0 { Darwin.close(socketFD) }
        socketFD = -1; state = nil
        receiverActivity.stop()
        if shouldPublish { publish() }
    }

    private func readPendingPackets() {
        guard socketFD >= 0, let state else { return }
        // Bound each dispatch callback; a busy sender must not starve the 60 Hz watchdog.
        for _ in 0..<64 {
            var buffer = [UInt8](repeating: 0, count: Wire.maximumPacketSize + 1)
            var source = sockaddr_in()
            var sourceLength = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = withUnsafeMutablePointer(to: &source) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    recvfrom(socketFD, &buffer, buffer.count, 0, $0, &sourceLength)
                }
            }
            if count < 0 {
                let code = errno
                if code != EAGAIN && code != EWOULDBLOCK {
                    diagnostics.receiveErrors += 1
                    diagnostics.lastReceiveErrno = code
                }
                break
            }
            let moment = DiagnosticMoment()
            diagnostics.receivedDatagram(at: moment)
            var host = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            guard inet_ntop(AF_INET, &source.sin_addr, &host, socklen_t(host.count)) != nil else { continue }
            let endpoint = Endpoint(address: String(cString: host), port: UInt16(bigEndian: source.sin_port))
            let now = moment.monotonicSeconds
            let previousFrameCount = state.acceptedFrames
            let response = state.receive(Data(buffer.prefix(count)), from: endpoint, at: now)
            let accepted = state.acceptedFrames > previousFrameCount
            if accepted {
                timing.acceptedFrame(at: now)
                if let requested = state.lastRequestedControls {
                    diagnostics.lastAcceptedInput = AcceptedInputDiagnostics(sequence: state.lastSequence,
                        receivedAt: moment, requested: requested, effectiveAtAcceptance: state.output(at: now))
                }
            }
            if let response {
                send(response, to: &source, kind: accepted ? .acknowledgement : .challenge,
                     sequence: accepted ? state.lastSequence : nil)
            }
        }
    }

    private func tick() {
        guard let state else { return }
        let now = ProcessInfo.processInfo.systemUptime
        timing.tick(at: now)
        diagnostics.lastOutputTick = DiagnosticMoment(monotonicSeconds: now)
        sendLocal(state.output(at: now))
        snapshotTick += 1
        if snapshotTick % 6 == 0 { publish() }
    }

    private func sendLocal(_ controls: Controls) {
        diagnostics.effectiveOutput = controls
        if outputSequence == Int(Int32.max) {
            bridgeSession = Wire.randomIdentifier(); outputSequence = 0
            state?.setEnabled(false)
            var destination = localDestination
            diagnostics.effectiveOutput = .neutral
            send(Wire.localPacket(session: bridgeSession, sequence: 0, controls: .neutral),
                 to: &destination, kind: .loopback, sequence: 0)
            outputSequence = 1
            return
        }
        var destination = localDestination
        send(Wire.localPacket(session: bridgeSession, sequence: outputSequence, controls: controls),
             to: &destination, kind: .loopback, sequence: outputSequence)
        outputSequence += 1
    }

    private enum SendKind { case acknowledgement, challenge, loopback }

    private func send(_ value: String, to address: inout sockaddr_in, kind: SendKind, sequence: Int?) {
        let bytes = Array(value.utf8)
        let result = bytes.withUnsafeBytes { buffer in
            withUnsafePointer(to: &address) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(socketFD, buffer.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        let code: Int32? = result < 0 ? errno : nil
        let moment = DiagnosticMoment()
        switch kind {
        case .acknowledgement:
            diagnostics.acknowledgements.record(returnBytes: result, expectedBytes: bytes.count,
                error: code, sequence: sequence, at: moment)
        case .challenge:
            diagnostics.challenges.record(returnBytes: result, expectedBytes: bytes.count,
                error: code, sequence: sequence, at: moment)
        case .loopback:
            diagnostics.loopback.record(returnBytes: result, expectedBytes: bytes.count,
                error: code, sequence: sequence, at: moment)
        }
    }

    private func publish(error: String? = nil) {
        let now = ProcessInfo.processInfo.systemUptime
        var snapshot = BridgeSnapshot()
        snapshot.running = socketFD >= 0; snapshot.error = error
        snapshot.maximumReceiveGapMilliseconds = timing.maximumReceiveGapMilliseconds
        snapshot.maximumTickGapMilliseconds = timing.maximumTickGapMilliseconds
        if let state {
            snapshot.enabled = state.enabled; snapshot.connected = state.isConnected(at: now)
            snapshot.controls = state.output(at: now); snapshot.phoneAddress = state.endpoint?.address
            snapshot.acceptedFrames = state.acceptedFrames; snapshot.rejectedPackets = state.rejectedPackets
            diagnostics.phoneEndpoint = state.endpoint
            diagnostics.acceptedFrames = state.acceptedFrames
            diagnostics.rejectedPackets = state.rejectedPackets
        }
        diagnostics.updatedAt = DiagnosticMoment(monotonicSeconds: now)
        diagnostics.running = snapshot.running
        diagnostics.enabled = snapshot.enabled
        diagnostics.connected = snapshot.connected
        diagnostics.requiresDisarmFrame = state?.requiresDisarmFrame ?? true
        diagnostics.maximumAcceptedFrameGapMilliseconds = timing.maximumReceiveGapMilliseconds
        diagnostics.maximumOutputTickGapMilliseconds = timing.maximumTickGapMilliseconds
        diagnosticsWriter?.submit(diagnostics)
        onSnapshot(snapshot)
    }
}

public enum LocalNetwork {
    public static func ipv4Addresses() -> [String] {
        var addresses: [String] = []
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0 else { return [] }
        defer { freeifaddrs(interfaces) }
        var cursor = interfaces
        while let interface = cursor {
            defer { cursor = interface.pointee.ifa_next }
            let flags = Int32(interface.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let address = interface.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                let name = String(cString: interface.pointee.ifa_name)
                addresses.append("\(ip) (\(name))")
            }
        }
        return addresses.sorted()
    }
}
