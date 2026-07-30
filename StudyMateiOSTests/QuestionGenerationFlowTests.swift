import Foundation
import XCTest
@testable import StudyMate

@MainActor
final class QuestionGenerationFlowTests: XCTestCase {
    override func tearDown() {
        QuestionGenerationURLProtocol.requestHandler = nil
        super.tearDown()
    }

    func testDeletedRecordIsNotReinsertedIntoAllStudiesByStaleCommunityPage() {
        let deletedQuestion = CommunityQuestion(
            id: "record-42",
            question: "삭제된 기록",
            answer: "삭제된 답변",
            gradingResult: GradingResult(
                score: 80,
                isCorrect: true,
                feedback: "좋아요.",
                explanation: "설명"
            ),
            topic: "Swift",
            difficultyLevel: 5,
            status: "COMPLETED",
            source: "STUDY",
            createdAt: Date(timeIntervalSince1970: 1_000),
            answeredAt: Date(timeIntervalSince1970: 1_100),
            author: nil
        )
        let response = CommunityQuestionsResponse(
            questions: [deletedQuestion],
            totalCount: 1,
            limit: 20,
            offset: 0
        )
        var state = CommunityFeedStateStore()
        state.applyPage(response, offset: 0, reset: true)

        state.removeQuestion(id: deletedQuestion.id)
        state.applyPage(response, offset: 0, reset: true)

        XCTAssertFalse(state.questions.contains { $0.id == deletedQuestion.id })
        XCTAssertEqual(state.totalCount, 0)
    }

    func testClearedRecordsAreNotReinsertedIntoAllStudiesByStaleCommunityPage() {
        let questions = (1...3).map { index in
            CommunityQuestion(
                id: "record-\(index)",
                question: "삭제된 기록 \(index)",
                answer: "삭제된 답변",
                gradingResult: nil,
                topic: "Swift",
                difficultyLevel: 5,
                status: "COMPLETED",
                source: "STUDY",
                createdAt: Date(timeIntervalSince1970: TimeInterval(index)),
                answeredAt: nil,
                author: nil
            )
        }
        let response = CommunityQuestionsResponse(
            questions: questions,
            totalCount: questions.count,
            limit: 20,
            offset: 0
        )
        var state = CommunityFeedStateStore()
        state.applyPage(response, offset: 0, reset: true)

        state.removeQuestions(ids: Set(questions.map(\.id)))
        state.applyPage(response, offset: 0, reset: true)

        XCTAssertTrue(state.questions.isEmpty)
        XCTAssertEqual(state.totalCount, 0)
    }

    func testServiceAvailabilityUsesMonitoringEndpointAndBackendLanguageCode() async throws {
        let statusURL = URL(string: "https://monitoring.example/status/api/v1/service-status")!
        let client = makeClient(serviceStatusURL: statusURL) { request in
            XCTAssertEqual(request.url, statusURL)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept-Language"), "ko")
            return Self.response(
                for: request,
                statusCode: 200,
                body: #"{"status":"OPERATIONAL","checkedAt":"2026-07-28T00:00:00Z"}"#
            )
        }

        let availability = await client.fetchServiceAvailability(language: .korean)

        XCTAssertEqual(availability?.status, .operational)
        XCTAssertFalse(try XCTUnwrap(availability).isUnderMaintenance)
    }

    func testServiceAvailabilityUsesValidBodyRegardlessOfHTTPStatus() async throws {
        let statusURL = URL(string: "https://monitoring.example/status/api/v1/service-status")!
        let statusCodes = [200, 204, 301, 400, 401, 403, 404, 429, 500, 503]

        for statusCode in statusCodes {
            let client = makeClient(serviceStatusURL: statusURL) { request in
                Self.response(
                    for: request,
                    statusCode: statusCode,
                    body: #"{"status":"MAINTENANCE","title":"Maintenance","checkedAt":"invalid","retryAfterSeconds":"invalid"}"#
                )
            }
            let unauthorizedNotifications = LockedRequestCounter()
            let observer = NotificationCenter.default.addObserver(
                forName: BackendAuthorizationNotification.didReceiveUnauthorized,
                object: client,
                queue: nil
            ) { _ in
                unauthorizedNotifications.increment()
            }

            let availability = await client.fetchServiceAvailability(language: .english)

            NotificationCenter.default.removeObserver(observer)
            XCTAssertEqual(availability?.status, .maintenance, "HTTP \(statusCode)")
            XCTAssertEqual(availability?.title, "Maintenance", "HTTP \(statusCode)")
            XCTAssertEqual(unauthorizedNotifications.value, 0, "HTTP \(statusCode)")
        }
    }

    func testServiceAvailabilityIgnoresMissingOrUnknownStatus() async {
        for body in [
            #"{"message":"monitoring authentication required"}"#,
            #"{"status":"DEGRADED"}"#
        ] {
            let client = makeClient { request in
                Self.response(for: request, statusCode: 401, body: body)
            }

            let availability = await client.fetchServiceAvailability(language: .english)

            XCTAssertNil(availability)
        }
    }

