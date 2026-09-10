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
    /// Display-only link to the operation that produced this card.
    var operationId: String? = nil
    var id: String { requestId }

    var isValid: Bool {
        [requestId, sessionId, attemptId].allSatisfy { UUID(uuidString: $0) != nil }
            && sequence > 0 && !title.isEmpty && title.utf16.count <= 200
            && (1...5).contains(questions.count) && questions.allSatisfy(\.isValid)
            && Set(questions.map(\.id)).count == questions.count
            && (operationId.map { VoiceTutorOperationEvent.isSafeIdentifier($0, maximumLength: 191) } ?? true)
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
        let fallbackAnchor: VoiceTutorOperationState.Entry
        private(set) var originOperation: VoiceTutorOperationState.Entry? = nil
        private(set) var submittedAnswers: [VoiceTutorUserInputAnswer]? = nil
        fileprivate var sendingAnswers: [VoiceTutorUserInputAnswer]? = nil
        var id: String { request.id }
        var isWaiting: Bool { status == .pending || status == .submitting }
        var canSubmit: Bool { status == .pending && VoiceTutorUserInputState.validAnswers(answers, for: request) }

        fileprivate mutating func bindOrigin(_ operation: VoiceTutorOperationState.Entry?) {
            // Any authenticated operation may request a server-owned form,
            // including curriculum gates inside selection/question tools.
            // Its exact ID, not the displayed function name, fixes the origin.
            guard originOperation == nil, let operation, operation.id == request.operationId else { return }
            originOperation = operation
        }

        fileprivate mutating func acknowledgeSubmission() {
            submittedAnswers = sendingAnswers
        }
    }
    private(set) var entries: [Entry] = []
    private(set) var latestSequence: Int64 = 0
    private var isClosed = false
    var pending: Entry? { entries.last(where: \.isWaiting) }
    var holdsMicrophone: Bool { pending != nil }

    @discardableResult
    mutating func apply(_ request: VoiceTutorUserInputRequest, sessionID: String,
                        operation: VoiceTutorOperationState.Entry? = nil,
                        afterCaptionID: UUID? = nil, responseID: String? = nil) -> Bool {
        guard !isClosed, request.isValid, request.sessionId == sessionID,
              request.sequence > latestSequence, !entries.contains(where: { $0.id == request.id }), pending == nil else { return false }
        latestSequence = request.sequence
        let fallback = VoiceTutorOperationState.Entry(
            event: .init(sequence: request.sequence, operationID: request.id, name: "request_user_input",
                         phase: .started, elapsedMilliseconds: 0),
            receivedAt: 0, startedSequence: request.sequence, context: nil,
            afterCaptionID: afterCaptionID, fallbackResponseID: responseID)
        var entry = Entry(request: request, answers: request.questions.map { .init(questionId: $0.id) }, fallbackAnchor: fallback)
        entry.bindOrigin(operation)
        entries.append(entry)
        if entries.count > 64 { entries.removeFirst(entries.count - 64) }
        return true
    }

    /// The first exact operation wins, including when its event arrives after
    /// the card. Completion and later transcripts never replace its origin.
    mutating func bindOperation(_ operation: VoiceTutorOperationState.Entry) {
        guard let index = entries.firstIndex(where: { $0.request.operationId == operation.id }) else { return }
        entries[index].bindOrigin(operation)
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
        case .pending:
            entries[index].status = .pending
            entries[index].sendingAnswers = nil
        case .submitted:
            entries[index].status = .submitted
            entries[index].acknowledgeSubmission()
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
        entries[index].sendingAnswers = control.answers
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

/// Cards use the same causal source resolution as MCP rows. A card answering
/// an earlier card inherits that card's location, so successive forms remain
/// with their originating conversation rather than collecting at the bottom.
struct VoiceTutorUserInputTranscriptLayout {
    private enum Placement {
        case beforeCaptions, caption(UUID), assistantDraft, answerDraft
    }
    private(set) var beforeCaptions: [VoiceTutorUserInputState.Entry] = []
    private(set) var byCaptionID: [UUID: [VoiceTutorUserInputState.Entry]] = [:]
    private(set) var afterAssistantDraft: [VoiceTutorUserInputState.Entry] = []
    private(set) var afterAnswerDraft: [VoiceTutorUserInputState.Entry] = []
    private(set) var unresolvedWaiting: [VoiceTutorUserInputState.Entry] = []

    init(entries: [VoiceTutorUserInputState.Entry], captions: [VoiceTutorCaption],
         assistantResponseID: String?, hasAssistantDraft: Bool, answerDraftID: String? = nil) {
        var placements: [String: Placement] = [:]
        var earlierInputIDs = Set<String>()
        for entry in entries {
            defer { earlierInputIDs.insert(entry.id) }
            // An explicit operation ID must never fall back to receipt timing.
            guard let origin = entry.request.operationId == nil ? entry.fallbackAnchor : entry.originOperation else {
                if entry.isWaiting { unresolvedWaiting.append(entry) }
                continue
            }
            let source = VoiceTutorOperationTranscriptLayout(entries: [origin], captions: captions,
                assistantResponseID: assistantResponseID, hasAssistantDraft: hasAssistantDraft,
                userInputIDs: earlierInputIDs, answerDraftID: answerDraftID)
            let placement: Placement?
            if !source.beforeCaptions.isEmpty { placement = .beforeCaptions }
            else if let id = source.byCaptionID.keys.first { placement = .caption(id) }
            else if !source.afterAssistantDraft.isEmpty { placement = .assistantDraft }
            else if !source.afterAnswerDraft.isEmpty { placement = .answerDraft }
            else if let id = source.byUserInputID.keys.first { placement = placements[id] }
            else { placement = nil }
            guard let placement else {
                if entry.isWaiting { unresolvedWaiting.append(entry) }
                continue
            }
            placements[entry.id] = placement
            switch placement {
            case .beforeCaptions: beforeCaptions.append(entry)
            case .caption(let id): byCaptionID[id, default: []].append(entry)
            case .assistantDraft: afterAssistantDraft.append(entry)
            case .answerDraft: afterAnswerDraft.append(entry)
            }
        }
    }
}
#endif
