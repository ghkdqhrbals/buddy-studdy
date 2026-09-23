import Foundation

@MainActor
struct SearchStateStore {
    private(set) var homeStudyResults: [StudyCategory]?
    private(set) var recordResults: [StudyRecord]?
    private(set) var recordTotalCount = 0
    private(set) var recordLoadedCount = 0
    private(set) var isLoadingRecordPage = false
    private(set) var failedRecordPageReset: Bool?
    private(set) var recordQuery = ""
    private var recordPageRequestID: UUID?
    var communityQuery = ""

    mutating func replaceHomeStudyResults(_ results: [StudyCategory]?) {
        homeStudyResults = results
    }

    mutating func clearHomeStudyResults() {
        homeStudyResults = nil
    }

    mutating func replaceRecordResults(_ results: [StudyRecord]?) {
        recordResults = results
        if results == nil {
            recordTotalCount = 0
            recordLoadedCount = 0
            recordQuery = ""
            isLoadingRecordPage = false
            recordPageRequestID = nil
            failedRecordPageReset = nil
        }
    }

    var canLoadMoreRecordResults: Bool {
        recordLoadedCount < recordTotalCount
    }

    mutating func beginRecordPage(query: String, reset: Bool) -> UUID? {
        if recordQuery != query || recordResults == nil {
            recordResults = []
            recordTotalCount = 0
            recordLoadedCount = 0
            recordQuery = query
            isLoadingRecordPage = false
            recordPageRequestID = nil
        } else if reset {
            // Refresh the same query without removing the already visible page.
            isLoadingRecordPage = false
            recordPageRequestID = nil
        }
        guard !isLoadingRecordPage else {
            return nil
        }
        let requestID = UUID()
        recordPageRequestID = requestID
        isLoadingRecordPage = true
        failedRecordPageReset = nil
        return requestID
    }

    mutating func applyRecordPage(
        _ page: BackendRecordsPage,
        query: String,
        reset: Bool,
        requestID: UUID
    ) {
        guard recordQuery == query, recordPageRequestID == requestID else {
            return
        }
        var merged = reset ? [] : (recordResults ?? [])
        page.records.forEach { record in
            merged.removeAll { $0.id == record.id }
            merged.append(record)
        }
        recordResults = merged
        failedRecordPageReset = nil
        recordTotalCount = max(page.totalCount, merged.count)
        recordLoadedCount = reset
            ? page.records.count
            : max(recordLoadedCount, page.offset + page.records.count)
    }

    mutating func finishRecordPage(query: String, requestID: UUID) {
        guard recordQuery == query, recordPageRequestID == requestID else {
            return
        }
        isLoadingRecordPage = false
        recordPageRequestID = nil
    }

    mutating func failRecordPage(query: String, reset: Bool, requestID: UUID) {
        guard recordQuery == query, recordPageRequestID == requestID else { return }
        failedRecordPageReset = reset
    }

    mutating func removeRecordResult(id: String) {
        guard recordResults?.contains(where: { $0.id == id }) == true else {
            return
        }
        recordResults?.removeAll { $0.id == id }
        recordTotalCount = max(recordTotalCount - 1, 0)
        recordLoadedCount = max(recordLoadedCount - 1, 0)
    }

    mutating func clearRecordResults() {
        replaceRecordResults(nil)
    }

    var trimmedCommunityQuery: String {
        communityQuery.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
