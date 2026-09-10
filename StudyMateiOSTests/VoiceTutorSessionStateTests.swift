import Foundation
import SwiftUI
import UIKit
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

final class VoiceTutorOperationStateTests: XCTestCase {
    private let type = "buddystudy.voice.operation"

    func testOperationWireContractRejectsPayloadsAndMalformedTiming() throws {
        let valid: [String: Any] = ["type": type, "sequence": 1, "operationId": "call_read_1",
            "name": "get_grading_process", "phase": "started", "elapsedMs": 0]
        XCTAssertEqual(try parse(valid), .operation(event(1, "call_read_1", .started)))
        let invalidValues: [(String, Any)] = [
            ("sequence", true), ("sequence", 0), ("sequence", "1"), ("sequence", 1.5),
            ("elapsedMs", false), ("elapsedMs", -1), ("elapsedMs", 0.5), ("elapsedMs", 3_600_001),
            ("operationId", ""), ("operationId", "call/secret"), ("name", "get_record(arguments)"),
            ("name", ""), ("phase", "pending"), ("arguments", ["answer": "private"]),
            ("result", "private response"), ("message", "provider failure body")
        ]
        for (key, value) in invalidValues {
            var invalid = valid
            invalid[key] = value
            XCTAssertEqual(try parse(invalid), .ignored(type: type), key)
        }
        for key in valid.keys {
            guard key != "type" else { continue }
            var invalid = valid
            invalid.removeValue(forKey: key)
            XCTAssertEqual(try parse(invalid), .ignored(type: type), key)
        }
    }

    func testElapsedTimeSurvivesBackgroundAndDuplicateStartWithoutRewinding() {
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "call_read_1", .started), at: 100))
        XCTAssertEqual(state.active.first?.elapsedMilliseconds(at: 100.125), 125)
        XCTAssertFalse(state.apply(event(2, "call_read_1", .started), at: 101))
        XCTAssertEqual(state.active.first?.elapsedMilliseconds(at: 130), 30_000)
        XCTAssertTrue(state.apply(event(3, "call_read_1", .completed, elapsed: 30_050), at: 131))
        XCTAssertTrue(state.active.isEmpty)
        XCTAssertEqual(state.visibleEntries(at: 132).first?.elapsedMilliseconds(at: 132), 30_050)
        XCTAssertEqual(state.visibleEntries(at: 3_600).map(\.id), ["call_read_1"])
        state.endLocally()
        XCTAssertEqual(state.visibleEntries(at: 3_700).map(\.id), ["call_read_1"])
        XCTAssertFalse(state.apply(event(2, "call_read_1", .started), at: 137))
        XCTAssertFalse(state.apply(event(4, "call_read_1", .started), at: 137))
    }

    func testConcurrentOperationCompletionDoesNotHideAnotherPendingCall() {
        var state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "one", .started), at: 10))
        XCTAssertTrue(state.apply(event(2, "two", .started), at: 11))
        XCTAssertTrue(state.apply(event(3, "two", .failed, elapsed: 420), at: 12))
        XCTAssertEqual(state.visibleEntries(at: 12).map(\.id), ["one", "two"])
        XCTAssertTrue(state.apply(event(4, "one", .completed, elapsed: 2_300), at: 13))
        XCTAssertEqual(state.visibleEntries(at: 13).map(\.id), ["one", "two"])
        XCTAssertEqual(state.latestFinished?.event.phase, .completed)
    }

    func testOperationLimitAndCallEndFenceLateEvents() {
        var state = VoiceTutorOperationState()
        for sequence in 1...8 {
            XCTAssertTrue(state.apply(event(Int64(sequence), "call_\(sequence)", .started), at: 100))
        }
        XCTAssertFalse(state.apply(event(9, "call_9", .started), at: 100))
        XCTAssertEqual(state.active.count, VoiceTutorOperationState.maximumActiveOperations)
        state.endLocally()
        XCTAssertTrue(state.visibleEntries(at: 101).isEmpty)
        XCTAssertFalse(state.apply(event(10, "call_1", .completed), at: 101))
        state = VoiceTutorOperationState()
        XCTAssertTrue(state.apply(event(1, "new_call", .started), at: 102))
    }

    func testOperationStatusesAreLocalizedAndKeepExactFunctionAndMeasuredTime() {
        for language in [AppLanguage.korean, .english, .japanese] {
            let strings = AppStrings(language: language)
            let labels = VoiceTutorOperationEvent.Phase.allCases.map {
                strings.voiceTutorOperationStatus(name: "get_grading_process", phase: $0, elapsedMilliseconds: 275)
            }
            XCTAssertEqual(Set(labels).count, 3)
            XCTAssertTrue(labels.allSatisfy { $0.hasPrefix("get_grading_process · ") && !$0.contains("ms") })
        }
        let strings = AppStrings(language: .korean)
        XCTAssertTrue(strings.voiceTutorOperationStatus(name: "read_studies", phase: .completed, elapsedMilliseconds: 2_300).hasSuffix("2초"))
        XCTAssertTrue(strings.voiceTutorOperationStatus(name: "read_studies", phase: .completed, elapsedMilliseconds: 125_000).hasSuffix("2분"))
    }

    @MainActor
    func testOperationStatusRendersInCallAndTranscript() async throws {
        for expanded in [false, true] {
            var state = VoiceTutorOperationState()
            _ = state.apply(event(1, "visual_operation", .started), at: ProcessInfo.processInfo.systemUptime - 0.275)
            let root = VoiceTutorCallScreen(
                topic: "스프링", presentation: VoiceTutorCallPresentation(phase: .listening, isAwaitingTutorResponse: true),
                strings: AppStrings(language: .korean), operationState: state,
                captions: [VoiceTutorCaption(speaker: .tutor, text: "저장된 채점 결과를 확인하고 있어요.")],
                showsTranscript: .constant(expanded), showsSummary: .constant(false)
            ).environment(\.colorScheme, .dark).environment(\.scenePhase, .active)
            let controller = UIHostingController(rootView: root)
            let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            let previous = scene?.windows.first { $0.isKeyWindow }
            let window = scene.map(UIWindow.init(windowScene:)) ?? UIWindow()
            window.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
            window.overrideUserInterfaceStyle = .dark
            window.rootViewController = controller
            window.makeKeyAndVisible()
            defer { window.isHidden = true; window.rootViewController = nil; previous?.makeKey() }
            controller.view.frame = window.bounds
            controller.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(150))
            let image = UIGraphicsImageRenderer(bounds: window.bounds).image { context in
                window.layer.render(in: context.cgContext)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = expanded ? "voice-operation-transcript" : "voice-operation-call"
            attachment.lifetime = .keepAlways
            add(attachment)
            XCTAssertGreaterThan(image.size.width, 0)
        }
    }

    private func event(_ sequence: Int64, _ id: String, _ phase: VoiceTutorOperationEvent.Phase,
                       elapsed: Int64 = 0) -> VoiceTutorOperationEvent {
        VoiceTutorOperationEvent(sequence: sequence, operationID: id, name: "get_grading_process",
            phase: phase, elapsedMilliseconds: elapsed)
    }

    private func parse(_ fields: [String: Any]) throws -> VoiceTutorRealtimeEvent {
        try VoiceTutorRealtimeEventParser.parse(data: JSONSerialization.data(withJSONObject: fields))
    }
}
