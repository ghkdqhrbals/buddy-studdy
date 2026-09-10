#if os(iOS)
import Foundation

enum VoiceTutorLocalSpeechDeliveryError: Error, Equatable {
    case bufferOverflow
    case invalidSequence
    case staleAttempt
}

enum VoiceTutorTurnProtocol {
    static let capabilityHeader = "X-Voice-Turn-Protocol"
    static let capabilityValue = "realtime-native-v1"
    static let userInputCapabilityHeader = "X-Voice-User-Input-Protocol"
    static let userInputCapabilityValue = "user-input-v1"

    static func addingCapability(to request: URLRequest) -> URLRequest {
        var request = request
        request.setValue(capabilityValue, forHTTPHeaderField: capabilityHeader)
        request.setValue(userInputCapabilityValue, forHTTPHeaderField: userInputCapabilityHeader)
        return request
    }

    static func acceptsReady(_ protocolValue: String?) -> Bool {
        protocolValue == capabilityValue
    }

    /// Silero sends only numbered acoustic boundaries. The backend commits
    /// audio; the Realtime model interprets meaning and tool intent in context.
    static func payload(for event: VoiceTutorCallControlEvent) throws -> [String: Any] {
        switch event {
        case .userInput(let command):
            return try command.payload()
        case .speech(let speech):
            guard speech.sequence > 0 else { throw VoiceTutorLocalSpeechDeliveryError.invalidSequence }
            return ["type": speech.messageType, "sequence": speech.sequence]
        case .pause(let command):
            guard command.sequence > 0 else { throw VoiceTutorLocalSpeechDeliveryError.invalidSequence }
            return ["type": command.kind.rawValue, "sequence": command.sequence]
        case .answer(let command):
            guard UUID(uuidString: command.answerID) != nil,
                  VoiceTutorQuestionChange(studyID: 1, recordID: command.recordID) != nil else {
                throw VoiceTutorLocalSpeechDeliveryError.invalidSequence
            }
            var payload: [String: Any] = ["type": command.kind.rawValue, "answerId": command.answerID, "recordId": command.recordID]
            if command.kind == .submit {
                guard let text = command.text, VoiceTutorAnswerDraftState.isValidSubmission(text) else {
                    throw VoiceTutorLocalSpeechDeliveryError.invalidSequence
                }
                payload["text"] = text
            } else if command.text != nil {
                throw VoiceTutorLocalSpeechDeliveryError.invalidSequence
            }
            return payload
        }
    }
}

/// The capture callback only queues two tiny events per utterance. A slow or
/// failed socket must not silently discard a start/stop edge and strand the
/// server's input buffer; overflow is terminal and uses the existing teardown.
struct VoiceTutorLocalSpeechEventStream: Sendable {
    let stream: AsyncThrowingStream<VoiceTutorLocalSpeechEvent, Error>
    private let continuation: AsyncThrowingStream<VoiceTutorLocalSpeechEvent, Error>.Continuation

    init(capacity: Int = 32) {
        let pair = AsyncThrowingStream<VoiceTutorLocalSpeechEvent, Error>.makeStream(
            bufferingPolicy: .bufferingOldest(max(1, min(capacity, 128)))
        )
        stream = pair.stream
        continuation = pair.continuation
    }

    func yield(_ event: VoiceTutorLocalSpeechEvent) {
        switch continuation.yield(event) {
        case .enqueued, .terminated:
            break
        case .dropped:
            continuation.finish(throwing: VoiceTutorLocalSpeechDeliveryError.bufferOverflow)
        @unknown default:
            continuation.finish(throwing: VoiceTutorLocalSpeechDeliveryError.bufferOverflow)
        }
    }

    func finish() { continuation.finish() }
}

struct VoiceTutorRealtimeQuotaUpdate: Equatable, Sendable {
    var limitSeconds: Int
    var usedSeconds: Int
    var reservedSeconds: Int
    var remainingSeconds: Int
    var chargedSeconds: Int
}

struct VoiceTutorRealtimeEnded: Equatable, Sendable {
    var reason: String?
    var endedAt: Date?
    var durationSeconds: Int
    var chargedSeconds: Int
    var resultStatus: String?
    var pollAfterMilliseconds: Int?
}

struct VoiceTutorRealtimeAudioDelta: Equatable, Sendable {
    var audio: Data
    var responseID: String?
    var itemID: String?
    var contentIndex: Int
}

