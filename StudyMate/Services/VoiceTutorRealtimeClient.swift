#if os(iOS)
import Foundation

enum VoiceTutorLocalSpeechDeliveryError: Error, Equatable {
    case bufferOverflow
    case invalidSequence
    case staleAttempt
}

enum VoiceTutorLocalSpeechProtocol {
    static let capabilityHeader = "X-Voice-Turn-Protocol"
    static let capabilityValue = "local-vad-v1"

    static func addingCapability(to request: URLRequest) -> URLRequest {
        var request = request
        request.setValue(capabilityValue, forHTTPHeaderField: capabilityHeader)
        return request
    }

    static func payload(for event: VoiceTutorLocalSpeechEvent) throws -> [String: Any] {
        guard event.sequence > 0 else { throw VoiceTutorLocalSpeechDeliveryError.invalidSequence }
        return ["type": event.messageType, "sequence": event.sequence]
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

enum VoiceTutorRealtimeEvent: Equatable, Sendable {
    case sessionReady(hardEndsAt: Date?, quotaRemainingSeconds: Int?)
    case quotaUpdated(VoiceTutorRealtimeQuotaUpdate)
    case sessionEnding(reason: String?, hardEndsAt: Date?)
    case sessionEnded(VoiceTutorRealtimeEnded)
    case resultReady
    case heartbeatAcknowledged
    case serviceError(code: String?, message: String, retryable: Bool)
    case audioDelta(VoiceTutorRealtimeAudioDelta)
    case assistantTranscriptDelta(responseID: String?, delta: String)
    case assistantTranscriptDone(responseID: String?, transcript: String?)
    case userTranscript(String)
    case userSpeechStarted
    case userSpeechStopped
    case responseStarted(responseID: String?, isTutorIntervention: Bool)
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
        case "buddystudy.voice.session.ready":
            return .sessionReady(
                hardEndsAt: date("hardEndsAt", in: object),
                quotaRemainingSeconds: integer("quotaRemainingSeconds", in: object)
            )
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
                transcript: string("transcript", in: object)
            )
        case "conversation.item.input_audio_transcription.completed":
            return .userTranscript(string("transcript", in: object) ?? "")
        case "input_audio_buffer.speech_started":
            return .userSpeechStarted
        case "input_audio_buffer.speech_stopped":
            return .userSpeechStopped
        case "response.created":
            let response = object["response"] as? [String: Any]
            return .responseStarted(
                responseID: response.flatMap { string("id", in: $0) },
                isTutorIntervention: boolean("buddystudyTutorIntervention", in: object) ?? false
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

    private static func boolean(_ key: String, in object: [String: Any]) -> Bool? {
        if let value = object[key] as? Bool {
            return value
        }
        if let value = object[key] as? NSNumber {
            return value.boolValue
        }
        return nil
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

    func sendPlayoutDrained(responseID: String) async throws {
        try await sendJSON([
            "type": "buddystudy.voice.playout.drained",
            "responseId": responseID
        ])
    }

    func sendInputSpeechActivity(_ event: VoiceTutorLocalSpeechEvent, attemptID: UUID) async throws {
        guard localSpeechAttemptID == attemptID else { throw VoiceTutorLocalSpeechDeliveryError.staleAttempt }
        guard let socketTask else { throw TransportError.notConnected }
        // Capture this attempt's socket before the first suspension. A delayed
        // old pump can never send its utterance sequence to a retried call.
        let data = try JSONSerialization.data(withJSONObject: VoiceTutorLocalSpeechProtocol.payload(for: event))
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
