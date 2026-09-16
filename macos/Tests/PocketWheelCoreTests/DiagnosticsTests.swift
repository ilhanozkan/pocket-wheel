import Foundation
import Testing
@testable import PocketWheelCore

@Suite struct DiagnosticsTests {
    @Test func requestedInputSurvivesPermissionGatingForDiagnostics() {
        let key = "0123456789abcdef"
        let session = "1122334455667788"
        let state = ReceiverState(key: key, makeSession: { session })
        let endpoint = Endpoint(address: "127.0.0.1", port: 45678)
        _ = state.receive(Data(Wire.sign("PWH1|aabbccddeeff0011", key: key).utf8), from: endpoint, at: 1)
        _ = state.receive(Data(Wire.sign("PW1|\(session)|0|0|0|0|0|0", key: key).utf8), from: endpoint, at: 1.01)
        _ = state.receive(Data(Wire.sign("PW1|\(session)|1|2500|7000|3000|4|1", key: key).utf8), from: endpoint, at: 1.02)
        #expect(state.lastRequestedControls == Controls(steering: 2500, throttle: 7000, brake: 3000, buttons: 4, armed: true))
        #expect(state.output(at: 1.02) == .neutral)
        #expect(state.enabled == false)
    }

    @Test func rejectedTrafficStillCountsAsRawArrivals() {
        var diagnostics = DiagnosticsSnapshot()
        diagnostics.receivedDatagram(at: DiagnosticMoment(monotonicSeconds: 1, unixSeconds: 100))
        diagnostics.receivedDatagram(at: DiagnosticMoment(monotonicSeconds: 1.02, unixSeconds: 100.02))
        diagnostics.rejectedPackets = 2
        #expect(diagnostics.rawDatagrams == 2)
        #expect(diagnostics.acceptedFrames == 0)
        #expect(diagnostics.maximumRawDatagramGapMilliseconds < 21)
    }

    @Test func successfulSendDoesNotEraseEvidenceOfEarlierFailure() {
        var sends = SendDiagnostics()
        let first = DiagnosticMoment(monotonicSeconds: 1, unixSeconds: 100)
        sends.record(returnBytes: -1, expectedBytes: 80, error: 65, sequence: 12, at: first)
        sends.record(returnBytes: 80, expectedBytes: 80, error: nil, sequence: 13,
                     at: DiagnosticMoment(monotonicSeconds: 1.1, unixSeconds: 100.1))
        #expect(sends.attempts == 2)
        #expect(sends.successes == 1)
        #expect(sends.failures == 1)
        #expect(sends.lastErrno == nil)
        #expect(sends.lastFailureErrno == 65)
        #expect(sends.lastFailure?.monotonicSeconds == 1)
        #expect(sends.lastSequence == 13)
    }

    @Test func exportCoalescesLatestStateAndKeepsPrivatePermissions() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("pocket-wheel-diagnostics-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: folder) }
        let destination = folder.appendingPathComponent("diagnostics.json")
        let writer = DiagnosticsWriter(destination: destination)
        for index in 0..<1000 {
            var snapshot = DiagnosticsSnapshot()
            snapshot.rawDatagrams = index
            writer.submit(snapshot)
        }
        for _ in 0..<40 {
            if FileManager.default.fileExists(atPath: destination.path) { break }
            try await Task.sleep(for: .milliseconds(25))
        }
        let data = try Data(contentsOf: destination)
        let snapshot = try JSONDecoder().decode(DiagnosticsSnapshot.self, from: data)
        #expect(snapshot.rawDatagrams == 999)
        let attributes = try FileManager.default.attributesOfItem(atPath: destination.path)
        #expect((attributes[.posixPermissions] as? NSNumber)?.intValue == 0o600)
        let content = String(decoding: data, as: UTF8.self)
        for excluded in ["pairingKey", "session", "HMAC", "PWA1", "PWL1"] {
            #expect(!content.contains(excluded))
        }
        #expect(data.count < 10_000)
    }
}