struct VoiceTutorQuestionChange: Equatable, Hashable, Sendable {
    let studyID: Int
    let recordID: String

    init?(studyID: Int, recordID: String) {
        guard studyID > 0, let number = Int64(recordID), number > 0,
              String(number) == recordID else { return nil }
        self.studyID = studyID
        self.recordID = recordID
    }
}

enum VoiceTutorRealtimeEvent: Equatable, Sendable {
    case sessionReady(
        hardEndsAt: Date?, quotaRemainingSeconds: Int?, pauseProtocol: String? = nil,
        turnProtocol: String? = nil
    )
    case pauseAcknowledged(sequence: Int64, paused: Bool)
    case quotaUpdated(VoiceTutorRealtimeQuotaUpdate)
    case sessionEnding(reason: String?, hardEndsAt: Date?)
    case sessionEnded(VoiceTutorRealtimeEnded)
    case resultReady
    case heartbeatAcknowledged
    case serviceError(code: String?, message: String, retryable: Bool)
    case audioDelta(VoiceTutorRealtimeAudioDelta)
    case assistantTranscriptDelta(responseID: String?, delta: String)
    case assistantTranscriptDone(responseID: String?, transcript: String?, itemID: String? = nil)
    case userTranscript(String, itemID: String? = nil)
    case userSpeechStarted
    case userSpeechStopped
    case inputRetry
    case inputRetryScoped(sequence: Int, abandonedResponseID: String?)
    case inputSettled(sequence: Int)
    case providerTurnAbandoned(responseID: String)
    case responseInterrupted(responseID: String)
    case studyFocused(VoiceTutorStudyFocus?)
    case studyTreeChanged(studyID: Int)
    case studyTreeUpdated(studyID: Int)
    case studyTreeDeleted(studyIDs: Set<Int>)
    case questionChanged(VoiceTutorQuestionChange)
    case answerState(VoiceTutorAnswerStateEvent)
    case answerTranscript(VoiceTutorAnswerTranscriptEvent)
    case sessionState(VoiceTutorSessionStateEvent)
    case operation(VoiceTutorOperationEvent)
    case operationContext(VoiceTutorOperationContextEvent)
    case userInputRequest(VoiceTutorUserInputRequest)
    case userInputState(VoiceTutorUserInputStateEvent)
    case responseRecovering(responseID: String, sequence: Int)
    case responseStarted(
        responseID: String?,
        isTutorIntervention: Bool,
        isQuotaExhaustionNotice: Bool
    )
    case responseFinished(responseID: String?)
    case outputAudioBufferStarted(responseID: String?)
    case outputAudioBufferStopped(responseID: String?)
    case outputAudioBufferCleared(responseID: String?)
    case ignored(type: String)
}

enum VoiceTutorRealtimeEventParser {
    enum ParseError: Error {
        case invalidUTF8
        case invalidJSON
        case missingType
        case invalidAudio
    }

    static func parse(data: Data) throws -> VoiceTutorRealtimeEvent {
        guard let text = String(data: data, encoding: .utf8) else {
            throw ParseError.invalidUTF8
        }
        return try parse(text: text)
    }

    static func parse(text: String) throws -> VoiceTutorRealtimeEvent {
        guard let data = text.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ParseError.invalidJSON
        }
        guard let type = string("type", in: object), !type.isEmpty else {
            throw ParseError.missingType
        }

