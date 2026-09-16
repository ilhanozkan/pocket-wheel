import Foundation
import Testing
@testable import PocketWheelCore

@Suite struct ProtocolTests {
    private let key = "0123456789abcdef"
    private let nonce = "aabbccddeeff0011"
    private let session = "1122334455667788"
    private let phone = Endpoint(address: "192.168.1.20", port: 40200)

    private func receiver(enabled: Bool = true) -> ReceiverState {
        let state = ReceiverState(key: key, makeSession: { self.session })
        state.setEnabled(enabled)
        _ = state.receive(packet("PWH1|\(nonce)"), from: phone, at: 0)
        return state
    }
    private func packet(_ payload: String) -> Data { Data(Wire.sign(payload, key: key).utf8) }
    @discardableResult private func frame(_ state: ReceiverState, seq: Int, at time: Double, armed: Bool,
                                        steering: Int = 7000, throttle: Int = 6000, brake: Int = 3000,
                                        buttons: Int = 9, source: Endpoint? = nil) -> String? {
        state.receive(packet("PW1|\(session)|\(seq)|\(steering)|\(throttle)|\(brake)|\(buttons)|\(armed ? 1 : 0)"),
                      from: source ?? phone, at: time)
    }

    @Test func testHMACUsesASCIIKeyAndMatchesKnownPythonVector() {
        expectEqual(Wire.sign("PWH1|aabbccddeeff0011", key: key),
                       "PWH1|aabbccddeeff0011|d5f7393742275b87a8ea2fdd50ca5e7c39c53b5a251827cdc5b01b651213bc49")
    }

    @Test func testHandshakeRetryDoesNotResetSequenceOrControls() {
        let state = receiver()
        frame(state, seq: 0, at: 0.01, armed: false)
        frame(state, seq: 1, at: 0.02, armed: true)
        let challenge = state.receive(packet("PWH1|\(nonce)"), from: phone, at: 0.03)
        expectEqual(challenge, Wire.sign("PWC1|\(nonce)|\(session)", key: key))
        expectEqual(state.lastSequence, 1)
        expectTrue(state.output(at: 0.03).armed)
    }

    @Test func testFreshSessionNeedsDisarmAndDesktopPermission() {
        let state = receiver(enabled: false)
        frame(state, seq: 0, at: 0.01, armed: true)
        expectEqual(state.output(at: 0.01), .neutral)
        frame(state, seq: 1, at: 0.02, armed: false)
        frame(state, seq: 2, at: 0.03, armed: true)
        expectEqual(state.output(at: 0.03), .neutral)
        state.setEnabled(true)
        frame(state, seq: 3, at: 0.04, armed: true)
        expectEqual(state.output(at: 0.04), .neutral)
        frame(state, seq: 4, at: 0.05, armed: false)
        let ack = frame(state, seq: 5, at: 0.06, armed: true)
        expectTrue(state.output(at: 0.06).armed)
        expectEqual(ack, Wire.sign("PWA1|\(session)|5|1", key: key))
    }

    @Test func testTimeoutDropsPedalsAndButtonsAndCentersWithinTwoHundredMilliseconds() {
        let state = receiver()
        frame(state, seq: 0, at: 0, armed: false)
        frame(state, seq: 1, at: 0.1, armed: true, steering: -10000)
        let middle = state.output(at: 0.5)
        expectEqual(middle.steering, -5000)
        expectEqual(middle.throttle, 0); expectEqual(middle.brake, 0)
        expectEqual(middle.buttons, 0); expectFalse(middle.armed)
        expectEqual(state.output(at: 0.61), .neutral)
        frame(state, seq: 2, at: 0.62, armed: true)
        expectEqual(state.output(at: 0.62), .neutral)
        frame(state, seq: 3, at: 0.63, armed: false)
        frame(state, seq: 4, at: 0.64, armed: true)
        expectTrue(state.output(at: 0.64).armed)
    }

    @Test func testLateFrameCannotAvoidWatchdogByArrivingBeforeTimer() {
        let state = receiver()
        frame(state, seq: 0, at: 0, armed: false)
        frame(state, seq: 1, at: 0.01, armed: true)
        frame(state, seq: 2, at: 0.5, armed: true)
        expectEqual(state.output(at: 0.5), .neutral)
    }

