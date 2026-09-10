import Foundation
import XCTest
@testable import StudyMate

final class VoiceTutorSessionStateTests: XCTestCase {
    private let type = "buddystudy.voice.session.state"
    private let answerID = "11111111-2222-3333-4444-555555555555"

    func testEveryServerPhaseHasAnExactTypedSnapshot() throws {
        for phase in VoiceTutorSessionStateEvent.Phase.allCases {
            let value = event(1, phase)
            XCTAssertEqual(try parse(fields(value)), .sessionState(value), phase.rawValue)
        }
    }

    func testMalformedOrUnscopedSnapshotsAreIgnored() throws {
        let valid = fields(event(1, .answering))
        for key in ["sequence", "phase", "paused", "revision", "studyId", "recordId", "answerId"] {
            var invalid = valid
            invalid.removeValue(forKey: key)
            XCTAssertEqual(try parse(invalid), .ignored(type: type), key)
        }
        let invalidValues: [(String, Any)] = [
            ("sequence", true), ("sequence", 0), ("sequence", -1), ("sequence", 1.5), ("sequence", "1"),
            ("phase", "unknown"), ("paused", 1), ("paused", "true"), ("revision", -1), ("revision", false),
            ("studyId", false), ("studyId", "42"), ("studyId", 0), ("recordId", "0101"),
            ("recordId", "9223372036854775808"), ("recordId", 101), ("answerId", "not-a-uuid"),
            ("answerId", "AAAAAAAA-2222-3333-4444-555555555555"), ("extra", "provider-authored text")
        ]
        for (key, value) in invalidValues {
            var invalid = valid
            invalid[key] = value
            XCTAssertEqual(try parse(invalid), .ignored(type: type), "\(key): \(value)")
        }
        for key in ["studyId", "recordId", "answerId"] {
            var invalid = fields(event(1, .conversation, scoped: false))
            invalid[key] = NSNull()
            XCTAssertEqual(try parse(invalid), .ignored(type: type))
        }
    }

    func testOutOfOrderSnapshotCannotRewindReviewOrResumeTheCall() {
        var state = VoiceTutorSessionState()
        XCTAssertTrue(state.apply(event(3, .answerReview, paused: true)))
        XCTAssertFalse(state.apply(event(2, .answering)))
        XCTAssertFalse(state.apply(event(3, .answerReview)))
        XCTAssertEqual(state.snapshot?.phase, .answerReview)
        XCTAssertEqual(state.snapshot?.paused, true)
        XCTAssertTrue(state.apply(event(4, .answerReview)))
        XCTAssertEqual(state.snapshot?.paused, false)
        XCTAssertEqual(state.snapshot?.answerID, answerID)
    }

    func testNewSequenceCannotRestoreAnOldLessonRevision() {
        var state = VoiceTutorSessionState()
        XCTAssertTrue(state.apply(event(1, .questionReading, revision: 3)))
        XCTAssertFalse(state.apply(event(2, .graded, revision: 2)))
        XCTAssertFalse(state.apply(event(3, .questionReading, revision: 3), minimumRevision: 4))
        XCTAssertTrue(state.apply(event(4, .questionGenerating, revision: 4)))
        XCTAssertEqual(state.snapshot?.revision, 4)
    }

    func testEndCannotBeReversedByLateLiveOrPausedSnapshots() {
        for terminal in [VoiceTutorSessionStateEvent.Phase.ended, .failed] {
            var state = VoiceTutorSessionState()
            XCTAssertTrue(state.apply(event(1, .ending)))
            XCTAssertFalse(state.apply(event(2, .conversation)))
            XCTAssertTrue(state.apply(event(3, terminal)))
            XCTAssertFalse(state.apply(event(4, .answering, paused: true)))
            XCTAssertEqual(state.snapshot?.phase, terminal)
        }
        var state = VoiceTutorSessionState()
        _ = state.apply(event(1, .answering))
        state.endLocally()
        XCTAssertNil(state.snapshot)
        XCTAssertFalse(state.apply(event(2, .graded)))
        state = VoiceTutorSessionState()
        XCTAssertTrue(state.apply(event(1, .conversation)), "A new call owns a new sequence domain")
    }

    private func event(_ sequence: Int64, _ phase: VoiceTutorSessionStateEvent.Phase,
                       paused: Bool = false, revision: Int64 = 1, scoped: Bool = true) -> VoiceTutorSessionStateEvent {
        VoiceTutorSessionStateEvent(sequence: sequence, phase: phase, paused: paused, revision: revision,
            studyID: scoped ? 42 : nil, recordID: scoped ? "101" : nil, answerID: scoped ? answerID : nil)
    }

    private func fields(_ event: VoiceTutorSessionStateEvent) -> [String: Any] {
        var value: [String: Any] = ["type": type, "sequence": event.sequence, "phase": event.phase.rawValue,
                                  "paused": event.paused, "revision": event.revision]
        if let studyID = event.studyID { value["studyId"] = studyID }
        if let recordID = event.recordID { value["recordId"] = recordID }
        if let answerID = event.answerID { value["answerId"] = answerID }
        return value
    }

    private func parse(_ fields: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: fields))
    }
}
