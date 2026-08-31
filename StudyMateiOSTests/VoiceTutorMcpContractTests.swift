import Foundation
import XCTest
@testable import StudyMate

/// Pure contracts: no microphone, server requests, account or recording mutations.
final class VoiceTutorMcpContractTests: XCTestCase {
    func testConfirmedStudyChangeParsesOnlyItsExactID() throws {
        XCTAssertEqual(
            try VoiceTutorRealtimeEventParser.parse(
                text: #"{"type":"buddystudy.voice.study.changed","studyId":42,"topic":"untrusted"}"#
            ),
            .studyTreeChanged(studyID: 42)
        )
    }

    func testMalformedStudyChangeCannotTriggerARefresh() throws {
        for value in ["null", "true", "false", "0", "-1", "1.5", "\"42\"", "[]", "{}", "9223372036854775808"] {
            XCTAssertEqual(
                try VoiceTutorRealtimeEventParser.parse(
                    text: "{\"type\":\"buddystudy.voice.study.changed\",\"studyId\":\(value)}"
                ),
                .ignored(type: "buddystudy.voice.study.changed")
            )
        }
    }

    func testRawToolEventsDoNotAppearAsConversationOrStudyChanges() throws {
        for type in ["response.function_call_arguments.done", "conversation.item.created", "conversation.item.done"] {
            XCTAssertEqual(
                try VoiceTutorRealtimeEventParser.parse(
                    text: "{\"type\":\"\(type)\",\"item\":{\"type\":\"function_call_output\",\"studyId\":42}}"
                ),
                .ignored(type: type)
            )
        }
    }

    func testSilentToolResponseDoesNotClaimSpeakingAndNextAudioCanStart() {
        var state = VoiceTutorWebRTCResponseState()
        state.responseStarted("tool-response")
        XCTAssertFalse(state.mayIndicateSpeaking)
        XCTAssertNil(state.markResponseDone("tool-response"))
        XCTAssertFalse(state.mayIndicateSpeaking)
        // No invented output-buffer-stopped event is needed for a silent tool.
        state.responseStarted("spoken-tool-result")
        XCTAssertFalse(state.mayIndicateSpeaking)
        state.markOutputBufferStarted("spoken-tool-result")
        XCTAssertTrue(state.mayIndicateSpeaking)
        XCTAssertNil(state.markResponseDone("spoken-tool-result"))
        XCTAssertTrue(state.mayIndicateSpeaking)
        XCTAssertEqual(state.markOutputBufferStopped("spoken-tool-result"), "spoken-tool-result")
        XCTAssertFalse(state.mayIndicateSpeaking)
    }
}
