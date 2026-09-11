import Combine
import Foundation
import XCTest
@testable import StudyMate

/// Isolated defaults and a fail-closed synthetic HTTP transport only. No app
/// launch, account purge, microphone, recordings, provider calls or real writes.
@MainActor
final class VoiceCommonRecordTests: XCTestCase {
    func testLegacyQuestionDecodesWithoutNewFields() throws {
        let record = try decodeRecord(questionJSON())
        XCTAssertEqual(record.recordType, .question)
        XCTAssertNil(record.voiceRecord)
        XCTAssertTrue(record.isCompletedRecord)
        XCTAssertEqual(record.gradingResult?.score, 90)
    }

    func testVoiceUsesCanonicalRecordIDAndCompletedWithoutFakeGrade() throws {
        let record = try decodeRecord(voiceJSON())
        XCTAssertEqual(record.id, "900")
        XCTAssertEqual(record.questionStatus, .completed)
        XCTAssertEqual(record.recordType, .voiceTutor)
        XCTAssertNil(record.gradingResult)
        XCTAssertNil(record.gradingStatus)
        XCTAssertTrue(record.isCompletedRecord)
        XCTAssertFalse(record.isPendingQuestion)
    }

    func testMixedRecordsPagePreservesBothTypesAndPagination() throws {
        let page: BackendRecordsPage = try decode(pageJSON([questionJSON(), voiceJSON()], total: 7, offset: 3))
        XCTAssertEqual(page.records.map(\.id), ["101", "900"])
        XCTAssertEqual(page.totalCount, 7)
        XCTAssertEqual(page.offset, 3)
        XCTAssertEqual(page.records.map(\.recordType), [.question, .voiceTutor])
    }

    func testUnknownTypeCannotFallBackToPendingQuestion() {
        var value = voiceJSON()
        value["recordType"] = "UNSUPPORTED"
        XCTAssertThrowsError(try decodeRecord(value))
    }

    func testMissingOrCrossTypeVoicePayloadFailsClosed() {
        var missing = voiceJSON(); missing.removeValue(forKey: "voiceRecord")
        var wrong = questionJSON(); wrong["voiceRecord"] = voicePayload()
        XCTAssertThrowsError(try decodeRecord(missing))
        XCTAssertThrowsError(try decodeRecord(wrong))
    }

    func testVoiceCannotMasqueradeAsQueuedOrGradedQuestion() {
        var graded = voiceJSON(); graded["gradingResult"] = questionJSON()["gradingResult"]
        var queued = voiceJSON(); queued["gradingStatus"] = "QUEUED"
        var pending = voiceJSON(); pending["questionStatus"] = "UNGRADED"
        var request = voiceJSON(); request["gradingRequestId"] = "synthetic-request"
        for object in [graded, queued, pending, request] { XCTAssertThrowsError(try decodeRecord(object)) }
    }

    func testVoiceRequiresPositiveCanonicalIDWithoutLegacyPrefix() {
        for id in ["0", "-1", "voice:456", "not-an-id"] {
            var object = voiceJSON(); object["id"] = id
            XCTAssertThrowsError(try decodeRecord(object))
        }
    }

    func testUnansweredVoiceRemainsOwnerHistoryButCannotBePublished() throws {
        var object = voiceJSON(); object["answer"] = NSNull(); object["isPublic"] = true
        let record = try decodeRecord(object)
        XCTAssertTrue(record.isCompletedRecord)
        XCTAssertFalse(record.isPendingQuestion)
        XCTAssertFalse(record.canPublish)
        XCTAssertNil(record.displayScore)
        XCTAssertNil(record.asCommunityQuestion(author: nil))
        XCTAssertFalse(record.asQuestionBrowseQuestion(author: nil).canPublish)
    }

    func testBlankVoiceQuestionOrAnswerDoesNotEnablePublicInteractions() throws {
        for field in ["question", "answer"] {
            var object = voiceJSON()
            if field == "question" {
                object[field] = ["question": " \n ", "createdAt": dateText]
            } else { object[field] = " \n " }
            let record = try decodeRecord(object)
            XCTAssertFalse(record.canPublish)
        }
    }

    func testMissingPublicFlagDefaultsVoicePrivateButKeepsLegacyQuestionDefault() throws {
        var voice = voiceJSON(); voice.removeValue(forKey: "isPublic")
        var question = questionJSON(); question.removeValue(forKey: "isPublic")
        XCTAssertFalse(try decodeRecord(voice).isPublic)
        XCTAssertTrue(try decodeRecord(question).isPublic)
    }

    func testMissingInvalidOrLearnerQuestionScoreIsNotZero() throws {
        for score in [NSNull(), -1, 101] as [Any] {
            var object = voiceJSON(); var content = voicePayload(); content["score"] = score; object["voiceRecord"] = content
            XCTAssertNil(try decodeRecord(object).displayScore)
        }
        var object = voiceJSON(); var content = voicePayload(); content["kind"] = "LEARNER_QUESTION"; object["voiceRecord"] = content
        XCTAssertNil(try decodeRecord(object).displayScore)
    }

    func testActualZeroScoreIsRetainedWithoutGradingResult() throws {
        var object = voiceJSON(); var content = voicePayload(); content["score"] = 0; object["voiceRecord"] = content
        let record = try decodeRecord(object)
        XCTAssertEqual(record.displayScore, 0)
        XCTAssertNil(record.gradingResult)
    }

    func testVoiceCodableRoundTripDoesNotInventPrivateProvenance() throws {
        let record = try decodeRecord(voiceJSON())
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(record)
        let roundTrip = try RemotePushBackendClient.makeDecoder().decode(StudyRecord.self, from: data)
        XCTAssertEqual(roundTrip, record)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let payload = try XCTUnwrap(object["voiceRecord"] as? [String: Any])
        XCTAssertEqual(Set(payload.keys), Set(voicePayload().keys))
        for key in ["sessionId", "questionTurnId", "answerTurnIds", "recording", "audio"] {
            XCTAssertNil(object[key]); XCTAssertNil(payload[key])
        }
    }

