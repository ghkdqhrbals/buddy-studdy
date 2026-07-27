import Foundation
import XCTest
@testable import StudyMate

@MainActor
final class QuestionGenerationFlowTests: XCTestCase {
    override func tearDown() {
        QuestionGenerationURLProtocol.requestHandler = nil
        super.tearDown()
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
                URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                    .queryItems?
                    .first(where: { $0.name == "after" })?
                    .value,
                "4"
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

    private func makeClient(
        handler: @escaping (URLRequest) throws -> (HTTPURLResponse, Data)
    ) -> RemotePushBackendClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [QuestionGenerationURLProtocol.self]
        QuestionGenerationURLProtocol.requestHandler = handler
        return RemotePushBackendClient(
            baseURL: URL(string: "https://example.test")!,
            session: URLSession(configuration: configuration)
        )
    }

    private static let registration = RemotePushRegistration(
        deviceID: "device-1",
        clientSecret: "client-secret",
        apnsToken: ""
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
}

private final class QuestionGenerationURLProtocol: URLProtocol {
    nonisolated(unsafe) static var requestHandler:
        ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let requestHandler = Self.requestHandler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }

        do {
            let (response, data) = try requestHandler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
