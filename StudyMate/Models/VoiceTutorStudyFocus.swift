import Foundation

/// Server-confirmed call context, not a study room or a question-selection command.
struct VoiceTutorStudyFocus: Equatable, Sendable {
    let studyID: Int
    let parentStudyID: Int?
    let topic: String
    let difficulty: Int
    let revision: Int64

    init?(studyID: Int, parentStudyID: Int?, topic: String, difficulty: Int, revision: Int64) {
        let trimmedTopic = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        guard studyID > 0,
              parentStudyID.map({ $0 > 0 && $0 != studyID }) ?? true,
              !trimmedTopic.isEmpty, topic.utf16.count <= 255,
              (1...10).contains(difficulty), revision >= 1 else { return nil }
        self.studyID = studyID
        self.parentStudyID = parentStudyID
        self.topic = trimmedTopic
        self.difficulty = difficulty
        self.revision = revision
    }
}

/// Scoped to one call attempt. A clear retains the revision watermark so a
/// delayed duplicate cannot resurrect an old focus. Deleted IDs are metadata
/// only and are discarded with the attempt; no study/draft store is changed.
struct VoiceTutorStudyFocusState: Equatable, Sendable {
    private(set) var focus: VoiceTutorStudyFocus?
    private(set) var revision: Int64 = 0
    private var attemptID: UUID?
    private var deletedStudyIDs = Set<Int>()

    mutating func beginAttempt(_ attemptID: UUID) {
        focus = nil
        revision = 0
        deletedStudyIDs = []
        self.attemptID = attemptID
    }

    @discardableResult
    mutating func apply(_ next: VoiceTutorStudyFocus?, attemptID: UUID) -> Bool {
        guard self.attemptID == attemptID else { return false }
        guard let next else {
            let changed = focus != nil
            focus = nil
            return changed
        }
        guard next.revision > revision, !deletedStudyIDs.contains(next.studyID),
              next.parentStudyID.map({ !deletedStudyIDs.contains($0) }) ?? true else { return false }
        focus = next
        revision = next.revision
        return true
    }

    mutating func remove(studyIDs: Set<Int>, attemptID: UUID) {
        guard self.attemptID == attemptID else { return }
        deletedStudyIDs.formUnion(studyIDs.filter { $0 > 0 })
        if let focus, studyIDs.contains(focus.studyID)
            || focus.parentStudyID.map(studyIDs.contains) == true {
            self.focus = nil
        }
    }
}

enum VoiceTutorCallStartPolicy {
    /// An empty or not-yet-loaded local tree cannot prevent a spoken discovery call.
    static func canStart(status: BackendVoiceTutorStatus?) -> Bool {
        status?.eligible == true
            && (status?.quota.remainingSeconds ?? 0) > 0
            && status?.activeSession == nil
    }
}