    func testPrivateToPublicProjectionKeepsCanonicalIDVoiceContentAndCounts() throws {
        var record = try decodeRecord(voiceJSON()); record.isPublic = true
        let value = try XCTUnwrap(record.asCommunityQuestion(author: nil))
        XCTAssertEqual(value.id, record.id)
        XCTAssertEqual(value.recordType, .voiceTutor)
        XCTAssertEqual(value.voiceRecord, record.voiceRecord)
        XCTAssertEqual(value.status, "completed")
        XCTAssertEqual(value.source, "voice_tutor")
        XCTAssertEqual(value.commentCount, record.commentCount)
        XCTAssertNil(value.gradingResult)
    }

    func testPublicDecoderKeepsVoiceCompletedContract() throws {
        let publicRecord: CommunityQuestion = try decode(publicJSON())
        XCTAssertEqual(publicRecord.id, "900")
        XCTAssertTrue(publicRecord.canPublish)
        XCTAssertNil(publicRecord.gradingResult)
        var malformed = publicJSON(); malformed["status"] = "graded"
        XCTAssertThrowsError(try decode(malformed) as CommunityQuestion)
    }

    func testPublicQuestionOwnershipProofDoesNotRequireAnAuthorProfile() throws {
        for owned in [true, false] {
            var object = publicJSON()
            object["isOwnedByMe"] = owned
            let question: CommunityQuestion = try decode(object)
            XCTAssertNil(question.author)
            XCTAssertEqual(question.isOwnedByMe, owned)
            var updated = question
            updated.isLikedByMe = true
            updated.commentCount += 1
            XCTAssertEqual(updated.isOwnedByMe, owned, "Local feed updates must retain the server ownership proof")
        }
    }

    func testLegacyPublicQuestionOwnershipRemainsUnknownInsteadOfBecomingFalse() throws {
        let omitted: CommunityQuestion = try decode(publicJSON())
        XCTAssertNil(omitted.isOwnedByMe)
        var object = publicJSON(); object["isOwnedByMe"] = NSNull()
        let explicitNull: CommunityQuestion = try decode(object)
        XCTAssertNil(explicitNull.isOwnedByMe)
    }

    func testPublicQuestionOwnershipProofRejectsNonBooleanValues() {
        for invalid in [1, 0, "true", "false", [], [:]] as [Any] {
            var object = publicJSON(); object["isOwnedByMe"] = invalid
            XCTAssertThrowsError(try decode(object) as CommunityQuestion)
        }
    }

    func testPublicQuestionOwnershipAcceptsBeanAliasButCanonicalFalseWins() throws {
        var object = publicJSON(); object["ownedByMe"] = true
        let aliased: CommunityQuestion = try decode(object)
        XCTAssertEqual(aliased.isOwnedByMe, true)
        object["isOwnedByMe"] = false
        let canonical: CommunityQuestion = try decode(object)
        XCTAssertEqual(canonical.isOwnedByMe, false)

        for key in ["isOwnedByMe", "ownedByMe"] {
            for invalid in [1, "true"] as [Any] {
                var malformed = publicJSON(); malformed["ownedByMe"] = true; malformed[key] = invalid
                XCTAssertThrowsError(try decode(malformed) as CommunityQuestion, key)
            }
        }
    }

    func testColdStartPublicQuestionActionsUseServerOwnershipBeforeTheProfileLoads() throws {
        let fixture = try CommonRecordHTTPFixture(response: publicJSON())
        defer { fixture.close() }
        // Recreate the real startup state instead of the fixture's already-loaded profile.
        let app = AppState(settingsStore: fixture.store, remotePushBackendClient: fixture.client,
                           appNotificationEventProvider: CommonRecordNotificationEvents())
        XCTAssertTrue(app.isCommunitySessionActive)
        XCTAssertNil(app.communityProfile)
        XCTAssertEqual(app.backendAccessState.user.id, 0)

        var ownedJSON = publicJSON(); ownedJSON["isOwnedByMe"] = true
        let owned: CommunityQuestion = try decode(ownedJSON)
        XCTAssertNil(owned.author)
        let ownActions = app.communityQuestionActionPolicy(for: owned)
        XCTAssertTrue(ownActions.canManage)
        XCTAssertFalse(ownActions.canReport)
        XCTAssertFalse(ownActions.canBlock)

        var otherJSON = publicJSON(); otherJSON["isOwnedByMe"] = false
        let other: CommunityQuestion = try decode(otherJSON)
        let otherActions = app.communityQuestionActionPolicy(for: other)
        XCTAssertFalse(otherActions.canManage)
        XCTAssertTrue(otherActions.canReport)
        XCTAssertTrue(otherActions.canBlock)

        var legacy: CommunityQuestion = try decode(publicJSON())
        legacy.author = CommonRecordHTTPFixture.profile(id: 7)
        let unknownActions = app.communityQuestionActionPolicy(for: legacy)
        XCTAssertFalse(unknownActions.canManage)
        XCTAssertFalse(unknownActions.canReport)
        XCTAssertFalse(unknownActions.canBlock)
        XCTAssertTrue(fixture.requests.isEmpty, "Ownership presentation must not need a profile fetch or mutation")
        fixture.assertDraftPreserved(app)
    }

    func testPublicAndLikedFeedsKeepCompletedVoiceAndExcludeUnansweredVoice() throws {
        let voice: CommunityQuestion = try decode(publicJSON())
        var unanswered = voice; unanswered.id = "901"; unanswered.answer = nil
        var pending = voice; pending.id = "902"; pending.recordType = .question; pending.voiceRecord = nil; pending.status = "ungraded"
        let response = CommunityQuestionsResponse(questions: [voice, unanswered, pending], totalCount: 3, limit: 30)
        var feed = CommunityFeedStateStore()
        feed.applyPage(response, offset: 0, reset: true)
        XCTAssertEqual(feed.questions.map(\.id), ["900"])
        XCTAssertEqual(feed.offset, 3)
        var liked = LikedQuestionsStateStore()
        liked.applyPage(response, offset: 0, reset: true)
        XCTAssertEqual(liked.questions.map(\.id), ["900"])
        XCTAssertEqual(liked.offset, 3)
    }

