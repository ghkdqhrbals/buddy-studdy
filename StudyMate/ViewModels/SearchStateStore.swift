import Foundation

@MainActor
struct SearchStateStore {
    private(set) var homeStudyResults: [StudyCategory]?
    private(set) var recordResults: [StudyRecord]?
    var communityQuery = ""

    mutating func replaceHomeStudyResults(_ results: [StudyCategory]?) {
        homeStudyResults = results
    }

    mutating func clearHomeStudyResults() {
        homeStudyResults = nil
    }

    mutating func replaceRecordResults(_ results: [StudyRecord]?) {
        recordResults = results
    }

    mutating func clearRecordResults() {
        recordResults = nil
    }

    var trimmedCommunityQuery: String {
        communityQuery.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
