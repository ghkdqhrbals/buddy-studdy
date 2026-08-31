#if os(iOS)
import Foundation

enum StudyLearningRecordScope: String, CaseIterable, Identifiable, Hashable, Sendable {
    case node
    case subtree

    var id: String { rawValue }
}

/// The node page keeps legacy source IDs for pagination, while new servers
/// supply the same canonical typed record used by Records and public comments.
struct BackendStudyLearningRecord: Decodable, Equatable, Identifiable {
    enum Source: String, Decodable {
        case question = "QUESTION"
        case voiceTutor = "VOICE_TUTOR"
    }

    var id: String
    var source: Source
    var studyID: Int
    var createdAt: Date
    var questionRecord: StudyRecord?
    var voiceRecord: BackendVoiceStudyLearningRecord?
    var record: StudyRecord?

    private enum CodingKeys: String, CodingKey {
        case id, source, createdAt, questionRecord, voiceRecord, record
        case studyID = "studyId"
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        source = try values.decode(Source.self, forKey: .source)
        studyID = try values.decode(Int.self, forKey: .studyID)
        createdAt = try values.decode(Date.self, forKey: .createdAt)
        questionRecord = try values.decodeIfPresent(StudyRecord.self, forKey: .questionRecord)
        voiceRecord = try values.decodeIfPresent(BackendVoiceStudyLearningRecord.self, forKey: .voiceRecord)
        record = try values.decodeIfPresent(StudyRecord.self, forKey: .record)
        guard studyID > 0 else { throw StudyLearningRecordsError.invalidResponse }
        switch source {
        case .question:
            guard let questionRecord, voiceRecord == nil,
                  id == "question:\(questionRecord.id)",
                  questionRecord.studyID == nil || questionRecord.studyID == studyID else {
                throw StudyLearningRecordsError.invalidResponse
            }
        case .voiceTutor:
            guard let voiceRecord, questionRecord == nil,
                  id == "voice:\(voiceRecord.id)", voiceRecord.studyID == studyID else {
                throw StudyLearningRecordsError.invalidResponse
            }
        }
        if let record {
            guard record.studyID == studyID,
                  record.recordType == (source == .question ? .question : .voiceTutor),
                  record.id == (source == .question ? questionRecord?.id : voiceRecord?.recordID) else {
                throw StudyLearningRecordsError.invalidResponse
            }
        }
    }

    var commonRecord: StudyRecord? { record ?? questionRecord }
    var canonicalRecordID: String? { commonRecord?.id ?? voiceRecord?.recordID }
    var topic: String { commonRecord?.topic ?? voiceRecord?.topic ?? "" }
    var question: String { commonRecord?.question.question ?? voiceRecord?.question ?? "" }
    var answer: String? {
        if let commonRecord { return commonRecord.answer }
        return voiceRecord?.answer
    }
    var difficulty: Int? { commonRecord?.difficulty.level ?? voiceRecord?.difficulty }
    var score: Int? {
        if let commonRecord { return commonRecord.displayScore }
        return voiceRecord?.displayScore
    }
    var translationPending: Bool {
        if let commonRecord { return commonRecord.translationPending }
        return voiceRecord?.translationPending == true
    }
}

struct BackendVoiceStudyLearningRecord: Decodable, Equatable, Identifiable {
    enum Kind: String, Decodable {
        case tutorQuestion = "TUTOR_QUESTION"
        case learnerQuestion = "LEARNER_QUESTION"
    }

    var id: String
    var recordID: String?
    var sessionID: String
    var studyID: Int
    var parentStudyID: Int?
    var topic: String
    var difficulty: Int
    var createdAt: Date
    var kind: Kind
    var question: String
    var answer: String?
    var score: Int?
    var strengths: [String]
    var improvements: [String]
    var depthSummary: String
    var feedback: String?
    var questionTurnID: String
    var answerTurnIDs: [String]
    var feedbackTurnIDs: [String]
    var sourceLanguage: String
    var requestedLanguage: String
    var displayLanguage: String
    var translationPending: Bool

    private enum CodingKeys: String, CodingKey {
        case id, topic, difficulty, createdAt, kind, question, answer, score
        case recordID = "recordId"
        case strengths, improvements, depthSummary, feedback
        case sourceLanguage, requestedLanguage, displayLanguage, translationPending
        case sessionID = "sessionId"
        case studyID = "studyId"
        case parentStudyID = "parentStudyId"
        case questionTurnID = "questionTurnId"
        case answerTurnIDs = "answerTurnIds"
        case feedbackTurnIDs = "feedbackTurnIds"
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(StudyLearningSourceID.self, forKey: .id).value
        recordID = try values.decodeIfPresent(StudyLearningSourceID.self, forKey: .recordID)?.value
        sessionID = try values.decode(String.self, forKey: .sessionID)
        studyID = try values.decode(Int.self, forKey: .studyID)
        parentStudyID = try values.decodeIfPresent(Int.self, forKey: .parentStudyID)
        topic = try values.decode(String.self, forKey: .topic)
        difficulty = try values.decode(Int.self, forKey: .difficulty)
        createdAt = try values.decode(Date.self, forKey: .createdAt)
        kind = try values.decode(Kind.self, forKey: .kind)
        question = try values.decode(String.self, forKey: .question)
        answer = try values.decodeIfPresent(String.self, forKey: .answer)
        score = (try? values.decodeIfPresent(Int.self, forKey: .score)).flatMap {
            (0...100).contains($0) ? $0 : nil
        }
        strengths = try values.decode([String].self, forKey: .strengths)
        improvements = try values.decode([String].self, forKey: .improvements)
        depthSummary = try values.decode(String.self, forKey: .depthSummary)
        feedback = try values.decodeIfPresent(String.self, forKey: .feedback)
        questionTurnID = try values.decode(StudyLearningSourceID.self, forKey: .questionTurnID).value
        answerTurnIDs = try values.decode([StudyLearningSourceID].self, forKey: .answerTurnIDs).map(\.value)
        feedbackTurnIDs = try values.decode([StudyLearningSourceID].self, forKey: .feedbackTurnIDs).map(\.value)
        sourceLanguage = try values.decode(String.self, forKey: .sourceLanguage)
        requestedLanguage = try values.decode(String.self, forKey: .requestedLanguage)
        displayLanguage = try values.decode(String.self, forKey: .displayLanguage)
        translationPending = try values.decode(Bool.self, forKey: .translationPending)
        guard studyID > 0, (1...10).contains(difficulty), !sessionID.isEmpty,
              parentStudyID == nil || parentStudyID! > 0 else {
            throw StudyLearningRecordsError.invalidResponse
        }
    }