        switch type {
        case "buddystudy.voice.user_input.request":
            guard let request = try? JSONDecoder().decode(VoiceTutorUserInputRequest.self, from: data), request.isValid else {
                return .ignored(type: type)
            }
            return .userInputRequest(request)
        case "buddystudy.voice.user_input.state":
            guard let event = try? JSONDecoder().decode(VoiceTutorUserInputStateEvent.self, from: data),
                  event.sequence > 0, [event.requestId, event.sessionId, event.attemptId].allSatisfy({ UUID(uuidString: $0) != nil }) else {
                return .ignored(type: type)
            }
            return .userInputState(event)
        case "buddystudy.voice.operation.context":
            let required: Set<String> = ["type", "operationId"]
            let optional: Set<String> = ["responseId", "learnerItemId", "tutorItemId", "answerId"]
            guard required.isSubset(of: Set(object.keys)),
                  Set(object.keys).isSubset(of: required.union(optional)),
                  optional.allSatisfy({ object[$0] == nil || object[$0] is String }),
                  let operationID = string("operationId", in: object) else { return .ignored(type: type) }
            let event = VoiceTutorOperationContextEvent(operationID: operationID,
                responseID: string("responseId", in: object), learnerItemID: string("learnerItemId", in: object),
                tutorItemID: string("tutorItemId", in: object), answerID: string("answerId", in: object))
            return event.isValid ? .operationContext(event) : .ignored(type: type)
        case "buddystudy.voice.operation":
            guard Set(object.keys) == ["type", "sequence", "operationId", "name", "phase", "elapsedMs"],
                  let sequence = exactInteger("sequence", in: object),
                  let operationID = string("operationId", in: object),
                  let name = string("name", in: object),
                  let rawPhase = string("phase", in: object),
                  let phase = VoiceTutorOperationEvent.Phase(rawValue: rawPhase),
                  let elapsed = exactInteger("elapsedMs", in: object) else { return .ignored(type: type) }
            let event = VoiceTutorOperationEvent(sequence: sequence, operationID: operationID,
                name: name, phase: phase, elapsedMilliseconds: elapsed)
            return event.isValid ? .operation(event) : .ignored(type: type)
        case "buddystudy.voice.session.state":
            let required: Set<String> = ["type", "sequence", "phase", "paused", "revision"]
            guard required.isSubset(of: Set(object.keys)),
                  Set(object.keys).isSubset(of: required.union(["studyId", "recordId", "answerId"])),
                  let sequence = exactInteger("sequence", in: object),
                  let rawPhase = string("phase", in: object),
                  let phase = VoiceTutorSessionStateEvent.Phase(rawValue: rawPhase),
                  let paused = exactBoolean("paused", in: object),
                  let revision = exactInteger("revision", in: object),
                  object["studyId"] == nil || exactInteger("studyId", in: object).flatMap({ Int(exactly: $0) }) != nil,
                  object["recordId"] == nil || object["recordId"] is String,
                  object["answerId"] == nil || object["answerId"] is String else { return .ignored(type: type) }
            let event = VoiceTutorSessionStateEvent(sequence: sequence, phase: phase, paused: paused, revision: revision,
                studyID: exactInteger("studyId", in: object).flatMap({ Int(exactly: $0) }),
                recordID: string("recordId", in: object), answerID: string("answerId", in: object))
            return event.isValid ? .sessionState(event) : .ignored(type: type)
        case "buddystudy.voice.answer.state":
            let required: Set<String> = ["type", "answerId", "studyId", "recordId", "revision", "phase"]
            guard required.isSubset(of: Set(object.keys)), Set(object.keys).isSubset(of: required.union(["text", "code"])),
                  let answerID = answerIdentifier(in: object),
                  let studyID = exactInteger("studyId", in: object).flatMap({ Int(exactly: $0) }),
                  let recordID = string("recordId", in: object), VoiceTutorQuestionChange(studyID: studyID, recordID: recordID) != nil,
                  let revision = exactInteger("revision", in: object), revision >= 0,
                  let rawPhase = string("phase", in: object), let phase = VoiceTutorAnswerDraftState.Phase(rawValue: rawPhase), phase != .inactive,
                  object["text"] == nil || (object["text"] as? String).map({ $0.utf16.count <= VoiceTutorAnswerDraftState.maximumTextLength }) == true,
                  object["code"] == nil || (object["code"] as? String).map({
                      ["ANSWER_TRANSCRIPT_INCOMPLETE", "ANSWER_SUBMISSION_FAILED", "ANSWER_TOO_LONG",
                       "ANSWER_CANCELLED", "ANSWER_CANCEL_UNAVAILABLE"].contains($0)
                  }) == true else { return .ignored(type: type) }
            return .answerState(VoiceTutorAnswerStateEvent(answerID: answerID, studyID: studyID, recordID: recordID,
                revision: revision, phase: phase, text: string("text", in: object), code: string("code", in: object)))
        case "buddystudy.voice.answer.transcript":
            guard Set(object.keys) == ["type", "answerId", "recordId", "itemId", "sequence", "text"],
                  let answerID = answerIdentifier(in: object), let recordID = string("recordId", in: object),
                  VoiceTutorQuestionChange(studyID: 1, recordID: recordID) != nil,
                  let itemID = providerResponseID("itemId", in: object),
                  let sequence = exactInteger("sequence", in: object), sequence > 0,
                  let text = string("text", in: object), text.utf16.count <= VoiceTutorAnswerDraftState.maximumTextLength else { return .ignored(type: type) }
            return .answerTranscript(VoiceTutorAnswerTranscriptEvent(answerID: answerID, recordID: recordID,
                itemID: itemID, sequence: sequence, text: text))
        case "buddystudy.voice.question.changed":
            guard Set(object.keys) == ["type", "studyId", "recordId"],
                  let studyID = exactInteger("studyId", in: object).flatMap({ Int(exactly: $0) }),
                  let recordID = object["recordId"] as? String,
                  let change = VoiceTutorQuestionChange(studyID: studyID, recordID: recordID) else {
                return .ignored(type: type)
            }
            return .questionChanged(change)
        case "buddystudy.voice.session.ready":
            return .sessionReady(
                hardEndsAt: date("hardEndsAt", in: object),
                quotaRemainingSeconds: integer("quotaRemainingSeconds", in: object),
                pauseProtocol: string("pauseProtocol", in: object),
                turnProtocol: string("turnProtocol", in: object)
            )
        case "buddystudy.voice.pause.state":
            guard let number = object["sequence"] as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID(),
                  let sequence = Int64(number.stringValue), sequence > 0,
                  let paused = object["paused"] as? NSNumber,
                  CFGetTypeID(paused) == CFBooleanGetTypeID() else {
                return .ignored(type: type)
            }
            return .pauseAcknowledged(sequence: sequence, paused: paused.boolValue)
        case "buddystudy.voice.quota.updated":
            return .quotaUpdated(
                VoiceTutorRealtimeQuotaUpdate(
                    limitSeconds: max(0, integer("limitSeconds", in: object) ?? 0),
                    usedSeconds: max(0, integer("usedSeconds", in: object) ?? 0),
                    reservedSeconds: max(0, integer("reservedSeconds", in: object) ?? 0),
                    remainingSeconds: max(0, integer("remainingSeconds", in: object) ?? 0),
                    chargedSeconds: max(0, integer("chargedSeconds", in: object) ?? 0)
                )
            )
        case "buddystudy.voice.session.ending":
            return .sessionEnding(
                reason: string("reason", in: object),
                hardEndsAt: date("hardEndsAt", in: object)
            )
        case "buddystudy.voice.session.ended":
            return .sessionEnded(
                VoiceTutorRealtimeEnded(
                    reason: string("reason", in: object),
                    endedAt: date("endedAt", in: object),
                    durationSeconds: max(0, integer("durationSeconds", in: object) ?? 0),
                    chargedSeconds: max(0, integer("chargedSeconds", in: object) ?? 0),
                    resultStatus: string("resultStatus", in: object),
                    pollAfterMilliseconds: integer("pollAfterMs", in: object)
                )
            )
        case "buddystudy.voice.result.ready":
            return .resultReady
        case "buddystudy.voice.heartbeat.ack":
            return .heartbeatAcknowledged
        case "buddystudy.voice.input.retry":
            if object["sequence"] != nil {
                guard let sequence = exactInteger("sequence", in: object).flatMap({ Int(exactly: $0) }),
                      sequence >= 0 else { return .ignored(type: type) }
                let responseID = providerResponseID("abandonedResponseId", in: object)
                guard object["abandonedResponseId"] == nil || responseID != nil else { return .ignored(type: type) }
                return .inputRetryScoped(sequence: sequence, abandonedResponseID: responseID)
            }
            if let responseID = providerResponseID("abandonedResponseId", in: object) {
                return .providerTurnAbandoned(responseID: responseID)
            }
            return .inputRetry
        case "buddystudy.voice.input.settled":
            guard let sequence = exactInteger("sequence", in: object).flatMap({ Int(exactly: $0) }),
                  sequence >= 0 else { return .ignored(type: type) }
            return .inputSettled(sequence: sequence)
        case "buddystudy.voice.response.interrupted":
            guard let responseID = providerResponseID("responseId", in: object) else {
                return .ignored(type: type)
            }
            return .responseInterrupted(responseID: responseID)
        case "buddystudy.voice.response.recovering":
            guard Set(object.keys) == ["type", "responseId", "sequence"],
                  let responseID = providerResponseID("responseId", in: object),
                  let sequence = exactInteger("sequence", in: object).flatMap({ Int(exactly: $0) }),
                  sequence >= 0 else { return .ignored(type: type) }
            return .responseRecovering(responseID: responseID, sequence: sequence)
        case "buddystudy.voice.study.focused":
            // Only an explicit null clears the current topic. Missing or malformed
            // metadata cannot impersonate discovery, a saved node, or a new epoch.
            guard let value = object["focus"] else { return .ignored(type: type) }
            if value is NSNull { return .studyFocused(nil) }
            guard let fields = value as? [String: Any],
                  let studyID = exactInteger("studyId", in: fields).flatMap({ Int(exactly: $0) }),
                  let parent = fields["parentStudyId"],
                  let topic = string("topic", in: fields),
                  let difficulty = exactInteger("difficulty", in: fields).flatMap({ Int(exactly: $0) }),
                  let revision = exactInteger("revision", in: fields) else { return .ignored(type: type) }
            let parentStudyID: Int?
            if parent is NSNull {
                parentStudyID = nil
            } else {
                guard let id = exactInteger("parentStudyId", in: fields).flatMap({ Int(exactly: $0) }) else {
                    return .ignored(type: type)
                }
                parentStudyID = id
            }
            guard let focus = VoiceTutorStudyFocus(
                studyID: studyID, parentStudyID: parentStudyID, topic: topic,
                difficulty: difficulty, revision: revision
            ) else { return .ignored(type: type) }
            return .studyFocused(focus)
        case "buddystudy.voice.study.changed":
            guard let number = object["studyId"] as? NSNumber,
                  CFGetTypeID(number) != CFBooleanGetTypeID(),
                  let studyID = Int(number.stringValue), studyID > 0 else {
                return .ignored(type: type)
            }
            if object["change"] == nil { return .studyTreeChanged(studyID: studyID) }
            switch object["change"] as? String {
            case "created": return .studyTreeChanged(studyID: studyID)
            case "updated": return .studyTreeUpdated(studyID: studyID)
            case "deleted":
                guard let values = object["deletedStudyIds"] as? [Any],
                      !values.isEmpty, values.count <= 128 else { return .ignored(type: type) }
                var ids = Set<Int>()
                for value in values {
                    guard let number = value as? NSNumber,
                          CFGetTypeID(number) != CFBooleanGetTypeID(),
                          let id = Int(number.stringValue), id > 0,
                          ids.insert(id).inserted else { return .ignored(type: type) }
                }
                guard ids.contains(studyID) else { return .ignored(type: type) }
                return .studyTreeDeleted(studyIDs: ids)
            default: return .ignored(type: type)
            }
        case "buddystudy.voice.error":
            return .serviceError(
                code: string("code", in: object),
                message: string("message", in: object) ?? "",
                retryable: boolean("retryable", in: object) ?? false
            )
        case "response.output_audio.delta", "response.audio.delta":
            guard let encoded = string("delta", in: object) ?? string("audio", in: object),
                  let audio = Data(base64Encoded: encoded) else {
                throw ParseError.invalidAudio
            }
            return .audioDelta(
                VoiceTutorRealtimeAudioDelta(
                    audio: audio,
                    responseID: string("response_id", in: object),
                    itemID: string("item_id", in: object),
                    contentIndex: max(0, integer("content_index", in: object) ?? 0)
                )
            )
        case "response.output_audio.done", "response.audio.done":
            return .ignored(type: type)
        case "response.output_audio_transcript.delta", "response.audio_transcript.delta":
            return .assistantTranscriptDelta(
                responseID: string("response_id", in: object),
                delta: string("delta", in: object) ?? ""
            )
        case "response.output_audio_transcript.done", "response.audio_transcript.done":
            return .assistantTranscriptDone(
                responseID: string("response_id", in: object),
                transcript: string("transcript", in: object),
                itemID: providerResponseID("item_id", in: object)
            )
        case "conversation.item.input_audio_transcription.completed":
            // Older PCM frames may omit an item ID; the native call requires
            // one before presentation. A malformed supplied ID is never an
            // anonymous utterance, since that would bypass item deduplication.
            let itemID = providerResponseID("item_id", in: object)
            guard let transcript = string("transcript", in: object),
                  object["item_id"] == nil || itemID != nil else { return .ignored(type: type) }
            return .userTranscript(transcript, itemID: itemID)
        case "input_audio_buffer.speech_started":
            return .userSpeechStarted
        case "input_audio_buffer.speech_stopped":
            return .userSpeechStopped
        case "response.created":
            let response = object["response"] as? [String: Any]
            return .responseStarted(
                responseID: response.flatMap { string("id", in: $0) },
                isTutorIntervention: boolean("buddystudyTutorIntervention", in: object) ?? false,
                isQuotaExhaustionNotice: exactBoolean(
                    "buddystudyQuotaExhaustionNotice",
                    in: object
                ) ?? false
            )
        case "response.done", "response.completed":
            let response = object["response"] as? [String: Any]
            return .responseFinished(responseID: response.flatMap { string("id", in: $0) })
        case "output_audio_buffer.started":
            return .outputAudioBufferStarted(responseID: string("response_id", in: object))
        case "output_audio_buffer.stopped":
            return .outputAudioBufferStopped(responseID: string("response_id", in: object))
        case "output_audio_buffer.cleared":
            return .outputAudioBufferCleared(responseID: string("response_id", in: object))
        case "error":
            let nestedError = object["error"] as? [String: Any]
            return .serviceError(
                code: nestedError.flatMap { string("code", in: $0) },
                message: nestedError.flatMap { string("message", in: $0) }
                    ?? string("message", in: object)
                    ?? "",
                retryable: false
            )
        default:
            return .ignored(type: type)
        }
    }

    private static func string(_ key: String, in object: [String: Any]) -> String? {
        object[key] as? String
    }

    private static func answerIdentifier(in object: [String: Any]) -> String? {
        guard let value = string("answerId", in: object), value.count == 36, UUID(uuidString: value) != nil else { return nil }
        return value
    }

    private static func providerResponseID(_ key: String, in object: [String: Any]) -> String? {
        guard let value = string(key, in: object), !value.isEmpty, value.utf8.count <= 191 else {
            return nil
        }
        let allowedPunctuation = CharacterSet(charactersIn: "_-")
        let allowed = CharacterSet.alphanumerics.union(allowedPunctuation)
        guard value.unicodeScalars.allSatisfy({ $0.isASCII && allowed.contains($0) }) else {
            return nil
        }
        return value
    }

    private static func integer(_ key: String, in object: [String: Any]) -> Int? {
        if let value = object[key] as? Int {
            return value
        }
        if let value = object[key] as? NSNumber {
            return value.intValue
        }
        if let value = object[key] as? String {
            return Int(value)
        }
        return nil
    }

    private static func exactInteger(_ key: String, in object: [String: Any]) -> Int64? {
        guard let number = object[key] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        return Int64(number.stringValue)
    }

    private static func boolean(_ key: String, in object: [String: Any]) -> Bool? {
        if let value = object[key] as? Bool {
            return value
        }
        if let value = object[key] as? NSNumber {
            return value.boolValue
        }
        return nil
    }

    private static func exactBoolean(_ key: String, in object: [String: Any]) -> Bool? {
        guard let value = object[key] as? NSNumber,
              CFGetTypeID(value) == CFBooleanGetTypeID() else { return nil }
        return value.boolValue
    }

    private static func date(_ key: String, in object: [String: Any]) -> Date? {
        guard let value = string(key, in: object) else {
            return nil
        }
        let fractionalFormatter = ISO8601DateFormatter()
        fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractionalFormatter.date(from: value) {
            return date
        }
        return ISO8601DateFormatter().date(from: value)
    }
}