    func testPendingAndAnswerPresentationNeverTreatVoiceAsDraft() throws {
        var pending = try decodeRecord(questionJSON()); pending.gradingResult = nil; pending.answer = nil; pending.questionStatus = .ungraded
        let voice = try decodeRecord(voiceJSON())
        let state = RecordsStateStore(records: [voice, pending])
        XCTAssertEqual(state.pendingRecords.map(\.id), [pending.id])
        XCTAssertEqual(StudyAnswerPresentationPolicy.state(for: voice), .completed)
        XCTAssertFalse(StudyAnswerPresentationPolicy.shouldShowEditor(for: voice))
        XCTAssertTrue(StudyAnswerPresentationPolicy.shouldShowEditor(for: pending))
    }

    func testHistoryPageAccountingIncludesVoiceWithoutScore() throws {
        let voice = try decodeRecord(voiceJSON())
        var state = RecordsStateStore(records: [voice])
        state.applyPage(BackendRecordsPage(records: [voice], totalCount: 2, limit: 30), reset: true)
        XCTAssertEqual(state.loadedBackendCount, 1)
        XCTAssertTrue(state.canLoadMore)
        state.removeLoadedBackendRecord(voice)
        XCTAssertEqual(state.totalCount, 1)
        XCTAssertEqual(state.loadedBackendCount, 0)
    }

    func testTimestampQuestionLookupCannotReturnVoice() throws {
        let voice = try decodeRecord(voiceJSON())
        let state = RecordsStateStore(records: [voice])
        XCTAssertNil(state.record(questionCreatedAt: voice.question.createdAt.timeIntervalSince1970))
        XCTAssertNil(state.record(matching: voice.question, matches: { _, _ in true }))
    }

    func testVoiceDoesNotReplaceStudyPendingQuestionOrLatestQuestion() throws {
        var pending = try decodeRecord(questionJSON()); pending.gradingResult = nil; pending.questionStatus = .ungraded
        let voice = try decodeRecord(voiceJSON())
        var state = StudyRoomStateStore()
        state.replace(with: [BackendStudyRoom(
            id: 41, topic: "Synthetic topic", difficultyLevel: 3, intervalMinutes: 30, enabled: true,
            notificationSound: nil, customPrompt: "", openAIModel: "", maxHistoryCount: 30,
            nextDueAt: nil, lastSentAt: nil, lastError: nil, pendingQuestion: pending,
            createdAt: pending.question.createdAt, updatedAt: pending.question.createdAt
        )])
        XCTAssertFalse(state.applyIncomingRecord(voice))
        state.setPendingQuestion(voice, forStudyID: 41)
        state.applyAnsweredRecord(voice)
        XCTAssertEqual(state.rooms.first?.pendingQuestion, pending)
        XCTAssertNil(state.rooms.first?.latestQuestion)
        XCTAssertEqual(state.pendingQuestionCount, 1)
    }

    func testQuestionScheduleIgnoresNewerVoiceRecord() throws {
        var question = try decodeRecord(questionJSON()); question.question.createdAt = Date(timeIntervalSince1970: 100)
        var voice = try decodeRecord(voiceJSON()); voice.question.createdAt = Date(timeIntervalSince1970: 1_000)
        XCTAssertEqual(QuestionSchedulePolicy.latestQuestionDate(currentQuestion: nil, studyRecords: [question, voice]), question.question.createdAt)
    }

    func testSameWordsDoNotMergeVoiceWithQuestionOrAnotherVoiceID() throws {
        let question = try decodeRecord(questionJSON())
        let first = try decodeRecord(voiceJSON())
        var second = first; second.id = "901"
        XCTAssertEqual(question.question.question, first.question.question)
        XCTAssertFalse(StudyRecordIdentityPolicy.recordsMatch(question, first))
        XCTAssertFalse(StudyRecordIdentityPolicy.recordsMatch(first, second))
        XCTAssertTrue(StudyRecordIdentityPolicy.recordsMatch(first, first))
    }

    func testTombstoneIsIDOnlyForVoiceAndContainsNoUtterance() throws {
        let question = try decodeRecord(questionJSON())
        let voice = try decodeRecord(voiceJSON())
        var repeated = voice; repeated.id = "901"
        let marker = DeletedStudyRecordMarker(record: voice)
        XCTAssertTrue(marker.matches(voice))
        XCTAssertFalse(marker.matches(question))
        XCTAssertFalse(marker.matches(repeated))
        XCTAssertEqual(marker.normalizedQuestion, "")
        XCTAssertFalse(marker.mergeKey.contains(voice.question.question))
        XCTAssertFalse(DeletedStudyRecordMarker(record: question).matches(voice))
    }

    func testOldQuestionTombstoneStillDecodesWithoutType() throws {
        let record = try decodeRecord(questionJSON())
        let marker: DeletedStudyRecordMarker = try decode([
            "recordID": record.id, "normalizedQuestion": "synthetic question",
            "mergeKey": DeletedStudyRecordMarker.mergeKey(for: record), "deletedAt": dateText
        ])
        XCTAssertNil(marker.recordType)
        XCTAssertTrue(marker.matches(record))
        XCTAssertFalse(marker.matches(try decodeRecord(voiceJSON())))
    }

