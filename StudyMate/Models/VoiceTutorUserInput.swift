#if os(iOS)
import Foundation

struct VoiceTutorUserInputQuestion: Codable, Equatable, Sendable, Identifiable {
    enum SelectionMode: String, Codable, Sendable { case single, multiple, text }
    struct Option: Codable, Equatable, Sendable, Identifiable {
        let id: String
        let label: String
    }
    let id: String
    let prompt: String
    let selectionMode: SelectionMode
    let options: [Option]
    let allowFreeText: Bool

    var isValid: Bool {
        VoiceTutorUserInputRequest.isIdentifier(id) && !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && prompt.utf16.count <= 500 && options.count <= 8
            && Set(options.map(\.id)).count == options.count
            && options.allSatisfy { VoiceTutorUserInputRequest.isIdentifier($0.id) && !$0.label.isEmpty && $0.label.utf16.count <= 200 }
            && (selectionMode == .text ? options.isEmpty && allowFreeText : !options.isEmpty)
    }
}

struct VoiceTutorUserInputRequest: Codable, Equatable, Sendable, Identifiable {
    let requestId: String
    let sessionId: String
    let attemptId: String
    let sequence: Int64
    let title: String
    let questions: [VoiceTutorUserInputQuestion]
    var id: String { requestId }

    var isValid: Bool {
        [requestId, sessionId, attemptId].allSatisfy { UUID(uuidString: $0) != nil }
            && sequence > 0 && !title.isEmpty && title.utf16.count <= 200
            && (1...5).contains(questions.count) && questions.allSatisfy(\.isValid)
            && Set(questions.map(\.id)).count == questions.count
    }

    static func isIdentifier(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 80 && value.utf8.allSatisfy {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 95 || $0 == 45
        }
    }
}

struct VoiceTutorUserInputStateEvent: Codable, Equatable, Sendable {
    enum Phase: String, Codable, Sendable { case pending, submitted, cancelled }
    let requestId: String
    let sessionId: String
    let attemptId: String
    let sequence: Int64
    let phase: Phase
    let errorCode: String?
}

struct VoiceTutorUserInputAnswer: Codable, Equatable, Sendable {
    let questionId: String
    var selectedOptionIds: [String] = []
    var text = ""
}

struct VoiceTutorUserInputControl: Equatable, Sendable {
    let request: VoiceTutorUserInputRequest
    /// nil cancels the request; an answer array explicitly submits it.
    let answers: [VoiceTutorUserInputAnswer]?

    func payload() throws -> [String: Any] {
        guard request.isValid, answers.map({ VoiceTutorUserInputState.validAnswers($0, for: request) }) ?? true else {
            throw VoiceTutorLocalSpeechDeliveryError.invalidSequence
        }
        var value: [String: Any] = [
            "type": answers == nil ? "buddystudy.voice.user_input.cancel" : "buddystudy.voice.user_input.submit",
            "requestId": request.requestId, "sessionId": request.sessionId, "attemptId": request.attemptId
        ]
        if let answers { value["answers"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(answers)) }
        return value
    }
}

/// One authenticated conversation attempt owns these drafts and acknowledgments.
/// Editing or sending an answer does not release the microphone hold.
struct VoiceTutorUserInputState: Equatable, Sendable {
    enum Status: Equatable, Sendable { case pending, submitting, submitted, cancelled }
    struct Entry: Equatable, Sendable, Identifiable {
        let request: VoiceTutorUserInputRequest
        var answers: [VoiceTutorUserInputAnswer]
        var status: Status = .pending
        var hasError = false
        var actionFailed = false
        var id: String { request.id }
        var isWaiting: Bool { status == .pending || status == .submitting }
        var canSubmit: Bool { status == .pending && VoiceTutorUserInputState.validAnswers(answers, for: request) }
    }
    private(set) var entries: [Entry] = []
    private(set) var latestSequence: Int64 = 0
    private var isClosed = false
    var pending: Entry? { entries.last(where: \.isWaiting) }
    var holdsMicrophone: Bool { pending != nil }

    @discardableResult
    mutating func apply(_ request: VoiceTutorUserInputRequest, sessionID: String) -> Bool {
        guard !isClosed, request.isValid, request.sessionId == sessionID,
              request.sequence > latestSequence, !entries.contains(where: { $0.id == request.id }), pending == nil else { return false }
        latestSequence = request.sequence
        entries.append(Entry(request: request, answers: request.questions.map { .init(questionId: $0.id) }))
        if entries.count > 64 { entries.removeFirst(entries.count - 64) }
        return true
    }

    @discardableResult
    mutating func apply(_ event: VoiceTutorUserInputStateEvent) -> Bool {
        guard !isClosed, event.sequence > latestSequence,
              let index = entries.firstIndex(where: { $0.id == event.requestId && $0.isWaiting }),
              entries[index].request.sessionId == event.sessionId,
              entries[index].request.attemptId == event.attemptId,
              event.errorCode == nil || (event.phase == .pending && ["INVALID_ANSWERS", "ACTION_FAILED"].contains(event.errorCode)) else { return false }
        latestSequence = event.sequence
        switch event.phase {
        case .pending: entries[index].status = .pending
        case .submitted: entries[index].status = .submitted
        case .cancelled: entries[index].status = .cancelled
        }
        entries[index].hasError = event.errorCode != nil
        entries[index].actionFailed = event.errorCode == "ACTION_FAILED"
        return true
    }

    mutating func update(requestID: String, answer: VoiceTutorUserInputAnswer) {
        guard !isClosed, let index = entries.firstIndex(where: { $0.id == requestID && $0.status == .pending }),
              let answerIndex = entries[index].answers.firstIndex(where: { $0.questionId == answer.questionId }),
              answer.text.utf16.count <= 2_000 else { return }
        entries[index].answers[answerIndex] = answer
        entries[index].hasError = false
        entries[index].actionFailed = false
    }

    mutating func submit(requestID: String, cancel: Bool) -> VoiceTutorUserInputControl? {
        guard !isClosed, let index = entries.firstIndex(where: { $0.id == requestID && $0.status == .pending }),
              cancel || entries[index].canSubmit else { return nil }
        let control = VoiceTutorUserInputControl(request: entries[index].request, answers: cancel ? nil : entries[index].answers)
        entries[index].status = .submitting
        return control
    }

    mutating func endLocally() {
        isClosed = true
        for index in entries.indices where entries[index].isWaiting { entries[index].status = .cancelled }
    }

    static func validAnswers(_ answers: [VoiceTutorUserInputAnswer], for request: VoiceTutorUserInputRequest) -> Bool {
        guard answers.count == request.questions.count, Set(answers.map(\.questionId)).count == answers.count else { return false }
        return request.questions.allSatisfy { question in
            guard let answer = answers.first(where: { $0.questionId == question.id }), answer.text.utf16.count <= 2_000,
                  Set(answer.selectedOptionIds).count == answer.selectedOptionIds.count,
                  answer.selectedOptionIds.allSatisfy({ selected in question.options.contains(where: { $0.id == selected }) }),
                  question.selectionMode == .multiple || answer.selectedOptionIds.count <= 1 else { return false }
            let text = answer.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return (question.allowFreeText || text.isEmpty)
                && (!answer.selectedOptionIds.isEmpty || !text.isEmpty)
        }
    }
}
#endif
