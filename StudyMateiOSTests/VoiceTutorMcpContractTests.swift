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

    func testUpdatedStudyUsesExactIDAndNeverTrustsEventMetadata() throws {
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.study.changed","studyId":42,"change":"updated","topic":"untrusted","difficultyLevel":10}"#
        ), .studyTreeUpdated(studyID: 42))
    }

    func testDeletionParsesOnlyAnExactConfirmedSubtree() throws {
        XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
            text: #"{"type":"buddystudy.voice.study.changed","studyId":42,"change":"deleted","deletedStudyIds":[43,42,44]}"#
        ), .studyTreeDeleted(studyIDs: [42, 43, 44]))
    }

    func testUnknownStudyChangeKindsDoNotMutateState() throws {
        for value in ["null", "true", "1", "\"DELETE\"", "\"renamed\"", "{}"] {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
                text: "{\"type\":\"buddystudy.voice.study.changed\",\"studyId\":42,\"change\":\(value)}"
            ), .ignored(type: "buddystudy.voice.study.changed"))
        }
    }

    func testMalformedDeletionCannotRemoveAnyNodes() throws {
        let oversized = "[" + (1...129).map(String.init).joined(separator: ",") + "]"
        for ids in ["null", "[]", "[43]", "[42,42]", "[42,0]", "[42,-1]", "[42,true]", "[42,1.5]", "[42,\"43\"]", oversized] {
            XCTAssertEqual(try VoiceTutorRealtimeEventParser.parse(
                text: "{\"type\":\"buddystudy.voice.study.changed\",\"studyId\":42,\"change\":\"deleted\",\"deletedStudyIds\":\(ids)}"
            ), .ignored(type: "buddystudy.voice.study.changed"))
        }
    }

    func testMetadataRefreshAcceptsOnlyLatestRequest() throws {
        var fence = VoiceTutorStudyMetadataFence()
        let first = try XCTUnwrap(fence.begin(studyID: 42))
        let second = try XCTUnwrap(fence.begin(studyID: 42))
        XCTAssertFalse(fence.isCurrent(studyID: 42, token: first))
        fence.finish(studyID: 42, token: first)
        XCTAssertTrue(fence.isCurrent(studyID: 42, token: second))
        fence.finish(studyID: 42, token: second)
        XCTAssertFalse(fence.isCurrent(studyID: 42, token: second))
    }

    func testDeletionFencesLateMetadataWithoutInvalidatingSurvivingNode() throws {
        var fence = VoiceTutorStudyMetadataFence()
        let deletedRequest = try XCTUnwrap(fence.begin(studyID: 42))
        let survivingRequest = try XCTUnwrap(fence.begin(studyID: 45))
        fence.delete(studyIDs: [42, 43])
        XCTAssertFalse(fence.isCurrent(studyID: 42, token: deletedRequest))
        XCTAssertNil(fence.begin(studyID: 42))
        XCTAssertTrue(fence.isDeleted(studyID: 43))
        XCTAssertTrue(fence.isCurrent(studyID: 45, token: survivingRequest))
    }

    func testAccountFenceResetCannotAcceptOldRequestOrLeakTombstones() throws {
        var fence = VoiceTutorStudyMetadataFence()
        let old = try XCTUnwrap(fence.begin(studyID: 42))
        fence.delete(studyIDs: [43])
        fence = VoiceTutorStudyMetadataFence()
        XCTAssertFalse(fence.isCurrent(studyID: 42, token: old))
        XCTAssertNotNil(fence.begin(studyID: 43))
    }

    func testRenameAndLevelMergePreservesIdentitySelectionAndUnrelatedSettings() {
        let original = makeSettings()
        let result = VoiceTutorStudySettingsMetadata.applying(makeStudy(), to: original)
        XCTAssertEqual(result.selectedStudyCategoryID, original.selectedStudyCategoryID)
        XCTAssertEqual(result.studyCategories.map(\.id), original.studyCategories.map(\.id))
        XCTAssertEqual(result.studyCategories[0].title, "새 합성 주제")
        XCTAssertEqual(result.studyCategories[0].difficulty.level, 6)
        XCTAssertEqual(result.topic, "새 합성 주제")
        XCTAssertEqual(result.difficulty.level, 6)
        XCTAssertEqual(result.studyCategories[1], original.studyCategories[1])
        XCTAssertEqual(result.customPrompt, original.customPrompt)
        XCTAssertEqual(result.openAIModel, original.openAIModel)
        XCTAssertEqual(result.voiceTutorVoice, original.voiceTutorVoice)
        XCTAssertEqual(result.intervalMinutes, original.intervalMinutes)
        XCTAssertEqual(original.studyCategories[0].difficulty.level, 3)
    }

    func testNewRootMetadataAppendsAVisibleCategoryWithoutChangingTheActiveSelection() {
        let original = makeSettings()
        var root = makeStudy()
        root.id = 99
        root.topic = "새 음성 루트"
        root.difficultyLevel = 8
        root.customPrompt = "새 루트 전용 안내"
        root.openAIModel = "gpt-5.4"
        root.createdAt = Date(timeIntervalSince1970: 123)

        let result = VoiceTutorStudySettingsMetadata.applying(root, to: original)

        XCTAssertEqual(result.studyCategories.map(\.id), ["42", "45", "99"])
        XCTAssertEqual(result.studyCategories.last, StudyCategory(
            id: "99",
            title: "새 음성 루트",
            difficulty: Difficulty(level: 8),
            customPrompt: "새 루트 전용 안내",
            openAIModel: "gpt-5.4",
            createdAt: Date(timeIntervalSince1970: 123)
        ))
        XCTAssertEqual(result.selectedStudyCategoryID, original.selectedStudyCategoryID)
        XCTAssertEqual(result.topic, original.topic)
        XCTAssertEqual(result.difficulty, original.difficulty)
        XCTAssertEqual(result.customPrompt, original.customPrompt)
        XCTAssertEqual(result.openAIModel, original.openAIModel)
        XCTAssertEqual(result.intervalMinutes, original.intervalMinutes)
        XCTAssertEqual(original.studyCategories.map(\.id), ["42", "45"])

        var rooms = StudyRoomStateStore()
        rooms.replace(with: [root])
        XCTAssertEqual(
            StudyRoomDisplayPolicy.rootCategories(from: result.studyCategories, rooms: rooms.rooms).map(\.id),
            ["99"]
        )
    }

    func testNewChildMetadataNeverBecomesARootCategoryOrChangesDraftSettings() {
        let original = makeSettings()
        var child = makeStudy()
        child.id = 99
        child.parentStudyId = 42
        child.topic = "새 음성 하위 주제"
        child.difficultyLevel = 8

        XCTAssertEqual(VoiceTutorStudySettingsMetadata.applying(child, to: original), original)
    }

    func testMissingSelectedRootCategoryCanBeImportedWithoutReplacingItsDraftTopicOrLevel() {
        var original = makeSettings()
        original.selectedStudyCategoryID = "99"
        original.topic = "작성 중인 기존 주제"
        original.difficulty = .level9
        var root = makeStudy()
        root.id = 99
        root.topic = "서버의 새 음성 루트"
        root.difficultyLevel = 2

        let result = VoiceTutorStudySettingsMetadata.applying(root, to: original)

        XCTAssertEqual(result.studyCategories.map(\.id), ["42", "45", "99"])
        XCTAssertEqual(result.selectedStudyCategoryID, "99")
        XCTAssertEqual(result.topic, "작성 중인 기존 주제")
        XCTAssertEqual(result.difficulty, .level9)
    }

    func testServerDeletionDoesNotSwitchActiveDraftSettingsOrDeleteOtherTopics() {
        let original = makeSettings()
        let result = VoiceTutorStudySettingsMetadata.removing(studyIDs: [42, 43], from: original)
        XCTAssertEqual(result.studyCategories.map(\.id), ["45"])
        XCTAssertEqual(result.selectedStudyCategoryID, original.selectedStudyCategoryID)
        XCTAssertEqual(result.topic, original.topic)
        XCTAssertEqual(result.difficulty, original.difficulty)
        XCTAssertEqual(result.customPrompt, original.customPrompt)
        XCTAssertEqual(original.studyCategories.count, 2)
    }

    func testConfirmedDeletionAlsoRejectsLateActivationRollbackAndWholePage() {
        var rooms = StudyRoomStateStore()
        let original = makeStudy()
        var surviving = original
        surviving.id = 45
        rooms.replace(with: [original, surviving])
        rooms.markVoiceDeleted(studyIDs: [42])
        rooms.upsertStudy(original) // delayed activation success or error rollback
        XCTAssertEqual(rooms.rooms.map(\.id), [45])
        rooms.replace(with: [original, surviving]) // an older whole-tree response
        XCTAssertEqual(rooms.rooms.map(\.id), [45])
    }

    func testNewAccountCanReuseAnIDAfterDeletionFenceReset() {
        var rooms = StudyRoomStateStore()
        rooms.markVoiceDeleted(studyIDs: [42])
        rooms.resetVoiceDeletionFence()
        rooms.replace(with: [makeStudy()])
        XCTAssertEqual(rooms.rooms.map(\.id), [42])
    }

    private func makeSettings() -> StudySettings {
        StudySettings(topic: "원래 합성 주제", difficulty: .level3,
                      customPrompt: "보존할 설정", intervalMinutes: 45,
                      studyCategories: [
                        StudyCategory(id: "42", title: "원래 합성 주제", difficulty: .level3),
                        StudyCategory(id: "45", title: "다른 합성 주제", difficulty: .level3),
                      ], selectedStudyCategoryID: "42")
    }

    private func makeStudy() -> BackendStudyRoom {
        BackendStudyRoom(id: 42, topic: "새 합성 주제", difficultyLevel: 6, intervalMinutes: 60,
                         enabled: true, notificationSound: nil, customPrompt: "", openAIModel: "synthetic-model",
                         maxHistoryCount: 30, nextDueAt: nil, lastSentAt: nil, lastError: nil,
                         pendingQuestion: nil, createdAt: Date(timeIntervalSince1970: 0), updatedAt: Date(timeIntervalSince1970: 0))
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