    func testSettingsStoreKeepsRepeatedVoiceIDsAndQuestionDraftWhenDeletingOneVoice() throws {
        let fixture = try CommonRecordHTTPFixture(response: [:]); defer { fixture.close() }
        let question = try decodeRecord(questionJSON())
        let first = try decodeRecord(voiceJSON())
        var second = first; second.id = "901"
        fixture.store.saveAnswerDraft("synthetic draft", recordID: question.id)
        [question, first, second].forEach(fixture.store.saveStudyRecord)
        XCTAssertEqual(Set(fixture.store.loadStudyRecords().map(\.id)), ["101", "900", "901"])
        fixture.store.deleteStudyRecord(first)
        XCTAssertEqual(Set(fixture.store.loadStudyRecords().map(\.id)), ["101", "901"])
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: question.id), "synthetic draft")
    }

    func testSameNumericIDDifferentKindsCannotMatchOrOpenWrongDetail() throws {
        var question = try decodeRecord(questionJSON()); question.id = "900"
        let voice = try decodeRecord(voiceJSON())
        XCTAssertFalse(StudyRecordIdentityPolicy.recordsMatch(question, voice))
        XCTAssertNil(StudyRecordIdentityPolicy.cachedRecord(matching: voice, in: [question]))
        XCTAssertEqual(StudyRecordIdentityPolicy.cachedRecord(matching: voice, in: [question, voice]), voice)
        XCTAssertFalse(DeletedStudyRecordMarker(record: voice).matches(question))
        XCTAssertFalse(DeletedStudyRecordMarker(record: question).matches(voice))
    }

    func testStoreRejectsCrossTypeIDOverwriteAndVoiceDeleteCannotRemoveQuestionDraft() throws {
        let fixture = try CommonRecordHTTPFixture(response: [:]); defer { fixture.close() }
        var question = try decodeRecord(questionJSON()); question.id = "900"; question.gradingResult = nil
        let voice = try decodeRecord(voiceJSON())
        fixture.store.saveStudyRecord(question)
        fixture.store.saveAnswerDraft("Synthetic colliding draft", recordID: "900")
        fixture.store.saveStudyRecord(voice)
        XCTAssertEqual(fixture.store.loadStudyRecords(), [question])
        fixture.store.deleteStudyRecord(voice)
        XCTAssertEqual(fixture.store.loadStudyRecords(), [question])
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "900"), "Synthetic colliding draft")
    }

    func testConflictingVoiceActionCannotPublishOrDeleteQuestionWithSameNumericID() throws {
        let fixture = try CommonRecordHTTPFixture(response: [:]); defer { fixture.close() }
        var question = try decodeRecord(questionJSON()); question.id = "900"; question.gradingResult = nil
        fixture.store.saveStudyRecord(question)
        fixture.store.saveAnswerDraft("Synthetic colliding draft", recordID: "900")
        let app = fixture.makeApp()
        let voice = try decodeRecord(voiceJSON())
        app.updateStudyRecordPublicity(voice, isPublic: true)
        app.deleteStudyRecord(voice)
        XCTAssertEqual(fixture.store.loadStudyRecords(), [question])
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "900"), "Synthetic colliding draft")
        XCTAssertTrue(fixture.requests.isEmpty)
        fixture.assertDraftPreserved(app)
    }

    func testAccountChangeDetachesDraftBeforeMergingSameNumericIDVoice() async throws {
        let fixture = try CommonRecordHTTPFixture(response: pageJSON([voiceJSON()])); defer { fixture.close() }
        var pending = try decodeRecord(questionJSON())
        pending.id = "900"; pending.answer = nil; pending.gradingResult = nil; pending.questionStatus = .ungraded
        fixture.store.saveStudyRecord(pending)
        fixture.store.saveAnswerDraft("Synthetic colliding draft", recordID: "900")
        let app = fixture.makeApp()
        app.communityProfile = CommonRecordHTTPFixture.profile(id: 8)
        let detached = try XCTUnwrap(app.studyRecords.first(where: \.isDetachedLocalQuestion))
        XCTAssertNotEqual(detached.id, "900")
        XCTAssertNil(detached.studyID)
        XCTAssertEqual(detached.question, pending.question)
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: detached.id), "Synthetic colliding draft")
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "900"), "")
        await app.refreshBackendRecords()
        let voice = try XCTUnwrap(app.studyRecords.first(where: \.isVoiceRecord))
        XCTAssertEqual(voice.id, "900")
        XCTAssertEqual(app.studyRecords.filter(\.isDetachedLocalQuestion).map(\.id), [detached.id])
        XCTAssertEqual(StudyRecordIdentityPolicy.cachedRecord(matching: voice, in: app.studyRecords), voice)
        fixture.store.deleteStudyRecord(voice)
        XCTAssertEqual(fixture.store.loadStudyRecords().map(\.id), [detached.id])
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: detached.id), "Synthetic colliding draft")
        fixture.assertDraftPreserved(app)
    }

    func testLocaleOnlyRefreshDoesNotRekeyCanonicalQuestionDraft() async throws {
        let fixture = try CommonRecordHTTPFixture(response: pageJSON([])); defer { fixture.close() }
        var pending = try decodeRecord(questionJSON())
        pending.id = "900"; pending.answer = nil; pending.gradingResult = nil; pending.questionStatus = .ungraded
        fixture.store.saveStudyRecord(pending)
        fixture.store.saveAnswerDraft("Synthetic canonical draft", recordID: "900")
        let app = fixture.makeApp()
        app.settings.appLanguage = .english
        await app.refreshBackendRecords()
        XCTAssertEqual(app.studyRecords.map(\.id), ["900"])
        XCTAssertFalse(app.studyRecords.contains(where: \.isDetachedLocalQuestion))
        XCTAssertEqual(fixture.store.loadAnswerDraft(recordID: "900"), "Synthetic canonical draft")
        fixture.assertDraftPreserved(app)
    }

    func testDetachedDraftCannotBecomeNewOwnerPendingQuotaOrServerGrading() async throws {
        let fixture = try CommonRecordHTTPFixture(response: [:]); defer { fixture.close() }
        var pending = try decodeRecord(questionJSON())
        pending.id = "local-draft:synthetic"; pending.answer = nil; pending.gradingResult = nil; pending.questionStatus = .ungraded
        fixture.store.saveStudyRecord(pending)
        let app = fixture.makeApp()
        let state = RecordsStateStore(records: [pending])
        XCTAssertFalse(pending.isPendingQuestion)
        XCTAssertTrue(state.pendingRecordsIncludingCurrent(
            currentQuestion: pending.question, gradingResult: nil, fallbackTopic: pending.topic,
            fallbackDifficulty: pending.difficulty, matches: { _, _ in true }
        ).isEmpty)
        await app.gradeRecord(pending, answer: "Synthetic local draft")
        app.updateStudyRecordPublicity(pending, isPublic: true)
        XCTAssertTrue(fixture.requests.isEmpty)
        XCTAssertEqual(fixture.store.loadStudyRecords(), [pending])
        fixture.assertDraftPreserved(app)
    }

    func testCommonStoreDoesNotCreateParallelPersistenceForVoiceBody() throws {
        let fixture = try CommonRecordHTTPFixture(response: [:]); defer { fixture.close() }
        let before = NSDictionary(dictionary: fixture.defaults.dictionaryRepresentation())
        fixture.store.saveStudyRecord(try decodeRecord(voiceJSON()))
        XCTAssertEqual(NSDictionary(dictionary: fixture.defaults.dictionaryRepresentation()), before)
        let recreated = SettingsStore(defaults: fixture.defaults, usesSecureBackendIdentityStorage: false)
        XCTAssertTrue(recreated.loadStudyRecords().isEmpty)
    }

    func testNodeWrapperKeepsLegacyKeyButOpensCanonicalCommonID() throws {
        let item: BackendStudyLearningRecord = try decode(nodeJSON())
        XCTAssertEqual(item.id, "voice:456")
        XCTAssertEqual(item.voiceRecord?.id, "456")
        XCTAssertEqual(item.voiceRecord?.recordID, "900")
        XCTAssertEqual(item.canonicalRecordID, "900")
        XCTAssertEqual(item.commonRecord?.recordType, .voiceTutor)
    }

    func testCanonicalNodeRecordCannotFallBackToLegacyAnswerOrScore() throws {
        var node = nodeJSON(); var record = voiceJSON(); record["answer"] = NSNull()
        node["record"] = record
        let item: BackendStudyLearningRecord = try decode(node)
        XCTAssertNil(item.answer)
        XCTAssertNil(item.score)
    }

    func testNodeCanonicalIdentityAndTypeMismatchFailsClosed() {
        var wrong = nodeJSON(); var record = voiceJSON(); record["id"] = "901"; wrong["record"] = record
        XCTAssertThrowsError(try decode(wrong) as BackendStudyLearningRecord)
        wrong["record"] = questionJSON()
        XCTAssertThrowsError(try decode(wrong) as BackendStudyLearningRecord)
    }

    func testOldNodeServerKeepsReadOnlyLegacyFallbackWithoutInventingRecordID() throws {
        var node = nodeJSON(); node.removeValue(forKey: "record")
        var legacy = try XCTUnwrap(node["voiceRecord"] as? [String: Any]); legacy.removeValue(forKey: "recordId"); node["voiceRecord"] = legacy
        let item: BackendStudyLearningRecord = try decode(node)
        XCTAssertNil(item.commonRecord)
        XCTAssertNil(item.canonicalRecordID)
        XCTAssertEqual(item.voiceRecord?.id, "456")
    }

    func testNodeDetailFetchesCanonicalIDInsteadOfLegacyVoiceID() async throws {
        var object = nodeJSON(); object.removeValue(forKey: "record")
        let item: BackendStudyLearningRecord = try decode(object)
        let record = try decodeRecord(voiceJSON())
        var requested: [String] = []
        var legacyCalls = 0
        let loader = StudyLearningRecordsLoader(
            context: StudyLearningRecordsContext(identity: StudyLearningRecordsIdentity(
                lifetimeID: UUID(), userID: 7, sessionGeneration: 1, backendGeneration: 0, languageCode: "ko"
            ), studyID: 41, scope: .node),
            isCurrent: { true }, cachedPage: { _ in nil },
            loadPage: { _ in throw StudyLearningRecordsError.unavailable },
            loadVoice: { _, _ in legacyCalls += 1; throw StudyLearningRecordsError.unavailable },
            loadQuestion: { id, _ in requested.append(id); return record }
        )
        let model = StudyLearningRecordDetailViewModel(record: item)
        await model.load(using: loader, view: .original)
        XCTAssertEqual(requested, ["900"])
        XCTAssertEqual(legacyCalls, 0)
        XCTAssertEqual(model.record.commonRecord, record)
        XCTAssertTrue(model.isShowingOriginal)
    }

    func testTranslationStateSurvivesPrivatePublicAndNodeProjection() throws {
        var object = voiceJSON(); var content = voicePayload()
        content["sourceLanguage"] = "en"; content["displayLanguage"] = "ko"; content["translationPending"] = true
        object["voiceRecord"] = content
        let record = try decodeRecord(object)
        XCTAssertTrue(record.translationPending)
        XCTAssertTrue(record.hasTranslatedContent)
        let publicRecord = record.asQuestionBrowseQuestion(author: nil)
        XCTAssertTrue(publicRecord.translationPending)
        XCTAssertEqual(publicRecord.voiceRecord, record.voiceRecord)
    }

    func testAppStateCommonPagesAppendBothTypesWithoutTouchingDraftOrQuota() async throws {
        let fixture = try CommonRecordHTTPFixture(response: pageJSON([voiceJSON()], total: 2)); defer { fixture.close() }
        let app = fixture.makeApp()
        await app.refreshBackendRecords()
        fixture.response = pageJSON([questionJSON()], total: 2, offset: 1)
        await app.loadMoreBackendRecords()
        XCTAssertEqual(Set(app.studyRecords.map(\.id)), ["900", "101"])
        XCTAssertFalse(app.canLoadMoreRecords)
        fixture.assertDraftPreserved(app)
        XCTAssertEqual(fixture.requests.map(\.httpMethod), ["GET", "GET"])
    }

    func testAppStateDropsRecordsAfterOwnerChangesDuringRead() async throws {
        let fixture = try CommonRecordHTTPFixture(response: pageJSON([voiceJSON()])); defer { fixture.close() }
        let app = fixture.makeApp()
        fixture.onRequest = { _ in app.communityProfile = CommonRecordHTTPFixture.profile(id: 8) }
        await app.refreshBackendRecords()
        XCTAssertFalse(app.studyRecords.contains(where: \.isVoiceRecord))
        XCTAssertFalse(fixture.store.loadStudyRecords().contains(where: \.isVoiceRecord))
        fixture.assertDraftPreserved(app)
    }

    func testAppStateDropsDetailAfterLocaleChangesDuringRead() async throws {
        let fixture = try CommonRecordHTTPFixture(response: voiceJSON()); defer { fixture.close() }
        let app = fixture.makeApp()
        fixture.onRequest = { _ in app.settings.appLanguage = .english }
        let record = await app.loadStudyRecordDetail(recordID: "900")
        XCTAssertNil(record)
        fixture.assertDraftPreserved(app)
    }

    func testStaleRecordPageDoesNotLeaveRefreshTaskStuckAfterLocaleChange() async throws {
        let fixture = try CommonRecordHTTPFixture(response: pageJSON([voiceJSON()])); defer { fixture.close() }
        let app = fixture.makeApp()
        fixture.onRequest = { _ in app.settings.appLanguage = .english }
        await app.refreshBackendRecords()
        XCTAssertFalse(app.studyRecords.contains(where: \.isVoiceRecord))
        fixture.onRequest = nil
        await app.refreshBackendRecords()
        XCTAssertEqual(app.studyRecords.map(\.id), ["900"])
        XCTAssertEqual(fixture.requests.count, 2)
        XCTAssertFalse(app.isLoadingRecordPage)
        fixture.assertDraftPreserved(app)
    }

    func testStaleRecordSearchFinishesLoadingWithoutPublishingVoice() async throws {
        let fixture = try CommonRecordHTTPFixture(response: pageJSON([voiceJSON()])); defer { fixture.close() }
        let app = fixture.makeApp()
        fixture.onRequest = { _ in app.settings.appLanguage = .english }
        await app.searchBackendRecords(query: "synthetic")
        XCTAssertFalse(app.isLoadingRecordSearchPage)
        XCTAssertFalse(app.recordSearchResults?.contains(where: \.isVoiceRecord) == true)
        fixture.onRequest = nil
        await app.searchBackendRecords(query: "synthetic")
        XCTAssertEqual(app.recordSearchResults?.map(\.id), ["900"])
        XCTAssertEqual(fixture.requests.count, 2)
        fixture.assertDraftPreserved(app)
    }

    func testAppStateVoiceCannotWriteDraftGradeSkipOrConsumeQuestionQuota() async throws {
        let fixture = try CommonRecordHTTPFixture(response: [:]); defer { fixture.close() }
        let app = fixture.makeApp()
        let record = try decodeRecord(voiceJSON())
        app.updateAnswer("do not save", for: record)
        app.skipPendingQuestion(record)
        await app.gradeRecord(record, answer: "do not submit")
        await app.gradeStudyRoomRecord(record, answer: "do not submit")
        XCTAssertEqual(app.answerDraft(for: record), "")
        XCTAssertEqual(app.notificationRoute(for: record), .recordDetail(recordID: "900"))
        XCTAssertTrue(fixture.requests.isEmpty)
        fixture.assertDraftPreserved(app)
    }

    func testUnansweredVoicePublicToggleDoesNotMutateOrSendRequest() throws {
        let fixture = try CommonRecordHTTPFixture(response: [:]); defer { fixture.close() }
        let app = fixture.makeApp()
        var record = try decodeRecord(voiceJSON()); record.answer = nil
        app.updateStudyRecordPublicity(record, isPublic: true)
        XCTAssertTrue(fixture.requests.isEmpty)
        XCTAssertTrue(fixture.store.loadStudyRecords().isEmpty)
        fixture.assertDraftPreserved(app)
    }

    func testSharedRecordCommentsUseCanonicalIDAndRejectStaleAccountResponse() async throws {
        let fixture = try CommonRecordHTTPFixture(response: ["comments": [], "totalCount": 0, "limit": 30, "offset": 0]); defer { fixture.close() }
        let app = fixture.makeApp()
        fixture.onRequest = { _ in app.communityProfile = CommonRecordHTTPFixture.profile(id: 8) }
        let comments = await app.loadCommunityQuestionComments(questionID: "900", refresh: true)
        XCTAssertNil(comments)
        XCTAssertEqual(fixture.requests.first?.url?.path, "/api/v1/public/questions/900/comments")
        XCTAssertNil(app.cachedCommunityQuestionComments(questionID: "900"))
    }

    func testPublicFeedDiscardsVoiceResponseAfterOwnerChanges() async throws {
        let fixture = try CommonRecordHTTPFixture(response: ["questions": [publicJSON()], "totalCount": 1, "limit": 30])
        defer { fixture.close() }
        let app = fixture.makeApp()
        // A search page does not request ad entitlement or create an ad slot.
        app.communitySearchText = "synthetic"
        fixture.onRequest = { _ in app.communityProfile = CommonRecordHTTPFixture.profile(id: 8) }
        await app.loadCommunityQuestions()
        XCTAssertTrue(app.communityQuestions.isEmpty)
        XCTAssertTrue(app.communityFeedItems.isEmpty)
        let request = try XCTUnwrap(fixture.requests.first)
        XCTAssertEqual(fixture.requests.count, 1)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/v2/public/questions/search")
        let query = try XCTUnwrap(URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(query.first(where: { $0.name == "query" })?.value, "synthetic")
        XCTAssertEqual(query.first(where: { $0.name == "tl" })?.value, "ko")
        XCTAssertEqual(query.first(where: { $0.name == "view" })?.value, "localized")
        fixture.assertDraftPreserved(app)
    }

    func testLikedFeedDiscardsVoiceResponseAfterLocaleChanges() async throws {
        let fixture = try CommonRecordHTTPFixture(response: ["questions": [publicJSON()], "totalCount": 1, "limit": 30])
        defer { fixture.close() }
        let app = fixture.makeApp()
        fixture.onRequest = { _ in app.settings.appLanguage = .english }
        await app.loadLikedCommunityQuestions(query: "synthetic")
        XCTAssertTrue(app.likedCommunityQuestions.isEmpty)
        XCTAssertFalse(app.isLoadingLikedCommunityQuestions)
        XCTAssertEqual(fixture.requests.first?.url?.path, "/api/v1/public/questions/liked")
        fixture.assertDraftPreserved(app)
    }

    func testCommonPrivateAndPublicEndpointsProtectBodyLogs() {
        for path in ["/api/v1/records", "/api/v1/records/900", "/api/v1/records/900/publicity",
                     "/api/v1/public/questions", "/api/v1/public/questions/liked", "/api/v1/public/questions/900/comments",
                     "/api/v2/public/questions", "/api/v2/public/questions/search"] {
            XCTAssertTrue(RemotePushBackendClient.suppressesPrivateLearningBodies(for: URL(string: "https://records.test\(path)")))
        }
        XCTAssertFalse(RemotePushBackendClient.suppressesPrivateLearningBodies(for: URL(string: "https://records.test/api/v1/records-other")))
    }

    func testForbiddenRecordResponseKeepsAuthCodeButNotPrivateErrorText() async throws {
        // ApiErrorEnvelope + ApiError uses a symbolic errorCode and numeric code.
        let fixture = try CommonRecordHTTPFixture(response: ["error": [
            "errorCode": "ACCOUNT_FORBIDDEN", "code": 300, "messageKey": "error.account.forbidden",
            "message": "PRIVATE_SYNTHETIC_ERROR", "debugDescription": "PRIVATE_SYNTHETIC_DIAGNOSTIC",
            "requestId": "synthetic-error-request", "status": 403
        ]])
        defer { fixture.close() }
        fixture.status = 403
        do {
            _ = try await fixture.client.fetchRecord(registration: fixture.registration, recordID: "900", language: .korean, view: .localized)
            XCTFail("Forbidden records must not become successful details")
        } catch RemotePushBackendError.httpStatus(let code, let body, let error) {
            XCTAssertEqual(code, 403)
            XCTAssertEqual(body, "")
            let backendError = try XCTUnwrap(error)
            XCTAssertEqual(backendError.code, "ACCOUNT_FORBIDDEN")
            XCTAssertEqual(backendError.numericCode, 300)
            XCTAssertEqual(backendError.status, 403)
            XCTAssertEqual(backendError.messageKey, "error.account.forbidden")
            XCTAssertFalse(backendError.message.contains("PRIVATE_SYNTHETIC_ERROR"))
            XCTAssertNil(backendError.description)
            XCTAssertNil(backendError.debugDescription)
        }
    }

    func testCommonRecordLabelsCoverKoreanEnglishAndJapanese() {
        let values = AppLanguage.allCases.map { AppStrings(language: $0) }
        XCTAssertEqual(Set(values.map { $0.recordTypeLabel(.voiceTutor) }).count, 3)
        XCTAssertEqual(Set(values.map(\.commonRecordTitle)).count, 3)
        XCTAssertEqual(Set(values.map(\.voiceRecordTutorAnswer)).count, 3)
    }

    private let dateText = "2026-09-01T00:00:00Z"

    private func voicePayload() -> [String: Any] {
        ["kind": "TUTOR_QUESTION", "score": 85, "feedback": "Synthetic feedback", "strengths": ["Synthetic strength"],
         "improvements": ["Synthetic improvement"], "depthSummary": "Synthetic exploration", "sourceLanguage": "ko",
         "requestedLanguage": "ko", "displayLanguage": "ko", "translationPending": false]
    }

    private func voiceJSON() -> [String: Any] {
        ["id": "900", "studyId": 41, "recordType": "VOICE_TUTOR", "voiceRecord": voicePayload(),
         "question": ["question": "Synthetic question", "createdAt": dateText], "answer": "Synthetic answer",
         "topic": "Synthetic topic", "difficulty": 3, "questionStatus": "COMPLETED", "isPublic": false,
         "commentCount": 2, "likeCount": 1, "viewCount": 3]
    }

    private func questionJSON() -> [String: Any] {
        ["id": "101", "studyId": 41, "question": ["question": "Synthetic question", "createdAt": dateText],
         "answer": "Synthetic answer", "topic": "Synthetic topic", "difficulty": 3, "isPublic": false,
         "gradingResult": ["score": 90, "isCorrect": true, "feedback": "Synthetic feedback", "explanation": "Synthetic explanation"]]
    }

    private func publicJSON() -> [String: Any] {
        ["id": "900", "recordType": "VOICE_TUTOR", "voiceRecord": voicePayload(), "question": "Synthetic question",
         "answer": "Synthetic answer", "topic": "Synthetic topic", "difficultyLevel": 3,
         "status": "completed", "source": "voice_tutor", "createdAt": dateText]
    }

    private func pageJSON(_ records: [[String: Any]], total: Int? = nil, offset: Int = 0) -> [String: Any] {
        ["records": records, "totalCount": total ?? records.count, "limit": 30, "offset": offset]
    }

    private func nodeJSON() -> [String: Any] {
        var legacy = voicePayload()
        legacy.merge([
            "id": "456", "recordId": "900", "sessionId": "synthetic-session", "studyId": 41, "topic": "Synthetic topic",
            "difficulty": 3, "createdAt": dateText, "question": "Legacy synthetic question", "answer": "Legacy synthetic answer",
            "questionTurnId": 1, "answerTurnIds": [2], "feedbackTurnIds": [3]
        ]) { _, new in new }
        return ["id": "voice:456", "source": "VOICE_TUTOR", "studyId": 41, "createdAt": dateText,
                "questionRecord": NSNull(), "voiceRecord": legacy, "record": voiceJSON()]
    }

    private func decodeRecord(_ object: [String: Any]) throws -> StudyRecord { try decode(object) }
    private func decode<T: Decodable>(_ object: [String: Any]) throws -> T {
        try RemotePushBackendClient.makeDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: object))
    }
}

