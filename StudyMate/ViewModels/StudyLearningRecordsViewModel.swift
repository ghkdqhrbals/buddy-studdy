#if os(iOS)
import Combine
import Foundation

@MainActor
final class StudyLearningRecordsViewModel: ObservableObject {
    enum Direction: Equatable { case first, refresh, previous, next, retry }

    @Published private(set) var page: BackendStudyLearningRecordsPage?
    @Published private(set) var isLoading = false
    @Published private(set) var failed = false
    @Published private(set) var pageNumber = 1
    @Published private(set) var context: StudyLearningRecordsContext?
    private var cursors: [String?] = [nil]
    private var loader: StudyLearningRecordsLoader?
    private var isActive = false
    private var requestID = UUID()
    private var lastDirection: Direction = .refresh
    private let actionRunner = AppActionRunner()

    var canGoPrevious: Bool { pageNumber > 1 && !isLoading }
    var canGoNext: Bool { page?.hasMore == true && !isLoading }
    var detailLoader: StudyLearningRecordsLoader? { loader }

    func activate(_ loader: StudyLearningRecordsLoader) async {
        if context != loader.context {
            deactivate()
            context = loader.context
            page = nil
            pageNumber = 1
            cursors = [nil]
            failed = false
        }
        self.loader = loader
        isActive = true
        if page == nil { page = loader.cachedPage(cursors[pageNumber - 1]) }
        await load(.refresh)
    }

    func deactivate() {
        requestID = UUID()
        isActive = false
        isLoading = false
    }

    func load(_ requestedDirection: Direction) async {
        guard isActive, !isLoading, let loader, loader.isCurrent(), !Task.isCancelled else { return }
        let direction = requestedDirection == .retry ? lastDirection : requestedDirection
        let targetIndex: Int
        let targetCursor: String?
        switch direction {
        case .first:
            targetIndex = 0
            targetCursor = nil
        case .refresh, .retry:
            targetIndex = pageNumber - 1
            targetCursor = cursors[targetIndex]
        case .previous:
            guard canGoPrevious else { return }
            targetIndex = pageNumber - 2
            targetCursor = cursors[targetIndex]
        case .next:
            guard canGoNext, let next = page?.nextCursor else { return }
            targetIndex = pageNumber
            targetCursor = next
        }
        let token = UUID()
        requestID = token
        lastDirection = direction
        isLoading = true
        failed = false
        await actionRunner.run(
            operation: {
                let result: BackendStudyLearningRecordsPage
                if direction == .previous, let cached = loader.cachedPage(targetCursor) {
                    result = cached
                } else {
                    result = try await loader.loadPage(targetCursor)
                }
                guard !Task.isCancelled, loader.isCurrent() else { throw CancellationError() }
                let visited = cursors.prefix(targetIndex)
                guard !result.hasMore || (result.nextCursor != targetCursor && !visited.contains(result.nextCursor)) else {
                    throw StudyLearningRecordsError.invalidResponse
                }
                return result
            },
            onSuccess: { result in
                guard requestID == token, context == loader.context, loader.isCurrent(), !Task.isCancelled else { return }
                cursors = Array(cursors.prefix(targetIndex)) + [targetCursor]
                pageNumber = targetIndex + 1
                page = result
            },
            onFailure: { error in
                guard requestID == token, context == loader.context, loader.isCurrent(), !Task.isCancelled,
                      !isCancelledLearningRead(error) else { return }
                failed = true
            },
            onCompletion: {
                guard requestID == token else { return }
                isLoading = false
            }
        )
    }
}

@MainActor
final class StudyLearningRecordDetailViewModel: ObservableObject {
    @Published private(set) var record: BackendStudyLearningRecord
    @Published private(set) var isShowingOriginal = false
    @Published private(set) var isLoading = false
    @Published private(set) var failed = false
    private var requestID = UUID()
    private let actionRunner = AppActionRunner()
    private let sleep: @MainActor () async throws -> Void

    init(
        record: BackendStudyLearningRecord,
        sleep: @escaping @MainActor () async throws -> Void = { try await Task.sleep(for: .seconds(2)) }
    ) {
        self.record = record
        self.sleep = sleep
    }

    func deactivate() {
        requestID = UUID()
        isLoading = false
    }

    /// Translation is server-owned. Only the visible record gets at most three
    /// delayed read-repair refreshes; exhaustion stays visibly pending/retryable.
    func load(using loader: StudyLearningRecordsLoader, view: LocalizedContentView) async {
        guard loader.isCurrent(), !Task.isCancelled else { return }
        let token = UUID()
        requestID = token
        isLoading = true
        failed = false
        defer { if requestID == token { isLoading = false } }
        for attempt in 0...3 {
            guard requestID == token, loader.isCurrent(), !Task.isCancelled else { return }
            if attempt > 0 {
                do { try await sleep() } catch { return }
                guard requestID == token, loader.isCurrent(), !Task.isCancelled else { return }
            }
            let seed = record
            let result = await actionRunner.run(
                operation: {
                    var next = seed
                    switch seed.source {
                    case .question:
                        guard let existing = seed.questionRecord else { throw StudyLearningRecordsError.invalidResponse }
                        let fetched = try await loader.loadQuestion(existing.id, view)
                        guard fetched.id == existing.id,
                              fetched.studyID == nil || fetched.studyID == seed.studyID else {
                            throw StudyLearningRecordsError.invalidResponse
                        }
                        next.questionRecord = fetched
                    case .voiceTutor:
                        guard let existing = seed.voiceRecord else { throw StudyLearningRecordsError.invalidResponse }
                        let fetched = try await loader.loadVoice(existing.id, view)
                        guard fetched.id == existing.id, fetched.studyID == seed.studyID,
                              fetched.sessionID == existing.sessionID else {
                            throw StudyLearningRecordsError.invalidResponse
                        }
                        next.voiceRecord = fetched
                    }
                    return next
                },
                onSuccess: { next in
                    guard requestID == token, loader.isCurrent(), !Task.isCancelled else { return }
                    record = next
                    isShowingOriginal = view == .original
                },
                onFailure: { error in
                    guard requestID == token, loader.isCurrent(), !Task.isCancelled,
                          !isCancelledLearningRead(error) else { return }
                    failed = true
                }
            )
            guard requestID == token, loader.isCurrent(), !Task.isCancelled,
                  result != nil, view == .localized, record.translationPending else { return }
        }
    }
}

private func isCancelledLearningRead(_ error: Error) -> Bool {
    if error is CancellationError { return true }
    let error = error as NSError
    return error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled
}
#endif