    func testDeveloperDebugModeDoesNotRequestMonitoringStatus() async throws {
        let suiteName = "DeveloperStatusBypassTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveDeveloperAccessUnlocked(true)
        store.saveIsDebuggingEnabled(true)
        store.saveHasCompletedOnboarding(false)
        let client = makeClient(
            serviceStatusURL: URL(
                string: "https://monitoring.example/status/api/v1/service-status"
            )!
        ) { request in
            XCTFail("Debug mode must not request monitoring status: \(request.url?.absoluteString ?? "-")")
            return Self.response(
                for: request,
                statusCode: 500,
                body: #"{"message":"unexpected request"}"#
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.start()
        await appState.refreshServiceAvailability()

        XCTAssertTrue(appState.isDebuggingEnabled)
        XCTAssertFalse(appState.isCheckingServiceAvailability)
    }

    func testBackendMaintenanceErrorDoesNotReplaceMonitoringServiceStatus() {
        let error = RemotePushBackendError.httpStatus(
            503,
            "",
            BackendAPIError(
                code: "SERVICE_UNDER_MAINTENANCE",
                numericCode: 903,
                debugDescription: nil,
                message: "Maintenance in progress",
                requestID: nil,
                metadata: nil
            )
        )

        XCTAssertNil(BackendErrorPresentationPolicy.serviceAvailability(for: error))
    }

    func testCreateQuestionSendsIdempotencyKeyAndDecodesAcceptedProcess() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/api/v1/studies/16/questions")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Idempotency-Key"), "request-key-1")
            return Self.response(
                for: request,
                statusCode: 202,
                body: """
                {
                  "correlationId": "correlation-1",
                  "studyId": "16",
                  "topicId": "16",
                  "status": "QUEUED",
                  "pollAfterMs": 250,
                  "submittedAt": "2026-07-27T12:00:00Z"
                }
                """
            )
        }

        let accepted = try await client.createQuestion(
            registration: Self.registration,
            studyID: 16,
            idempotencyKey: "request-key-1"
        )

        XCTAssertEqual(accepted.correlationID, "correlation-1")
        XCTAssertEqual(accepted.status, .queued)
        XCTAssertEqual(accepted.pollAfterMilliseconds, 250)
    }

    func testFetchProcessUsesCorrelationIDAndDecodesTerminalFailure() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(
                request.url?.path,
                "/api/v1/question-processes/correlation-1"
            )
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "correlationId": "correlation-1",
                  "status": "FAILED",
                  "currentStep": "GENERATING",
                  "terminal": true,
                  "pollAfterMs": null,
                  "questionId": null,
                  "question": null,
                  "failedStep": "GENERATING",
                  "error": {
                    "code": "QUESTION_GENERATION_FAILED",
                    "message": "Question generation failed.",
                    "retryable": false
                  },
                  "updatedAt": "2026-07-27T12:00:01Z",
                  "completedAt": null
                }
                """
            )
        }

        let process = try await client.fetchQuestionGenerationProcess(
            registration: Self.registration,
            correlationID: "correlation-1"
        )

        XCTAssertTrue(process.terminal)
        XCTAssertEqual(process.status, .failed)
        XCTAssertEqual(process.failedStep, .generating)
        XCTAssertEqual(process.error?.retryable, false)
    }

    func testFetchAnswerGradingProcessUsesCorrelationIDAndEventCursor() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/answer-processes/grading-77")
            XCTAssertEqual(
                request.url?.absoluteString,
                "https://example.test/api/v1/answer-processes/grading-77?after=4"
            )
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "correlationId": "grading-77",
                  "recordId": "record-77",
                  "status": "COMPLETED",
                  "terminal": true,
                  "pollAfterMs": null,
                  "events": [
                    {
                      "id": 5,
                      "recordId": "record-77",
                      "correlationId": "grading-77",
                      "status": "COMPLETED",
                      "errorMessage": null,
                      "occurredAt": "2026-07-27T12:00:01Z"
                    }
                  ],
                  "errorMessage": null,
                  "updatedAt": "2026-07-27T12:00:01Z"
                }
                """
            )
        }

        let process = try await client.fetchAnswerGradingProcess(
            registration: Self.registration,
            correlationID: "grading-77",
            afterEventID: 4
        )

        XCTAssertEqual(process.correlationID, "grading-77")
        XCTAssertEqual(process.recordID, "record-77")
        XCTAssertTrue(process.terminal)
        XCTAssertNil(process.pollAfterMilliseconds)
        XCTAssertEqual(process.events.map(\.id), [5])
        XCTAssertEqual(process.events.first?.status, .completed)
    }

    func testSigningOutStopsAnswerGradingProcessPolling() async {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("Unable to create isolated user defaults.")
            return
        }
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let pollCounter = LockedRequestCounter()
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/api/v1/records/record-sign-out/answer"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "id": "record-sign-out",
                      "question": {
                        "question": "How should polling react to sign-out?",
                        "expectedAnswerHint": null,
                        "createdAt": "2026-07-28T00:00:00Z"
                      },
                      "answer": "It should stop.",
                      "topic": "Concurrency",
                      "difficulty": 5,
                      "gradingRequestId": "grading-sign-out",
                      "gradingStatus": "QUEUED"
                    }
                    """
                )
            case ("GET", "/api/v1/answer-processes/grading-sign-out"):
                pollCounter.increment()
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "correlationId": "grading-sign-out",
                      "recordId": "record-sign-out",
                      "status": "QUEUED",
                      "terminal": false,
                      "pollAfterMs": 1000,
                      "events": [],
                      "errorMessage": null,
                      "updatedAt": "2026-07-28T00:00:00Z"
                    }
                    """
                )
            case ("POST", "/api/v1/auth/logout"):
                return Self.response(for: request, statusCode: 200, body: "{}")
            default:
                XCTFail("Unexpected request: \(request.httpMethod ?? "-") \(request.url?.path ?? "-")")
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveIsCommunitySignedIn(true)
        store.saveRemotePushRegistration(
            RemotePushRegistration(
                deviceID: "device-1",
                clientSecret: "client-secret",
                apnsToken: "",
                accessToken: "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
                accessTokenExpiresAt: Date().addingTimeInterval(3_600)
            )
        )
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)
        let record = StudyRecord(
            id: "record-sign-out",
            question: QuestionItem(
                question: "How should polling react to sign-out?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            topic: "Concurrency",
            difficulty: .intermediate
        )

        let gradingTask = Task { @MainActor in
            await appState.gradeRecord(record, answer: "It should stop.")
        }
        for _ in 0..<100 where pollCounter.value == 0 {
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(
            pollCounter.value,
            1,
            "Polling did not start. error=\(appState.errorMessage ?? "nil"), status=\(appState.statusMessage ?? "nil")"
        )

        appState.signOutFromCommunity()
        await gradingTask.value
        try? await Task.sleep(nanoseconds: 200_000_000)

        XCTAssertEqual(
            pollCounter.value,
            1,
            "Polling continued or never started. error=\(appState.errorMessage ?? "nil"), status=\(appState.statusMessage ?? "nil")"
        )
        XCTAssertFalse(appState.isGradingAnswer)
        XCTAssertNil(appState.answerGradingStatusMessage)
    }

    func testLeavingAnswerScreenStopsThreeSecondPollingImmediately() async {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("Unable to create isolated user defaults.")
            return
        }
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let pollCounter = LockedRequestCounter()
        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("POST", "/api/v1/records/record-screen-exit/answer"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "id": "record-screen-exit",
                      "question": {
                        "question": "What should happen after leaving the screen?",
                        "expectedAnswerHint": null,
                        "createdAt": "2026-07-28T00:00:00Z"
                      },
                      "answer": "Polling should stop.",
                      "topic": "Concurrency",
                      "difficulty": 5,
                      "gradingRequestId": "grading-screen-exit",
                      "gradingStatus": "QUEUED"
                    }
                    """
                )
            case ("GET", "/api/v1/answer-processes/grading-screen-exit"):
                pollCounter.increment()
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "correlationId": "grading-screen-exit",
                      "recordId": "record-screen-exit",
                      "status": "QUEUED",
                      "terminal": false,
                      "pollAfterMs": 250,
                      "events": [],
                      "errorMessage": null,
                      "updatedAt": "2026-07-28T00:00:00Z"
                    }
                    """
                )
            default:
                XCTFail("Unexpected request: \(request.httpMethod ?? "-") \(request.url?.path ?? "-")")
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveRemotePushRegistration(
            RemotePushRegistration(
                deviceID: "device-1",
                clientSecret: "client-secret",
                apnsToken: "",
                accessToken: "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
                accessTokenExpiresAt: Date().addingTimeInterval(3_600)
            )
        )
        let sleepProvider = BlockingRecordingAppSleepProvider()
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client,
            appSleepProvider: sleepProvider
        )
        let record = StudyRecord(
            id: "record-screen-exit",
            question: QuestionItem(
                question: "What should happen after leaving the screen?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            topic: "Concurrency",
            difficulty: .intermediate
        )
        let ownerID = UUID().uuidString
        let gradingTask = Task { @MainActor in
            await appState.gradeRecord(
                record,
                answer: "Polling should stop.",
                pollingOwnerID: ownerID
            )
        }

        for _ in 0..<100 {
            if !(await sleepProvider.requestedNanoseconds()).isEmpty {
                break
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }

        let requestedNanoseconds = await sleepProvider.requestedNanoseconds()
        XCTAssertEqual(requestedNanoseconds, [3_000_000_000])
        XCTAssertEqual(pollCounter.value, 1)
        let persistedQueuedRecord = try? XCTUnwrap(
            store.loadStudyRecords().first(where: { $0.id == record.id })
        )
        XCTAssertEqual(persistedQueuedRecord?.answer, "Polling should stop.")
        XCTAssertEqual(persistedQueuedRecord?.gradingRequestID, "grading-screen-exit")
        XCTAssertEqual(persistedQueuedRecord?.gradingStatus, .queued)

        appState.cancelAnswerGradingPolling(
            ownerID: ownerID,
            reason: "test-screen-disappeared"
        )
        await gradingTask.value
        try? await Task.sleep(nanoseconds: 50_000_000)

        XCTAssertEqual(pollCounter.value, 1)
        XCTAssertFalse(appState.isGradingAnswer)
        XCTAssertNil(appState.answerGradingStatusMessage)
        XCTAssertTrue(appState.isAnswerGradingInProgress(for: persistedQueuedRecord))
        XCTAssertEqual(
            appState.gradingPresentationMessage(for: persistedQueuedRecord),
            appState.strings.gradingQueued
        )
        var requestOnlyRecord = persistedQueuedRecord
        requestOnlyRecord?.gradingStatus = nil
        XCTAssertTrue(appState.isAnswerGradingInProgress(for: requestOnlyRecord))
        XCTAssertEqual(
            appState.gradingPresentationMessage(for: requestOnlyRecord),
            appState.strings.gradingQueued
        )
    }

    func testReopeningStudyRoomRestoresPersistedAnswerAndGradingState() async throws {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let category = StudyCategory(
            id: "11",
            title: "운영체제",
            difficulty: .intermediate
        )
        let question = QuestionItem(
            question: "프로세스와 스레드의 차이는 무엇인가요?",
            expectedAnswerHint: nil,
            createdAt: Date(timeIntervalSince1970: 1_753_660_800)
        )
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: category.title,
                difficulty: category.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15,
                studyCategories: [category],
                selectedStudyCategoryID: category.id
            )
        )
        store.saveStudyRecord(
            StudyRecord(
                id: "record-11",
                studyID: 11,
                question: question,
                topic: category.title,
                difficulty: category.difficulty
            )
        )
        store.saveQuestion(question)
        store.saveLastAnswer("")
        store.saveRemotePushRegistration(Self.signedInRegistration)

        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/studies")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "studies": [
                    {
                      "id": 11,
                      "topic": "운영체제",
                      "difficultyLevel": 5,
                      "intervalMinutes": 15,
                      "enabled": true,
                      "activeForQuestions": true,
                      "notificationSound": null,
                      "customPrompt": "짧게",
                      "openaiModel": "gpt-5.4",
                      "maxHistoryCount": 100,
                      "nextDueAt": null,
                      "lastSentAt": null,
                      "lastError": null,
                      "pendingQuestion": {
                        "id": "record-11",
                        "studyId": 11,
                        "question": {
                          "question": "프로세스와 스레드의 차이는 무엇인가요?",
                          "expectedAnswerHint": null,
                          "createdAt": "2025-07-28T00:00:00Z"
                        },
                        "answer": "프로세스는 독립된 메모리를 갖고 스레드는 메모리를 공유합니다.",
                        "gradingResult": null,
                        "topic": "운영체제",
                        "difficulty": 5,
                        "answeredAt": "2025-07-28T00:01:00Z",
                        "isPublic": true,
                        "gradingRequestId": "grading-11",
                        "gradingStatus": "FAILED",
                        "gradingError": "일시적인 채점 오류"
                      },
                      "createdAt": "2025-07-28T00:00:00Z",
                      "updatedAt": "2025-07-28T00:01:00Z"
                    }
                  ],
                  "totalCount": 1,
                  "limit": 500,
                  "offset": 0,
                  "serverTime": "2025-07-28T00:01:00Z"
                }
                """
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        await appState.prepareStudyRoom(categoryID: category.id)

        let restored = try XCTUnwrap(
            appState.studyRoomRecordForDisplay(categoryID: category.id)
        )
        XCTAssertEqual(
            restored.answer,
            "프로세스는 독립된 메모리를 갖고 스레드는 메모리를 공유합니다."
        )
        XCTAssertEqual(restored.gradingRequestID, "grading-11")
        XCTAssertEqual(restored.gradingStatus, .failed)
        XCTAssertEqual(store.loadStudyRecords().first?.answer, restored.answer)
        XCTAssertEqual(appState.lastAnswer, restored.answer)
    }

    func testReopeningStudyRoomResumesPersistedAnswerGrading() async throws {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let category = StudyCategory(
            id: "12",
            title: "데이터베이스",
            difficulty: .intermediate
        )
        let question = QuestionItem(
            question: "트랜잭션 격리 수준을 설명하세요.",
            expectedAnswerHint: nil,
            createdAt: Date(timeIntervalSince1970: 1_753_660_800)
        )
        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: category.title,
                difficulty: category.difficulty,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15,
                studyCategories: [category],
                selectedStudyCategoryID: category.id
            )
        )
        store.saveStudyRecord(
            StudyRecord(
                id: "record-12",
                studyID: 12,
                question: question,
                answer: "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다.",
                topic: category.title,
                difficulty: category.difficulty,
                answeredAt: Date(timeIntervalSince1970: 1_753_660_860),
                gradingRequestID: "grading-12",
                gradingStatus: .judging,
                gradingLastEventID: 4
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let gradingCursorQuery = LockedValue<String?>(nil)

        let client = makeClient { request in
            switch (request.httpMethod, request.url?.path) {
            case ("GET", "/api/v1/studies"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "studies": [
                        {
                          "id": 12,
                          "topic": "데이터베이스",
                          "difficultyLevel": 5,
                          "intervalMinutes": 15,
                          "enabled": true,
                          "activeForQuestions": true,
                          "notificationSound": null,
                          "customPrompt": "짧게",
                          "openaiModel": "gpt-5.4",
                          "maxHistoryCount": 100,
                          "nextDueAt": null,
                          "lastSentAt": null,
                          "lastError": null,
                          "pendingQuestion": {
                            "id": "record-12",
                            "studyId": 12,
                            "question": {
                              "question": "트랜잭션 격리 수준을 설명하세요.",
                              "expectedAnswerHint": null,
                              "createdAt": "2025-07-28T00:00:00Z"
                            },
                            "answer": "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다.",
                            "gradingResult": null,
                            "topic": "데이터베이스",
                            "difficulty": 5,
                            "answeredAt": "2025-07-28T00:01:00Z",
                            "isPublic": true,
                            "gradingRequestId": "grading-12",
                            "gradingStatus": "JUDGING",
                            "gradingError": null
                          },
                          "createdAt": "2025-07-28T00:00:00Z",
                          "updatedAt": "2025-07-28T00:01:00Z"
                        }
                      ],
                      "totalCount": 1,
                      "limit": 500,
                      "offset": 0,
                      "serverTime": "2025-07-28T00:01:00Z"
                    }
                    """
                )
            case ("GET", "/api/v1/answer-processes/grading-12"):
                let queryItems = URLComponents(
                    url: request.url!,
                    resolvingAgainstBaseURL: false
                )?.queryItems
                gradingCursorQuery.set(
                    queryItems?.first(where: { $0.name == "after" })?.value
                )
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "correlationId": "grading-12",
                      "recordId": "record-12",
                      "status": "COMPLETED",
                      "terminal": true,
                      "pollAfterMs": null,
                      "events": [
                        {
                          "id": 5,
                          "recordId": "record-12",
                          "correlationId": "grading-12",
                          "status": "COMPLETED",
                          "errorMessage": null,
                          "occurredAt": "2025-07-28T00:02:00Z"
                        }
                      ],
                      "errorMessage": null,
                      "updatedAt": "2025-07-28T00:02:00Z"
                    }
                    """
                )
            case ("GET", "/api/v1/records/record-12"):
                return Self.response(
                    for: request,
                    statusCode: 200,
                    body: """
                    {
                      "id": "record-12",
                      "studyId": 12,
                      "question": {
                        "question": "트랜잭션 격리 수준을 설명하세요.",
                        "expectedAnswerHint": null,
                        "createdAt": "2025-07-28T00:00:00Z"
                      },
                      "answer": "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다.",
                      "gradingResult": {
                        "score": 91,
                        "correct": true,
                        "feedback": "핵심을 잘 설명했습니다.",
                        "explanation": "격리 수준별 현상까지 연결하면 더 좋습니다."
                      },
                      "topic": "데이터베이스",
                      "difficulty": 5,
                      "answeredAt": "2025-07-28T00:01:00Z",
                      "isPublic": true,
                      "gradingRequestId": "grading-12",
                      "gradingStatus": "COMPLETED",
                      "gradingError": null
                    }
                    """
                )
            default:
                return Self.response(for: request, statusCode: 500, body: "{}")
            }
        }
        let sleepProvider = RecordingAppSleepProvider()
        let appState = AppState(
            settingsStore: store,
            remotePushBackendClient: client,
            appSleepProvider: sleepProvider
        )

        await appState.prepareStudyRoom(
            categoryID: category.id,
            gradingPollingOwnerID: "study-view-12"
        )

        let restored = try XCTUnwrap(
            appState.studyRoomRecordForDisplay(categoryID: category.id)
        )
        XCTAssertEqual(
            restored.answer,
            "격리 수준은 동시성 문제와 일관성 사이의 균형을 정합니다."
        )
        XCTAssertEqual(restored.gradingResult?.score, 91)
        XCTAssertEqual(restored.gradingStatus, .completed)
        XCTAssertEqual(restored.gradingLastEventID, 5)
        XCTAssertEqual(
            gradingCursorQuery.value,
            "4",
            "Reopening must continue after the last persisted event instead of reading the stream from zero."
        )
        XCTAssertFalse(appState.isGradingAnswer)
        let replayDelays = await sleepProvider.requestedNanoseconds()
        XCTAssertEqual(
            replayDelays,
            [],
            "Reopening must use the process snapshot instead of replaying historical grading events."
        )
    }

    func testJapaneseLanguageUsesJapaneseLocaleAndBackendCode() {
        XCTAssertEqual(AppLanguage.japanese.locale.identifier, "ja_JP")
        XCTAssertEqual(AppLanguage.japanese.backendCode, "ja")
        XCTAssertEqual(AppLanguage.japanese.displayName, "日本語")
        XCTAssertEqual(AppStrings(language: .japanese).showOriginal, "原文を見る")
        XCTAssertEqual(AppStrings(language: .japanese).showTranslation, "翻訳を見る")
        XCTAssertEqual(AppStrings(language: .japanese).translatedIntoLanguage, "日本語に翻訳済み")
    }

    func testContentLanguageRecognizerSupportsKoreanEnglishAndJapanese() {
        XCTAssertEqual(
            ContentLanguageRecognizer.detect("이 답변은 한국어로 작성되었습니다.", fallback: .english),
            "ko"
        )
        XCTAssertEqual(
            ContentLanguageRecognizer.detect("This answer is written in English.", fallback: .korean),
            "en"
        )
        XCTAssertEqual(
            ContentLanguageRecognizer.detect("この回答は日本語で書かれています。", fallback: .english),
            "ja"
        )
        XCTAssertEqual(ContentLanguageRecognizer.detect("OK", fallback: .japanese), "ja")
    }

    func testLocalizationMetadataDecodesMixedSourceAndDisplayLanguages() throws {
        let data = Data(
            """
            {
              "question": {
                "sourceLanguage": "en",
                "requestedLanguage": "ja",
                "displayLanguage": "ja",
                "translationState": "TRANSLATED",
                "isTranslated": true,
                "originalAvailable": true,
                "translationReason": "EXPLICIT_TL"
              },
              "answer": {
                "sourceLanguage": "ko",
                "requestedLanguage": "ja",
                "displayLanguage": "ko",
                "translationState": "PENDING",
                "isTranslated": false,
                "originalAvailable": true,
                "translationReason": "EXPLICIT_TL"
              },
              "aiResponse": null
            }
            """.utf8
        )

        let metadata = try JSONDecoder().decode(RecordLocalizationMetadata.self, from: data)

        XCTAssertTrue(metadata.containsTranslation)
        XCTAssertTrue(metadata.containsPendingTranslation)
        XCTAssertEqual(metadata.question.sourceLanguage, "en")
        XCTAssertEqual(metadata.question.displayLanguage, "ja")
        XCTAssertEqual(metadata.answer?.sourceLanguage, "ko")
        XCTAssertEqual(metadata.answer?.displayLanguage, "ko")
    }

    func testLocalizationMetadataDecodesLegacyTranslatedField() throws {
        let data = Data(
            """
            {
              "sourceLanguage": "ko",
              "requestedLanguage": "ko",
              "displayLanguage": "ko",
              "translationState": "ORIGINAL",
              "translated": false,
              "originalAvailable": false,
              "translationReason": "EXPLICIT_TL"
            }
            """.utf8
        )

        let metadata = try JSONDecoder().decode(ContentLocalizationMetadata.self, from: data)

        XCTAssertFalse(metadata.isTranslated)
        XCTAssertEqual(metadata.displayLanguage, "ko")
    }

    func testSubmittedBackendAnswerOverridesStaleEditableDraft() throws {
        let suiteName = "SubmittedAnswerTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        let record = StudyRecord(
            id: "submitted-answer",
            question: QuestionItem(
                question: "트랜잭션이란 무엇인가요?",
                expectedAnswerHint: nil,
                createdAt: Date()
            ),
            answer: "서버가 수락한 최종 답변",
            topic: "데이터베이스",
            difficulty: .intermediate,
            gradingRequestID: "grading-submitted",
            gradingStatus: .judging
        )
        store.saveAnswerDraft("제출 전에 남아 있던 편집 초안", recordID: record.id)
        let appState = AppState(settingsStore: store)

        XCTAssertEqual(appState.answerDraft(for: record), "서버가 수락한 최종 답변")
        XCTAssertFalse(StudyAnswerPresentationPolicy.shouldShowEditor(for: record))
        XCTAssertEqual(
            StudyAnswerPresentationPolicy.submittedAnswer(for: record),
            "서버가 수락한 최종 답변"
        )

        var unansweredRecord = record
        unansweredRecord.answer = nil
        unansweredRecord.gradingRequestID = nil
        unansweredRecord.gradingStatus = nil
        XCTAssertTrue(StudyAnswerPresentationPolicy.shouldShowEditor(for: unansweredRecord))
        XCTAssertEqual(appState.answerDraft(for: unansweredRecord), "제출 전에 남아 있던 편집 초안")
    }

    func testLearningRhythmUpdatesSettingsDraftAndSurvivesRelaunch() throws {
        let suiteName = "LearningRhythmTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        let appState = AppState(settingsStore: store)

        appState.setTimerInterval(47)

        XCTAssertEqual(appState.settings.intervalMinutes, 47)
        XCTAssertEqual(appState.draftSettings.intervalMinutes, 47)
        XCTAssertEqual(store.loadSettings().intervalMinutes, 47)

        let relaunchedState = AppState(settingsStore: store)
        XCTAssertEqual(relaunchedState.settings.intervalMinutes, 47)
        XCTAssertEqual(relaunchedState.draftSettings.intervalMinutes, 47)
    }

    func testBackendSettingsRefreshDoesNotOverwritePersistedLearningRhythm() async throws {
        let suiteName = "BackendLearningRhythmTests-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            defaults.removePersistentDomain(forName: suiteName)
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let store = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        store.saveSettings(
            StudySettings(
                topic: "Redis",
                difficulty: .level6,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 47,
                studyCategories: [StudyCategory(title: "Redis", difficulty: .level6)]
            )
        )
        store.saveRemotePushRegistration(Self.signedInRegistration)
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.path, "/api/v1/settings")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "topic": "Redis",
                  "difficultyLevel": 6,
                  "intervalMinutes": 15,
                  "enabled": true,
                  "notificationSound": "default",
                  "customPrompt": "",
                  "appLanguage": "ko",
                  "openAIModel": "\(StudySettings.defaultOpenAIModel)",
                  "maxHistoryCount": 100,
                  "isQuestionPublic": true,
                  "openAIKeyConfigured": true
                }
                """
            )
        }
        let appState = AppState(settingsStore: store, remotePushBackendClient: client)

        appState.beginSettingsEditing()
        await appState.loadBackendSettingsForEditing()

        XCTAssertEqual(appState.settings.intervalMinutes, 47)
        XCTAssertEqual(appState.draftSettings.intervalMinutes, 47)
        XCTAssertEqual(store.loadSettings().intervalMinutes, 47)
    }

    func testPendingProcessSurvivesSettingsStoreRecreation() {
        let suiteName = "QuestionGenerationFlowTests-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            XCTFail("Unable to create isolated user defaults.")
            return
        }
        defer {
            defaults.removePersistentDomain(forName: suiteName)
        }
        let databaseURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(suiteName).sqlite")
        defer {
            try? FileManager.default.removeItem(at: databaseURL)
        }

        let pending = PendingQuestionGenerationProcess(
            idempotencyKey: "request-key-1",
            correlationID: "correlation-1",
            studyID: 16,
            studyCategoryID: "study-16",
            submittedAt: Date(timeIntervalSince1970: 1_785_153_600)
        )
        SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        ).savePendingQuestionGenerationProcess(pending)

        let relaunchedStore = SettingsStore(
            defaults: defaults,
            recordDatabaseURL: databaseURL,
            usesSecureBackendIdentityStorage: false
        )
        XCTAssertEqual(relaunchedStore.loadPendingQuestionGenerationProcess(), pending)

        relaunchedStore.savePendingQuestionGenerationProcess(nil)
        XCTAssertNil(relaunchedStore.loadPendingQuestionGenerationProcess())
    }

    func testQuotaExceededStopsQuestionGenerationRetryLoop() {
        let error = RemotePushBackendError.httpStatus(
            403,
            "",
            BackendAPIError(
                code: "QUOTA_EXCEEDED",
                numericCode: 305,
                message: "Monthly question limit reached.",
                status: 403
            )
        )

        XCTAssertTrue(AppErrorHandlingUseCase().isPermanentBackendOperationError(error))
    }

    func testSettingsRequestOmitsClientDefaultPrompt() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.httpMethod, "PUT")
            XCTAssertEqual(request.url?.path, "/api/v1/settings")
            let bodyData = try Self.bodyData(from: request)
            let body = try XCTUnwrap(
                JSONSerialization.jsonObject(with: bodyData) as? [String: Any]
            )
            XCTAssertNil(body["customPrompt"])
            let schedules = try XCTUnwrap(body["schedules"] as? [[String: Any]])
            XCTAssertNil(try XCTUnwrap(schedules.first)["customPrompt"])
            return Self.response(for: request, statusCode: 200, body: "{}")
        }

        try await client.updateSchedule(
            registration: Self.registration,
            settings: StudySettings(
                topic: "Swift",
                difficulty: .level5,
                customPrompt: StudySettings.defaultCustomPrompt,
                intervalMinutes: 15
            ),
            apiKey: nil,
            enabled: true
        )
    }

    func testAppUpdateCheckSendsInstalledVersionAndDecodesForcedCampaign() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/app-updates/check")
            XCTAssertEqual(request.httpMethod, "POST")
            let body = try JSONSerialization.jsonObject(with: Self.bodyData(from: request)) as? [String: Any]
            XCTAssertEqual(body?["platform"] as? String, "ios")
            let expectedVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            let expectedBuild = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
            XCTAssertEqual(body?["currentVersion"] as? String, expectedVersion)
            XCTAssertEqual(body?["currentBuild"] as? String, expectedBuild)
            XCTAssertEqual(body?["language"] as? String, "ja")
            return Self.response(
                for: request,
                statusCode: 200,
                body: """
                {
                  "updateAvailable": true,
                  "shouldPresent": true,
                  "campaignId": 9,
                  "mode": "FORCE",
                  "targetVersion": "2.0.0",
                  "targetBuild": "100",
                  "title": "更新が必要です",
                  "message": "続けるには更新してください。",
                  "appStoreUrl": "https://apps.apple.com/app/id6774108938"
                }
                """
            )
        }

        let decision = try await client.checkAppUpdate(
            registration: Self.registration,
            language: .japanese
        )

        XCTAssertTrue(decision.updateAvailable)
        XCTAssertTrue(decision.shouldPresent)
        XCTAssertEqual(decision.mode, .force)
        XCTAssertEqual(decision.campaignID, 9)
    }

    func testAppUpdateDismissalEventUsesCampaignEndpoint() async throws {
        let client = makeClient { request in
            XCTAssertEqual(request.url?.path, "/api/v1/app-updates/42/events")
            XCTAssertEqual(request.httpMethod, "POST")
            let body = try JSONSerialization.jsonObject(with: Self.bodyData(from: request)) as? [String: Any]
            XCTAssertEqual(body?["event"] as? String, "DISMISSED")
            return Self.response(for: request, statusCode: 204, body: "")
        }

        try await client.recordAppUpdateEvent(
            registration: Self.registration,
            campaignID: 42,
            event: .dismissed
        )
    }

    private func makeClient(
        serviceStatusURL: URL = RemotePushBackendClient.defaultServiceStatusURL,
        handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)
    ) -> RemotePushBackendClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [QuestionGenerationURLProtocol.self]
        QuestionGenerationURLProtocol.requestHandler = handler
        return RemotePushBackendClient(
            baseURL: URL(string: "https://example.test")!,
            serviceStatusURL: serviceStatusURL,
            session: URLSession(configuration: configuration)
        )
    }

    private static let registration = RemotePushRegistration(
        deviceID: "device-1",
        clientSecret: "client-secret",
        apnsToken: ""
    )

    private static let signedInRegistration = RemotePushRegistration(
        deviceID: "device-1",
        clientSecret: "client-secret",
        apnsToken: "",
        accessToken: "eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJkZXZpY2VfaWQiOiJkZXZpY2UtMSIsImlzX2Fub255bW91cyI6ZmFsc2UsInN0YXR1cyI6IkFDVElWRSJ9.",
        accessTokenExpiresAt: Date().addingTimeInterval(3_600)
    )

    private static func response(
        for request: URLRequest,
        statusCode: Int,
        body: String
    ) -> (HTTPURLResponse, Data) {
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: statusCode,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )!
        return (response, Data(body.utf8))
    }

    private static func bodyData(from request: URLRequest) throws -> Data {
        if let body = request.httpBody {
            return body
        }
        let stream = try XCTUnwrap(request.httpBodyStream)
        stream.open()
        defer { stream.close() }

        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count >= 0 else {
                throw try XCTUnwrap(stream.streamError)
            }
            if count == 0 {
                break
            }
            data.append(contentsOf: buffer.prefix(count))
        }
        return data
    }
}