@MainActor
final class CommonRecordHTTPFixture {
    let suite = "VoiceCommonRecordTests.\(UUID().uuidString)"
    let host = "\(UUID().uuidString.lowercased()).common-records.test"
    let defaults: UserDefaults
    let store: SettingsStore
    let client: RemotePushBackendClient
    let registration: RemotePushRegistration
    let session: URLSession
    var response: [String: Any]
    var status = 200
    var requests: [URLRequest] = []
    var onRequest: ((URLRequest) -> Void)?
    let activeQuestion = QuestionItem(question: "Synthetic active question", expectedAnswerHint: nil, createdAt: Date(timeIntervalSince1970: 100))

    init(response: [String: Any]) throws {
        self.response = response
        defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        store = SettingsStore(defaults: defaults, usesSecureBackendIdentityStorage: false, preferredAppLanguageProvider: { .korean })
        store.saveSettings(.initial(for: .korean))
        store.saveIsCommunitySignedIn(true)
        store.saveQuestion(activeQuestion)
        store.saveLastAnswer("Synthetic active draft")
        store.saveAnswerDraft("Synthetic record draft", recordID: "999")
        let payload = try JSONSerialization.data(withJSONObject: [
            "device_id": "common-record-fixture", "user_id": 7, "is_anonymous": false, "status": "ACTIVE", "jti": "fixture"
        ])
        let encoded = payload.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        registration = RemotePushRegistration(
            deviceID: "common-record-fixture", clientSecret: "synthetic-secret", apnsToken: "",
            accessToken: "e30.\(encoded).synthetic-signature", accessTokenExpiresAt: Date(timeIntervalSince1970: 4_102_444_800)
        )
        store.saveRemotePushRegistration(registration)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [CommonRecordURLProtocol.self]
        config.timeoutIntervalForRequest = 3
        config.timeoutIntervalForResource = 3
        session = URLSession(configuration: config)
        client = RemotePushBackendClient(baseURL: try XCTUnwrap(URL(string: "https://\(host)")), session: session)
        CommonRecordURLProtocol.install({ [weak self] request in
            guard let self, request.httpMethod == "GET",
                  let path = request.url?.path,
                  path == "/api/v1/records" || path.hasPrefix("/api/v1/records/") ||
                    path.hasPrefix("/api/v1/public/questions/") || path == "/api/v2/public/questions" ||
                    path == "/api/v2/public/questions/search" else {
                XCTFail("This fixture only allows synthetic record reads")
                throw URLError(.unsupportedURL)
            }
            requests.append(request)
            onRequest?(request)
            return (try XCTUnwrap(HTTPURLResponse(url: try XCTUnwrap(request.url), statusCode: status, httpVersion: nil,
                headerFields: ["Content-Type": "application/json"])), try JSONSerialization.data(withJSONObject: self.response))
        }, host: host)
    }