actor VoiceTutorWebSocketTransport {
    enum TransportError: Error {
        case notConnected
    }

    private let session: URLSession
    private var socketTask: URLSessionWebSocketTask?
    private var localSpeechAttemptID: UUID?

    init(configuration: URLSessionConfiguration = .ephemeral) {
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 30
        self.session = URLSession(configuration: configuration)
    }

    func connect(request: URLRequest, localSpeechAttemptID: UUID? = nil) throws {
        guard socketTask == nil else {
            return
        }
        let task = session.webSocketTask(with: request)
        self.localSpeechAttemptID = localSpeechAttemptID
        socketTask = task
        task.resume()
    }

    func sendAudio(_ audio: Data) async throws {
        guard let socketTask else {
            throw TransportError.notConnected
        }
        let payload = try JSONSerialization.data(
            withJSONObject: [
                "type": "input_audio_buffer.append",
                "audio": audio.base64EncodedString()
            ]
        )
        guard let text = String(data: payload, encoding: .utf8) else {
            throw VoiceTutorRealtimeEventParser.ParseError.invalidUTF8
        }
        try await socketTask.send(.string(text))
    }

    func sendSessionEnd() async throws {
        guard let socketTask else {
            throw TransportError.notConnected
        }
        try await socketTask.send(.string(#"{"type":"buddystudy.voice.session.end"}"#))
    }

    func sendHeartbeat() async throws {
        guard let socketTask else {
            throw TransportError.notConnected
        }
        try await socketTask.send(.string(#"{"type":"buddystudy.voice.heartbeat"}"#))
    }

    func sendPlaybackCompleted(responseID: String) async throws {
        guard let socketTask else {
            throw TransportError.notConnected
        }
        let payload = try JSONSerialization.data(
            withJSONObject: [
                "type": "buddystudy.voice.playback.completed",
                "responseId": responseID
            ]
        )
        guard let text = String(data: payload, encoding: .utf8) else {
            throw VoiceTutorRealtimeEventParser.ParseError.invalidUTF8
        }
        try await socketTask.send(.string(text))
    }

    /// Confirms that the exact WebRTC response named by the server has crossed
    /// this device's bounded native playout tail. This is separate from the
    /// legacy PCM playback callback used by the non-WebRTC transport.
    func sendPlayoutDrained(responseID: String) async throws {
        guard let socketTask else {
            throw TransportError.notConnected
        }
        let payload = try JSONSerialization.data(
            withJSONObject: [
                "type": "buddystudy.voice.playout.drained",
                "responseId": responseID
            ]
        )
        guard let text = String(data: payload, encoding: .utf8) else {
            throw VoiceTutorRealtimeEventParser.ParseError.invalidUTF8
        }
        try await socketTask.send(.string(text))
    }

    func sendCallControl(_ event: VoiceTutorCallControlEvent, attemptID: UUID) async throws {
        guard localSpeechAttemptID == attemptID else { throw VoiceTutorLocalSpeechDeliveryError.staleAttempt }
        guard let socketTask else { throw TransportError.notConnected }
        // Capture this attempt's socket before the first suspension. A delayed
        // old pump can never send its utterance sequence to a retried call.
        let payload = try VoiceTutorTurnProtocol.payload(for: event)
        let data = try JSONSerialization.data(withJSONObject: payload)
        guard let text = String(data: data, encoding: .utf8) else {
            throw VoiceTutorRealtimeEventParser.ParseError.invalidUTF8
        }
        try await socketTask.send(.string(text))
    }

    func sendJSON(_ object: [String: Any]) async throws {
        guard let socketTask else {
            throw TransportError.notConnected
        }
        let data = try JSONSerialization.data(withJSONObject: object)
        guard let text = String(data: data, encoding: .utf8) else {
            throw VoiceTutorRealtimeEventParser.ParseError.invalidUTF8
        }
        try await socketTask.send(.string(text))
    }

    func receive() async throws -> VoiceTutorRealtimeEvent {
        guard let socketTask else {
            throw TransportError.notConnected
        }
        let message = try await socketTask.receive()
        switch message {
        case .string(let text):
            return try VoiceTutorRealtimeEventParser.parse(text: text)
        case .data(let data):
            if let first = data.first, first == 0x7B {
                return try VoiceTutorRealtimeEventParser.parse(data: data)
            }
            return .audioDelta(
                VoiceTutorRealtimeAudioDelta(
                    audio: data,
                    responseID: nil,
                    itemID: nil,
                    contentIndex: 0
                )
            )
        @unknown default:
            return .ignored(type: "unknown")
        }
    }

    // Read before local teardown. A missing close frame is unknown, not an
    // invented remote close code; closeReason is deliberately never exposed.
    func diagnosticCloseCode() -> Int? {
        guard let socketTask, socketTask.closeCode != .invalid else { return nil }
        return socketTask.closeCode.rawValue
    }

    func disconnect(closeCode: URLSessionWebSocketTask.CloseCode = .normalClosure) {
        socketTask?.cancel(with: closeCode, reason: nil)
        socketTask = nil
        localSpeechAttemptID = nil
    }
}
#endif