    /// No response, learner-led questions, and missing grades are never zeroes.
    var displayScore: Int? {
        guard kind == .tutorQuestion,
              answer?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else { return nil }
        return score
    }
}

private struct StudyLearningSourceID: Decodable {
    let value: String

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let number: Int64
        if let text = try? container.decode(String.self), let parsed = Int64(text) {
            number = parsed
        } else {
            number = try container.decode(Int64.self)
        }
        guard number > 0 else { throw StudyLearningRecordsError.invalidResponse }
        value = String(number)
    }
}

struct BackendStudyLearningRecordsPage: Decodable, Equatable {
    var items: [BackendStudyLearningRecord]
    var nextCursor: String?
    var hasMore: Bool
    var limit: Int

    init(items: [BackendStudyLearningRecord], nextCursor: String? = nil, hasMore: Bool = false, limit: Int = 30) {
        self.items = items
        self.nextCursor = nextCursor
        self.hasMore = hasMore
        self.limit = limit
    }

    private enum CodingKeys: String, CodingKey { case items, nextCursor, hasMore, limit }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        items = try values.decode([BackendStudyLearningRecord].self, forKey: .items)
        nextCursor = try values.decodeIfPresent(String.self, forKey: .nextCursor)
        hasMore = try values.decode(Bool.self, forKey: .hasMore)
        limit = try values.decode(Int.self, forKey: .limit)
        guard (1...100).contains(limit), items.count <= limit,
              Set(items.map(\.id)).count == items.count,
              !hasMore || nextCursor?.isEmpty == false,
              nextCursor == nil || nextCursor!.utf8.count <= 4_096 else {
            throw StudyLearningRecordsError.invalidResponse
        }
    }
}

enum StudyLearningRecordsError: Error {
    case invalidResponse
    case unavailable
}

/// No token, API key, or persisted identity is stored in a page key.
struct StudyLearningRecordsIdentity: Hashable, Sendable {
    var lifetimeID: UUID
    var userID: Int
    var sessionGeneration: UInt64
    var backendGeneration: Int
    var languageCode: String
}

struct StudyLearningRecordsContext: Hashable, Sendable {
    var identity: StudyLearningRecordsIdentity
    var studyID: Int
    var scope: StudyLearningRecordScope
}

struct StudyLearningRecordsCacheKey: Hashable, Sendable {
    var context: StudyLearningRecordsContext
    var cursor: String?
    var view: String
}

/// Lives inside SettingsStore's existing in-memory record store, never defaults,
/// SQLite, CloudKit, or the record-ID-keyed answer-draft dictionary.
struct StudyLearningRecordsPageCache {
    static let capacity = 8
    private var entries: [(key: StudyLearningRecordsCacheKey, page: BackendStudyLearningRecordsPage)] = []

    var count: Int { entries.count }

    mutating func page(for key: StudyLearningRecordsCacheKey) -> BackendStudyLearningRecordsPage? {
        entries.removeAll { $0.key.context.identity != key.context.identity }
        guard let index = entries.firstIndex(where: { $0.key == key }) else { return nil }
        let entry = entries.remove(at: index)
        entries.append(entry)
        return entry.page
    }

    mutating func save(_ page: BackendStudyLearningRecordsPage, for key: StudyLearningRecordsCacheKey) {
        // A new account, language, environment, or AppState lifetime evicts the
        // previous identity's material rather than merely making it unreachable.
        entries.removeAll { $0.key.context.identity != key.context.identity || $0.key == key }
        entries.append((key, page))
        if entries.count > Self.capacity { entries.removeFirst(entries.count - Self.capacity) }
    }

    mutating func clear() { entries.removeAll() }
}

@MainActor
struct StudyLearningRecordsLoader {
    var context: StudyLearningRecordsContext
    var isCurrent: @MainActor () -> Bool
    var cachedPage: @MainActor (String?) -> BackendStudyLearningRecordsPage?
    var loadPage: @MainActor (String?) async throws -> BackendStudyLearningRecordsPage
    var loadVoice: @MainActor (String, LocalizedContentView) async throws -> BackendVoiceStudyLearningRecord
    var loadQuestion: @MainActor (String, LocalizedContentView) async throws -> StudyRecord
}
#endif