    func makeApp() -> AppState {
        let app = AppState(settingsStore: store, remotePushBackendClient: client, appNotificationEventProvider: CommonRecordNotificationEvents())
        app.communityProfile = Self.profile(id: 7)
        return app
    }

    static func profile(id: Int) -> CommunityUserProfile {
        CommunityUserProfile(id: id, displayName: "Synthetic learner", status: "ACTIVE", provider: "GOOGLE", bio: "", avatarURL: nil)
    }

    func assertDraftPreserved(_ app: AppState, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(app.currentQuestion, activeQuestion, file: file, line: line)
        XCTAssertEqual(app.lastAnswer, "Synthetic active draft", file: file, line: line)
        XCTAssertEqual(store.loadAnswerDraft(recordID: "999"), "Synthetic record draft", file: file, line: line)
        XCTAssertNil(app.gradingResult, file: file, line: line)
        XCTAssertNil(app.questionQuota, file: file, line: line)
    }

    func close() {
        session.invalidateAndCancel()
        CommonRecordURLProtocol.remove(host: host)
        defaults.removePersistentDomain(forName: suite)
    }
}

@MainActor
private struct CommonRecordNotificationEvents: AppNotificationEventProviding {
    func observeAPITrafficLogs(_ handler: @MainActor @escaping (APITrafficLogEntry) -> Void) -> AnyCancellable { AnyCancellable {} }
    func observeBackendUnauthorized(_ handler: @MainActor @escaping (BackendUnauthorizedRequestIdentity) -> Void) -> AnyCancellable { AnyCancellable {} }
}

private final class CommonRecordURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @MainActor @Sendable (URLRequest) throws -> (HTTPURLResponse, Data)
    private static let lock = NSLock()
    private nonisolated(unsafe) static var handlers: [String: Handler] = [:]
    static func install(_ handler: @escaping Handler, host: String) { lock.lock(); defer { lock.unlock() }; handlers[host] = handler }
    static func remove(host: String) { lock.lock(); defer { lock.unlock() }; handlers.removeValue(forKey: host) }
    private static func handler(host: String) -> Handler? { lock.lock(); defer { lock.unlock() }; return handlers[host] }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Task { @MainActor in
            do {
                guard let handler = Self.handler(host: request.url?.host ?? "") else { throw URLError(.unsupportedURL) }
                let (response, data) = try handler(request)
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: data)
                client?.urlProtocolDidFinishLoading(self)
            } catch { client?.urlProtocol(self, didFailWithError: error) }
        }
    }
    override func stopLoading() {}
}