    @Test func testTamperingReplayWrongEndpointAndInvalidRangeDoNotRefreshWatchdog() {
        let state = receiver()
        frame(state, seq: 0, at: 0, armed: false)
        frame(state, seq: 1, at: 0.01, armed: true)
        expectNil(frame(state, seq: 1, at: 0.1, armed: true))
        expectNil(frame(state, seq: 2, at: 0.15, armed: true, source: Endpoint(address: phone.address, port: 40201)))
        expectNil(frame(state, seq: 2, at: 0.2, armed: true, throttle: 10001))
        let tampered = Wire.sign("PW1|\(session)|2|0|0|0|0|0", key: key).replacingOccurrences(of: "|2|", with: "|3|")
        expectNil(state.receive(Data(tampered.utf8), from: phone, at: 0.25))
        expectEqual(state.lastAcceptedTime, 0.01)
        expectFalse(state.output(at: 0.32).armed)
        expectEqual(state.rejectedPackets, 4)
    }

    @Test func testMalformedAuthenticatedFramesAreRejected() {
        let state = receiver()
        let bodies = [
            "PW1|\(session)|0|0|0|0|0|0|extra",
            "PW1|\(session)|+0|0|0|0|0|0",
            "PW1|\(session)|2147483648|0|0|0|0|0",
            "PW1|\(session)|0|0|0|0|64|0",
            "PW1|\(session)|0|0|0|0|0|2",
            "PW1|\(session)|0|0|0|0||0",
            "PW1|\(session)|0|0|0|0|0|0\n"
        ]
        for body in bodies { expectNil(state.receive(packet(body), from: phone, at: 0.1), body) }
        expectNil(state.lastAcceptedTime)
    }

    @Test func testCompetingHandshakeCannotStealActiveSession() {
        let state = receiver()
        let another = Endpoint(address: "192.168.1.99", port: 40200)
        expectNil(state.receive(packet("PWH1|fedcba9876543210"), from: another, at: 0.2))
        expectNil(state.receive(packet("PWH1|fedcba9876543210"), from: another, at: 0.8))
        expectEqual(state.endpoint, phone)
    }

    @Test func testSameHostCanRehandshakeAfterTimeoutButNotWhileLive() {
        let state = receiver()
        let reconnect = Endpoint(address: phone.address, port: 50000)
        expectNil(state.receive(packet("PWH1|fedcba9876543210"), from: reconnect, at: 0.2))
        expectNotNil(state.receive(packet("PWH1|fedcba9876543210"), from: reconnect, at: 0.5))
        expectEqual(state.endpoint, reconnect)
        expectEqual(state.output(at: 0.5), .neutral)
        expectEqual(state.lastSequence, -1)
    }

    @Test func testDisarmAndDisableReleaseEveryControlImmediately() {
        let state = receiver()
        frame(state, seq: 0, at: 0, armed: false)
        frame(state, seq: 1, at: 0.01, armed: true)
        frame(state, seq: 2, at: 0.02, armed: false)
        expectEqual(state.output(at: 0.02), .neutral)
        frame(state, seq: 3, at: 0.03, armed: true)
        state.setEnabled(false)
        expectEqual(state.output(at: 0.03), .neutral)
    }

    @Test func testPreviousSessionPacketIsRejectedAfterRestart() {
        let state = ReceiverState(key: key, makeSession: { "abcdef0123456789" })
        _ = state.receive(packet("PWH1|\(nonce)"), from: phone, at: 0)
        expectNil(frame(state, seq: 1, at: 0.1, armed: false))
        expectNil(state.lastAcceptedTime)
    }

    @Test func testLocalIPCEncodingAndPacketBound() {
        expectEqual(Wire.localPacket(session: session, sequence: 12,
                                       controls: Controls(steering: -2500, throttle: 10000, brake: 0, buttons: 32, armed: true)),
                       "PWL1|1122334455667788|12|-2500|10000|0|32|1")
        expectNil(Wire.authenticatedFields(packet(String(repeating: "a", count: 500)), key: key))
    }
}

private func expectEqual<T: Equatable>(_ lhs: T, _ rhs: T, _ message: String = "", sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(lhs == rhs, Comment(rawValue: message), sourceLocation: sourceLocation)
}
private func expectTrue(_ value: Bool, sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(value, sourceLocation: sourceLocation)
}
private func expectFalse(_ value: Bool, sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(!value, sourceLocation: sourceLocation)
}
private func expectNil<T>(_ value: T?, _ message: String = "", sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(value == nil, Comment(rawValue: message), sourceLocation: sourceLocation)
}
private func expectNotNil<T>(_ value: T?, sourceLocation: SourceLocation = #_sourceLocation) {
    #expect(value != nil, sourceLocation: sourceLocation)
}
