#if os(iOS)
import Foundation

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
    var itemID: String?
    var contentIndex: Int
}

struct VoiceTutorPlaybackTruncation: Equatable, Sendable {
    var itemID: String
    var contentIndex: Int
    var audioEndMilliseconds: Int
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
    case assistantTranscriptDelta(String)
    case assistantTranscriptDone(String?)
    case userTranscript(String)
    case userSpeechStarted
    case userSpeechStopped
    case responseStarted(isTutorIntervention: Bool)
    case responseFinished
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
                    itemID: string("item_id", in: object),
                    contentIndex: max(0, integer("content_index", in: object) ?? 0)
                )
            )
        case "response.output_audio_transcript.delta", "response.audio_transcript.delta":
            return .assistantTranscriptDelta(string("delta", in: object) ?? "")
        case "response.output_audio_transcript.done", "response.audio_transcript.done":
            return .assistantTranscriptDone(string("transcript", in: object))
        case "conversation.item.input_audio_transcription.completed":
            return .userTranscript(string("transcript", in: object) ?? "")
        case "input_audio_buffer.speech_started":
            return .userSpeechStarted
        case "input_audio_buffer.speech_stopped":
            return .userSpeechStopped
        case "response.created":
            return .responseStarted(
                isTutorIntervention: boolean("buddystudyTutorIntervention", in: object) ?? false
            )
        case "response.done", "response.completed":
            return .responseFinished
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

enum VoiceTutorRealtimeBargeInPayload {
    static func text(
        cancelResponse: Bool,
        truncation: VoiceTutorPlaybackTruncation?
    ) throws -> String? {
        guard cancelResponse || truncation != nil else {
            return nil
        }
        var object: [String: Any] = [
            "type": "buddystudy.voice.barge-in",
            "cancelResponse": cancelResponse
        ]
        if let truncation {
            object["itemId"] = truncation.itemID
            object["contentIndex"] = truncation.contentIndex
            object["audioEndMs"] = truncation.audioEndMilliseconds
        }
        let payload = try JSONSerialization.data(withJSONObject: object)
        guard let text = String(data: payload, encoding: .utf8) else {
            throw VoiceTutorRealtimeEventParser.ParseError.invalidUTF8
        }
        return text
    }
}

actor VoiceTutorWebSocketTransport {
    enum TransportError: Error {
        case notConnected
    }

    private let session: URLSession
    private var socketTask: URLSessionWebSocketTask?

    init(configuration: URLSessionConfiguration = .ephemeral) {
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForRequest = 30
        self.session = URLSession(configuration: configuration)
    }

    func connect(request: URLRequest) throws {
        guard socketTask == nil else {
            return
        }
        let task = session.webSocketTask(with: request)
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

    func sendBargeIn(
        cancelResponse: Bool,
        truncation: VoiceTutorPlaybackTruncation?
    ) async throws {
        guard let text = try VoiceTutorRealtimeBargeInPayload.text(
            cancelResponse: cancelResponse,
            truncation: truncation
        ) else {
            return
        }
        guard let socketTask else {
            throw TransportError.notConnected
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
                VoiceTutorRealtimeAudioDelta(audio: data, itemID: nil, contentIndex: 0)
            )
        @unknown default:
            return .ignored(type: "unknown")
        }
    }

    func disconnect(closeCode: URLSessionWebSocketTask.CloseCode = .normalClosure) {
        socketTask?.cancel(with: closeCode, reason: nil)
        socketTask = nil
    }
}
#endif
