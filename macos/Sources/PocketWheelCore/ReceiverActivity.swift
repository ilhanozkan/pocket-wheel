import Foundation

/// A running controller must keep receiving and forwarding input when the game covers its window.
/// Pair the assertion with receiver lifetime; system sleep remains allowed.
final class ReceiverActivity {
    private let begin: () -> NSObjectProtocol
    private let end: (NSObjectProtocol) -> Void
    private var token: NSObjectProtocol?

    init(
        begin: @escaping () -> NSObjectProtocol = {
            ProcessInfo.processInfo.beginActivity(
                options: [.userInitiatedAllowingIdleSystemSleep, .latencyCritical],
                reason: "Receive and forward Pocket Wheel driving controls")
        },
        end: @escaping (NSObjectProtocol) -> Void = { ProcessInfo.processInfo.endActivity($0) }
    ) {
        self.begin = begin
        self.end = end
    }

    func start() {
        guard token == nil else { return }
        token = begin()
    }

    func stop() {
        guard let current = token else { return }
        token = nil
        end(current)
    }

    deinit { stop() }
}

/// Compare input delivery gaps with the output timer to distinguish missing input from queue stalls.
struct BridgeTiming {
    private var lastAcceptedFrame: TimeInterval?
    private var lastTick: TimeInterval?
    private(set) var maximumReceiveGapMilliseconds = 0.0
    private(set) var maximumTickGapMilliseconds = 0.0

    mutating func acceptedFrame(at now: TimeInterval) {
        if let lastAcceptedFrame {
            maximumReceiveGapMilliseconds = max(maximumReceiveGapMilliseconds, (now - lastAcceptedFrame) * 1000)
        }
        lastAcceptedFrame = now
    }

    mutating func tick(at now: TimeInterval) {
        if let lastTick {
            maximumTickGapMilliseconds = max(maximumTickGapMilliseconds, (now - lastTick) * 1000)
        }
        lastTick = now
    }
}
