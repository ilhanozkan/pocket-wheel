import Foundation
import CryptoKit

public struct Controls: Equatable, Sendable, Codable {
    public var steering: Int
    public var throttle: Int
    public var brake: Int
    public var buttons: Int
    public var armed: Bool

    public init(steering: Int = 0, throttle: Int = 0, brake: Int = 0,
                buttons: Int = 0, armed: Bool = false) {
        self.steering = steering; self.throttle = throttle; self.brake = brake
        self.buttons = buttons; self.armed = armed
    }
    public static let neutral = Controls()
}

public struct Endpoint: Equatable, Sendable, Codable {
    public let address: String
    public let port: UInt16
    public init(address: String, port: UInt16) { self.address = address; self.port = port }
}

public enum Wire {
    public static let maximumPacketSize = 512
    public static func isIdentifier(_ value: String) -> Bool {
        value.utf8.count == 16 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
    public static func randomIdentifier() -> String {
        var generator = SystemRandomNumberGenerator()
        return (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255, using: &generator)) }.joined()
    }
    public static func sign(_ payload: String, key: String) -> String {
        let digest = HMAC<SHA256>.authenticationCode(for: Data(payload.utf8), using: SymmetricKey(data: Data(key.utf8)))
        return payload + "|" + digest.map { String(format: "%02x", $0) }.joined()
    }
    /// CryptoKit compares the authentication code in constant time.
    public static func authenticatedFields(_ data: Data, key: String) -> [String]? {
        guard !data.isEmpty, data.count <= maximumPacketSize,
              data.allSatisfy({ $0 >= 33 && $0 <= 126 }),
              let text = String(data: data, encoding: .ascii), let separator = text.lastIndex(of: "|") else { return nil }
        let tag = String(text[text.index(after: separator)...])
        guard tag.utf8.count == 64, tag.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else { return nil }
        var bytes = [UInt8]()
        let chars = Array(tag.utf8)
        for index in stride(from: 0, to: 64, by: 2) {
            func nibble(_ c: UInt8) -> UInt8 { c <= 57 ? c - 48 : c - 87 }
            bytes.append(nibble(chars[index]) << 4 | nibble(chars[index + 1]))
        }
        let payload = String(text[..<separator])
        guard HMAC<SHA256>.isValidAuthenticationCode(bytes, authenticating: Data(payload.utf8),
                                                    using: SymmetricKey(data: Data(key.utf8))) else { return nil }
        return payload.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
    }
    static func integer(_ text: String, range: ClosedRange<Int>) -> Int? {
        var digits = text[...]
        if digits.first == "-" { digits.removeFirst() }
        guard !digits.isEmpty, digits.utf8.allSatisfy({ (48...57).contains($0) }),
              let value = Int(text), range.contains(value) else { return nil }
        return value
    }
    public static func localPacket(session: String, sequence: Int, controls: Controls) -> String {
        "PWL1|\(session)|\(sequence)|\(controls.steering)|\(controls.throttle)|\(controls.brake)|\(controls.buttons)|\(controls.armed ? 1 : 0)"
    }
}

/// Pure protocol state machine. The network service confines this object to one serial queue.
public final class ReceiverState {
    public let key: String
    public private(set) var session: String?
    public private(set) var endpoint: Endpoint?
    public private(set) var lastSequence = -1
    public private(set) var lastAcceptedTime: TimeInterval?
    public private(set) var acceptedFrames = 0
    public private(set) var rejectedPackets = 0
    public private(set) var enabled = false
    public private(set) var lastRequestedControls: Controls?
    public var requiresDisarmFrame: Bool { !sawDisarm }
    private var clientNonce: String?
    private var sessionStarted = 0.0
    private var sawDisarm = false
    private var controls = Controls.neutral
    private var timeoutLatched = false
    private let makeSession: () -> String

    public init(key: String, makeSession: @escaping () -> String = Wire.randomIdentifier) {
        precondition(Wire.isIdentifier(key), "Pairing key must contain 16 lowercase hex characters")
        self.key = key; self.makeSession = makeSession
    }

    public func setEnabled(_ value: Bool) {
        if value != enabled {
            controls = .neutral
            sawDisarm = false
        }
        enabled = value
    }

    public func isConnected(at now: TimeInterval) -> Bool {
        guard let lastAcceptedTime else { return false }
        return now - lastAcceptedTime <= 0.3
    }

    /// Invalid packets never refresh the watchdog. Every valid frame receives an authenticated ACK.
    public func receive(_ data: Data, from source: Endpoint, at now: TimeInterval) -> String? {
        _ = output(at: now)
        guard let fields = Wire.authenticatedFields(data, key: key) else { rejectedPackets += 1; return nil }
        if fields.count == 2, fields[0] == "PWH1", Wire.isIdentifier(fields[1]) {
            let nonce = fields[1]
            if let session, source == endpoint, nonce == clientNonce {
                return Wire.sign("PWC1|\(nonce)|\(session)", key: key)
            }
            if let endpoint {
                let lastActivity = lastAcceptedTime ?? sessionStarted
                guard now - lastActivity > 0.3, source.address == endpoint.address else {
                    rejectedPackets += 1; return nil
                }
            }
            session = makeSession(); endpoint = source; clientNonce = nonce
            sessionStarted = now; lastAcceptedTime = nil; lastSequence = -1
            controls = .neutral; sawDisarm = false; timeoutLatched = false
            lastRequestedControls = nil
            return Wire.sign("PWC1|\(nonce)|\(session!)", key: key)
        }
        return receiveFrame(fields, source: source, at: now)
    }

    private func receiveFrame(_ fields: [String], source: Endpoint, at now: TimeInterval) -> String? {
        guard fields.count == 8, fields[0] == "PW1", fields[1] == session, source == endpoint,
              let sequence = Wire.integer(fields[2], range: 0...Int(Int32.max)), sequence > lastSequence,
              let steering = Wire.integer(fields[3], range: -10000...10000),
              let throttle = Wire.integer(fields[4], range: 0...10000),
              let brake = Wire.integer(fields[5], range: 0...10000),
              let buttons = Wire.integer(fields[6], range: 0...63),
              let requestedArm = Wire.integer(fields[7], range: 0...1) else {
            rejectedPackets += 1; return nil
        }
        lastSequence = sequence; lastAcceptedTime = now; acceptedFrames += 1
        timeoutLatched = false
        if requestedArm == 0 { sawDisarm = true }
        let effectiveArm = enabled && sawDisarm && requestedArm == 1
        lastRequestedControls = Controls(steering: steering, throttle: throttle, brake: brake,
                                         buttons: buttons, armed: requestedArm == 1)
        controls = effectiveArm ? Controls(steering: steering, throttle: throttle, brake: brake,
                                          buttons: buttons, armed: true) : .neutral
        return Wire.sign("PWA1|\(session!)|\(sequence)|\(effectiveArm ? 1 : 0)", key: key)
    }

    public func output(at now: TimeInterval) -> Controls {
        guard let lastAcceptedTime else { return .neutral }
        let age = now - lastAcceptedTime
        if age > 0.3 {
            if !timeoutLatched { timeoutLatched = true; sawDisarm = false }
            let factor = max(0, 1 - (age - 0.3) / 0.2)
            return Controls(steering: Int((Double(controls.steering) * factor).rounded()))
        }
        return controls
    }
}