private final class QuestionGenerationURLProtocol: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var requestHandler:
        ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let protocolReference = UncheckedSendableBox(self)
        Task { @MainActor in
            let protocolInstance = protocolReference.value
            guard let requestHandler = Self.requestHandler else {
                protocolInstance.client?.urlProtocol(
                    protocolInstance,
                    didFailWithError: URLError(.badServerResponse)
                )
                return
            }

            do {
                let (response, data) = try requestHandler(protocolInstance.request)
                protocolInstance.client?.urlProtocol(
                    protocolInstance,
                    didReceive: response,
                    cacheStoragePolicy: .notAllowed
                )
                protocolInstance.client?.urlProtocol(protocolInstance, didLoad: data)
                protocolInstance.client?.urlProtocolDidFinishLoading(protocolInstance)
            } catch {
                protocolInstance.client?.urlProtocol(protocolInstance, didFailWithError: error)
            }
        }
    }

    override func stopLoading() {}
}

private final class UncheckedSendableBox<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}

private final class LockedRequestCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer {
            lock.unlock()
        }
        return count
    }

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}

private final class LockedValue<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storedValue: Value

    init(_ value: Value) {
        storedValue = value
    }

    var value: Value {
        lock.lock()
        defer { lock.unlock() }
        return storedValue
    }

    func set(_ value: Value) {
        lock.lock()
        storedValue = value
        lock.unlock()
    }
}

private actor BlockingRecordingAppSleepProvider: AppSleepProviding {
    private var values: [UInt64] = []

    func sleep(nanoseconds: UInt64) async throws {
        values.append(nanoseconds)
        try await Task.sleep(nanoseconds: 60_000_000_000)
    }

    func requestedNanoseconds() -> [UInt64] {
        values
    }
}

private actor RecordingAppSleepProvider: AppSleepProviding {
    private var values: [UInt64] = []

    func sleep(nanoseconds: UInt64) async throws {
        values.append(nanoseconds)
    }

    func requestedNanoseconds() -> [UInt64] {
        values
    }
}

private struct ImmediateAppSleepProvider: AppSleepProviding {
    func sleep(nanoseconds: UInt64) async throws {}
}
