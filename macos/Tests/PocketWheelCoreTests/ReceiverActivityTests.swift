import Foundation
import Testing
@testable import PocketWheelCore

@Suite struct ReceiverActivityTests {
    @Test func assertionIsBalancedAcrossRepeatedStartStopAndRelease() {
        var starts = 0
        var ends = 0
        var activity: ReceiverActivity? = ReceiverActivity(begin: {
            starts += 1
            return NSObject()
        }, end: { _ in ends += 1 })
        activity?.start()
        activity?.start()
        #expect(starts == 1)
        activity?.stop()
        activity?.stop()
        #expect(ends == 1)
        activity?.start()
        #expect(starts == 2)
        activity = nil
        #expect(ends == 2)
    }

    @Test func missingInputDoesNotLookLikeAnOutputQueueStall() {
        var timing = BridgeTiming()
        timing.acceptedFrame(at: 0)
        for index in 0...60 { timing.tick(at: Double(index) / 60) }
        timing.acceptedFrame(at: 1)
        #expect(timing.maximumReceiveGapMilliseconds == 1000)
        #expect(timing.maximumTickGapMilliseconds < 17)
    }

    @Test func queueStallIsVisibleInBothMeasurements() {
        var timing = BridgeTiming()
        timing.acceptedFrame(at: 1)
        timing.tick(at: 1)
        timing.acceptedFrame(at: 2)
        timing.tick(at: 2)
        #expect(timing.maximumReceiveGapMilliseconds == 1000)
        #expect(timing.maximumTickGapMilliseconds == 1000)
        timing = BridgeTiming()
        #expect(timing.maximumReceiveGapMilliseconds == 0)
        #expect(timing.maximumTickGapMilliseconds == 0)
    }
}
