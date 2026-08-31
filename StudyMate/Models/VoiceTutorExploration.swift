import Foundation

/// Private voice-lesson material. These values never become question records,
/// question grades, study-tree writes, or topic statistics.
struct BackendVoiceTutorExploration: Decodable, Equatable, Sendable {
    var topic: String
    var studyId: Int?
    var difficulty: Int?
    var depthSummary: String
    var exchanges: [BackendVoiceTutorExplorationExchange]

    private enum CodingKeys: String, CodingKey {
        case topic, studyId, difficulty, depthSummary, exchanges
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        topic = try values.decodeIfPresent(String.self, forKey: .topic) ?? ""
        studyId = try values.decodeIfPresent(Int.self, forKey: .studyId).flatMap { $0 > 0 ? $0 : nil }
        difficulty = try values.decodeIfPresent(Int.self, forKey: .difficulty).flatMap {
            (1...10).contains($0) ? $0 : nil
        }
        depthSummary = try values.decodeIfPresent(String.self, forKey: .depthSummary) ?? ""
        exchanges = try values.decodeIfPresent([BackendVoiceTutorExplorationExchange].self, forKey: .exchanges) ?? []
    }
}

struct BackendVoiceTutorExplorationExchange: Decodable, Equatable, Sendable {
    enum Kind: String, Decodable, Equatable, Sendable {
        case tutorQuestion = "TUTOR_QUESTION"
        case learnerQuestion = "LEARNER_QUESTION"
        case unknown

        init(from decoder: Decoder) throws {
            let raw = try decoder.singleValueContainer().decode(String.self)
            self = Kind(rawValue: raw) ?? .unknown
        }
    }

    var kind: Kind
    var question: String
    var answer: String
    var score: Int?
    var strengths: [String]
    var improvements: [String]
    var questionTurnId: String?
    var answerTurnIds: [String]
    var feedbackTurnIds: [String]

    private enum CodingKeys: String, CodingKey {
        case kind, question, answer, score, strengths, improvements
        case questionTurnId, answerTurnIds, feedbackTurnIds
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        kind = try values.decodeIfPresent(Kind.self, forKey: .kind) ?? .unknown
        question = try values.decodeIfPresent(String.self, forKey: .question) ?? ""
        answer = try values.decodeIfPresent(String.self, forKey: .answer) ?? ""
        // An absent/invalid score is not a zero and must never be synthesized or
        // clamped into a seemingly valid assessment.
        score = (try? values.decodeIfPresent(Int.self, forKey: .score)).flatMap {
            (0...100).contains($0) ? $0 : nil
        }
        strengths = try values.decodeIfPresent([String].self, forKey: .strengths) ?? []
        improvements = try values.decodeIfPresent([String].self, forKey: .improvements) ?? []
        questionTurnId = try values.decodeIfPresent(VoiceTutorSourceTurnID.self, forKey: .questionTurnId)?.rawValue
        answerTurnIds = try values.decodeIfPresent([VoiceTutorSourceTurnID].self, forKey: .answerTurnIds)?
            .map(\.rawValue) ?? []
        feedbackTurnIds = try values.decodeIfPresent([VoiceTutorSourceTurnID].self, forKey: .feedbackTurnIds)?
            .map(\.rawValue) ?? []
    }
}

/// Session transcripts already support numeric or string IDs. Normalize only
/// their representation, never the actual transcript text.
private struct VoiceTutorSourceTurnID: Decodable {
    let rawValue: String

    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer()
        if let text = try? value.decode(String.self) {
            rawValue = text
        } else {
            rawValue = String(try value.decode(Int64.self))
        }
    }
}

#if os(iOS)
enum VoiceTutorExplorationPresentation {
    static let topicPageSize = 4
    static let exchangePageSize = 4
    static let transcriptPageSize = 20

    static func hasContent(_ exploration: BackendVoiceTutorExploration) -> Bool {
        !exploration.depthSummary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !displayExchanges(exploration.exchanges).isEmpty
    }

    static func displayExchanges(
        _ exchanges: [BackendVoiceTutorExplorationExchange]
    ) -> [BackendVoiceTutorExplorationExchange] {
        exchanges.filter {
            $0.kind != .unknown
                && (!$0.question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    || !$0.answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    static func displayScore(for exchange: BackendVoiceTutorExplorationExchange) -> Int? {
        guard exchange.kind == .tutorQuestion,
              !exchange.answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return exchange.score
    }

    /// Page arithmetic is bounded by the data already returned for this one
    /// session; pressing More never performs a broad record/tree refresh.
    static func nextVisibleCount(current: Int, total: Int, pageSize: Int) -> Int {
        let total = max(0, total)
        let current = min(max(0, current), total)
        let increment = min(max(0, pageSize), total - current)
        return current + increment
    }

    static func sourceTurns(
        for exchange: BackendVoiceTutorExplorationExchange,
        in transcript: [BackendVoiceTutorTranscriptTurn]
    ) -> [BackendVoiceTutorTranscriptTurn] {
        var references = Set(exchange.answerTurnIds + exchange.feedbackTurnIds)
        if let questionTurnId = exchange.questionTurnId { references.insert(questionTurnId) }
        references.remove("")
        var seen = Set<String>()
        return transcript.filter { references.contains($0.id) && seen.insert($0.id).inserted }
    }
}
#endif
