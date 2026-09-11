#if os(iOS)
import AVFoundation
import Foundation
import UIKit

enum VoiceTutorSessionPhase: Equatable {
    case idle
    case requestingPermission
    case connecting
    case listening
    case speaking
    case ending
    case ended
    case failed

    var isLive: Bool {
        switch self {
        case .requestingPermission, .connecting, .listening, .speaking:
            return true
        case .idle, .ending, .ended, .failed:
            return false
        }
    }

    static func completed(outcome: Self, serverState: String?, serverReason: String? = nil) -> Self {
        let reason = serverReason?.uppercased() ?? ""
        let failedReason = reason.hasSuffix("_ERROR") || reason.hasSuffix("_TIMEOUT")
            || ["AUTH_REVOKED", "CONTROL_BACKPRESSURE", "CLIENT_DISCONNECTED"].contains(reason)
        return outcome == .failed || serverState?.uppercased() == "FAILED" || failedReason ? .failed : .ended
    }

    func afterRenderedTutorAudio(assistantResponseActive: Bool) -> Self {
        // Do not interrupt startup's connecting guard or revive terminal UI.
        self == .listening && assistantResponseActive ? .speaking : self
    }

    func maySealServerResponse(isFinalizing: Bool) -> Bool {
        !isFinalizing && (isLive || self == .ending)
    }

    func shouldCloseFinalizingMediaForDismissal(isFinalizing: Bool) -> Bool {
        isFinalizing && self == .ending
    }
}

enum VoiceTutorFailureCause: Equatable {
    case connection, offline, timeout
    case provider, providerUnavailable, providerQuotaUnavailable, rateLimited
    case signInRequired, accountUnavailable, termsRequired, sessionConflict, proRequired, monthlyQuota
    case finalization
    case updateRequired, requestRejected, invalidResponse
    case microphone, audio, inputPreparation, localControl, service, unknown
}

struct VoiceTutorStartupFailurePresentation: Equatable {
    let cause: VoiceTutorFailureCause
    let message: String
}

/// REST session creation, SDP negotiation and control-stream failures share the
/// same public vocabulary. Only typed codes/statuses are trusted, never prose.
enum VoiceTutorStartupFailurePolicy {
    static func presentation(for error: Error, strings: AppStrings) -> VoiceTutorStartupFailurePresentation {
        if let backend = error as? RemotePushBackendError {
            switch backend {
            case .httpStatus(let status, _, _):
                return servicePresentation(code: backend.backendCode, statusCode: status, strings: strings)
            case .invalidResponse: return failure(.invalidResponse, strings)
            }
        }
        if let webRTC = error as? VoiceTutorWebRTCError {
            switch webRTC {
            case .sdpExchangeFailed(let status, let backend):
                return servicePresentation(code: backend?.code, statusCode: status, strings: strings)
            case .mediaConnectionTimedOut: return failure(.timeout, strings)
            case .mediaConnectionFailed: return failure(.connection, strings)
            case .invalidSDPResponse: return failure(.invalidResponse, strings)
            case .speechActivityUnavailable, .audioProcessingDelegateInstallationFailed,
                 .echoCancellationUnavailable, .localTrackCreationFailed:
                return failure(.inputPreparation, strings)
            case .peerConnectionCreationFailed, .offerCreationFailed:
                return failure(.connection, strings)
            }
        }
        let network = error as NSError
        if network.domain == NSURLErrorDomain {
            switch network.code {
            case NSURLErrorNotConnectedToInternet, NSURLErrorDataNotAllowed,
                 NSURLErrorInternationalRoamingOff, NSURLErrorCallIsActive:
                return failure(.offline, strings)
            case NSURLErrorTimedOut: return failure(.timeout, strings)
            default: return failure(.connection, strings)
            }
        }
        if error is VoiceTutorSileroError { return failure(.inputPreparation, strings) }
        if let preparation = error as? VoiceTutorPreparationError {
            switch preparation {
            case .signInRequired: return failure(.signInRequired, strings)
            case .missingRegistration: return failure(.accountUnavailable, strings)
            case .invalidWebSocketURL: return failure(.requestRejected, strings)
            }
        }
        if let audio = error as? VoiceTutorAudioEngine.AudioError {
            switch audio {
            case .microphonePermissionDenied: return failure(.microphone, strings)
            case .unsupportedInputFormat, .invalidOutputAudio: return failure(.inputPreparation, strings)
            }
        }
        return failure(.unknown, strings)
    }

    static func servicePresentation(code: String?, statusCode: Int? = nil,
                                    retryable: Bool? = nil, strings: AppStrings) -> VoiceTutorStartupFailurePresentation {
        let cause: VoiceTutorFailureCause
        switch code?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() {
        case "VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED": cause = .providerQuotaUnavailable
        case "VOICE_TUTOR_PROVIDER_UNAVAILABLE": cause = .providerUnavailable
        case "VOICE_TUTOR_PRO_REQUIRED": cause = .proRequired
        case "VOICE_TUTOR_QUOTA_EXCEEDED": cause = .monthlyQuota
        case "VOICE_TUTOR_SESSION_CONFLICT": cause = .sessionConflict
        case "AUTH_ACCESS_TOKEN_REQUIRED", "AUTH_DEVICE_CREDENTIALS_REQUIRED", "AUTH_DEVICE_MISMATCH",
             "AUTH_GOOGLE_REQUIRED", "AUTH_INVALID_ACCESS_TOKEN", "AUTH_INVALID_DEVICE_CREDENTIALS",
             "AUTH_INVALID_EMAIL_CREDENTIALS": cause = .signInRequired
        case "ACCOUNT_FORBIDDEN", "PERMISSION_DENIED", "USER_INACTIVE", "EMAIL_NOT_VERIFIED",
             "AUTH_EMAIL_VERIFICATION_REQUIRED", "DEVICE_NOT_REGISTERED", "DEVICE_NOT_FOUND",
             "AUTH_REVOKED": cause = .accountUnavailable
        case "TERMS_AGREEMENT_REQUIRED", "TERMS_REAGREEMENT_REQUIRED": cause = .termsRequired
        case "APP_VERSION_UNSUPPORTED": cause = .updateRequired
        case "SERVER_BUSY", "SERVICE_UNDER_MAINTENANCE": cause = .providerUnavailable
        case "VOICE_TUTOR_PROVIDER_ERROR", "VOICE_TUTOR_PROVIDER_PROTOCOL_ERROR": cause = .provider
        case "VOICE_TUTOR_FINALIZATION_FAILED": cause = .finalization
        default:
            switch statusCode {
            case 401: cause = .signInRequired
            case 403: cause = .accountUnavailable
            case 408, 504: cause = .timeout
            case 429: cause = .rateLimited
            case 426: cause = .updateRequired
            case 400, 413, 415, 422: cause = .requestRejected
            case 500, 502, 503: cause = .providerUnavailable
            default: cause = retryable == false ? .service : .unknown
            }
        }
        return failure(cause, strings)
    }

    private static func failure(_ cause: VoiceTutorFailureCause, _ strings: AppStrings) -> VoiceTutorStartupFailurePresentation {
        .init(cause: cause, message: strings.voiceTutorFailureMessage(cause))
    }
}

private enum VoiceTutorStopSource: String {
    case startupFailure, user, dismissal, audioInterruption, mediaFailure
    case identityInvalidated, controlReceiveFailure, providerError, pcmPlaybackFailure
    case localSpeechDeliveryFailure, pauseControlFailure
}

/// Explicit navigation must not own (or cancel) durable call finalization.
/// The task retains its operation until it finishes, even after the call view
/// and its StateObject have been released. Each call admits only one local end.
@MainActor
final class VoiceTutorBackgroundFinalization {
    private(set) var hasStarted = false
    private(set) var isRunning = false
    private var task: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var onExpiration: (@MainActor () -> Void)?
    private var didRequestBackgroundTime = false

    @discardableResult
    func begin(
        prepare: () -> Bool,
        onExpiration: @escaping @MainActor () -> Void = {},
        operation: @escaping @MainActor () async -> Void
    ) -> Bool {
        guard !hasStarted, prepare() else { return false }
        hasStarted = true
        isRunning = true
        acquireBackgroundTime(onExpiration: onExpiration)
        task = Task { @MainActor in
            await operation()
            self.endBackgroundTime()
            self.isRunning = false
            self.task = nil
        }
        return true
    }

    func waitForCompletion() async {
        await task?.value
    }

    func acquireBackgroundTime(onExpiration: @escaping @MainActor () -> Void) {
        guard !didRequestBackgroundTime else { return }
        didRequestBackgroundTime = true
        self.onExpiration = onExpiration
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "Voice Tutor finalization") { [weak self] in
            Task { @MainActor in self?.expireBackgroundTime() }
        }
    }

    func expireBackgroundTime() {
        guard let onExpiration else { return }
        // Release the OS lease first, even if a network send is unresponsive.
        // Consented recordings that already have a manifest retain their
        // existing protected-file retry path; no cleanup task is cancelled.
        endBackgroundTime()
        onExpiration()
    }

    func endBackgroundTime() {
        onExpiration = nil
        let identifier = backgroundTask
        backgroundTask = .invalid
        if identifier != .invalid { UIApplication.shared.endBackgroundTask(identifier) }
    }
}

/// A task-group cancellation alone cannot release a URLSession send that is
/// still waiting for connectivity. Close the captured socket at the deadline
/// so both its audio pump and end send actually resume before REST fallback.
@MainActor
enum VoiceTutorSocketFinalization {
    static func run<Value>(
        timeout: Duration = .seconds(3),
        disconnect: @escaping @MainActor () async -> Void,
        operation: @MainActor () async -> Value
    ) async -> Value {
        let deadline = Task { @MainActor in
            do { try await Task.sleep(for: timeout) } catch { return }
            await disconnect()
        }
        let result = await operation()
        deadline.cancel()
        // If expiration already entered disconnect, join that synchronous
        // socket-close operation before this model can start another attempt.
        await deadline.value
        return result
    }
}

/// Exact server protocol reasons, kept separate from natural-language intent.
/// This is deliberately an allow-list rather than fuzzy text matching: an
/// unknown reason must never turn a transport failure into a successful call.
enum VoiceTutorServerEndReasonPolicy {
    static let quotaExhausted = "QUOTA_EXHAUSTED"

    static func failureCause(_ reason: String?, isPaused: Bool = false) -> VoiceTutorFailureCause {
        switch normalized(reason) {
        case "AUTH_REVOKED": return .accountUnavailable
        case "PROVIDER_ERROR": return .provider
        case "SESSION_TIMEOUT", "PROVIDER_TIMEOUT": return .timeout
        default: return isPaused ? .localControl : .connection
        }
    }

    static func isMonthlyQuotaExhausted(_ reason: String?) -> Bool {
        switch normalized(reason) {
        case quotaExhausted: true
        default: false
        }
    }

    static func isGracefulServerCompletion(_ reason: String?) -> Bool {
        switch normalized(reason) {
        case "USER_ENDED", "TIME_LIMIT", quotaExhausted, "SERVER_FINALIZED": true
        default: false
        }
    }

    static func preservesFinalSpokenPlayout(_ reason: String?) -> Bool {
        switch normalized(reason) {
        case "USER_ENDED", quotaExhausted: true
        default: false
        }
    }

    static func permitsInputRetry(reason: String?, isFinalizing: Bool) -> Bool {
        !isFinalizing && !isMonthlyQuotaExhausted(reason)
    }

    private static func normalized(_ reason: String?) -> String? {
        let value = reason?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return value?.isEmpty == false ? value : nil
    }
}

enum VoiceTutorDiagnosticError {
    static func fields(for error: Error) -> String {
        let value = error as NSError
        let safeDomains = [NSURLErrorDomain, NSCocoaErrorDomain, NSPOSIXErrorDomain, NSOSStatusErrorDomain, AVFoundationErrorDomain]
        let domain = safeDomains.contains(value.domain) ? value.domain : "other"
        let kind: String
        if let parseError = error as? VoiceTutorRealtimeEventParser.ParseError {
            kind = "parser.\(parseError)"
        } else {
            kind = String(describing: type(of: error))
        }
        // Never interpolate Error itself, userInfo, URLs, or descriptions.
        var fields = "errorKind=\(kind) errorDomain=\(domain) errorCode=\(value.code)"
        if let webRTCError = error as? VoiceTutorWebRTCError,
           case let .sdpExchangeFailed(statusCode, backendFailure) = webRTCError {
            let safeStatus = (100...599).contains(statusCode) ? statusCode : 0
            let code = backendFailure?.code?.uppercased()
            let safeCode = code.flatMap { knownBackendCodes.contains($0) ? $0 : nil } ?? "unknown"
            fields += " httpStatus=\(safeStatus) backendCode=\(safeCode)"
        }
        return fields
    }

    private static let knownBackendCodes: Set<String> = [
        "VOICE_TUTOR_PROVIDER_QUOTA_EXHAUSTED", "VOICE_TUTOR_PROVIDER_UNAVAILABLE",
        "VOICE_TUTOR_PRO_REQUIRED", "VOICE_TUTOR_QUOTA_EXCEEDED", "VOICE_TUTOR_SESSION_CONFLICT",
        "VALIDATION_ERROR", "ACCOUNT_FORBIDDEN", "RESOURCE_NOT_FOUND"
    ]
}

struct VoiceTutorSessionQuotaState: Equatable {
    private(set) var remainingSeconds = 0
    private(set) var limitSeconds = 0
    private(set) var reservedSeconds = 0

    mutating func apply(_ quota: BackendVoiceTutorQuota) {
        remainingSeconds = quota.remainingSeconds
        limitSeconds = quota.limitSeconds
        reservedSeconds = quota.reservedSeconds
    }

    mutating func updateRemainingSeconds(_ seconds: Int) {
        remainingSeconds = max(0, seconds)
    }
}

struct VoiceTutorConnectionAttemptFence {
    private(set) var currentID = UUID()

    mutating func begin() -> UUID {
        currentID = UUID()
        return currentID
    }

    func isCurrent(_ candidate: UUID) -> Bool { candidate == currentID }
}

enum VoiceTutorAttemptDelivery {
    @MainActor
    static func deliver<Value: Sendable>(
        isCurrent: @MainActor () -> Bool,
        operation: @MainActor () async -> Value,
        apply: @MainActor (Value) async -> Void
    ) async {
        guard !Task.isCancelled, isCurrent() else { return }
        let value = await operation()
        guard !Task.isCancelled, isCurrent() else { return }
        await apply(value)
    }
}

struct VoiceTutorCaption: Identifiable, Equatable {
    enum Speaker: Equatable {
        case learner
        case tutor
    }

    let id = UUID()
    var speaker: Speaker
    var text: String
    var responseID: String? = nil
    var providerItemID: String? = nil
    var providerItemIDs: Set<String> = []
    var answerID: String? = nil
    var isInterrupted = false
    var isUnsubmittedAnswer = false

    func containsProviderItemID(_ id: String) -> Bool {
        providerItemID == id || providerItemIDs.contains(id)
    }
}

/// One verified question occupies one transcript position. Keep original
/// captions and their IDs so interrupted history and MCP anchors survive.
struct VoiceTutorQuestionTranscriptLayout {
    let canonicalCaptionID: UUID?
    let coveredCaptionIDs: Set<UUID>
    let hidesAssistantDraft: Bool
    var showsQuestionInAnswerCard: Bool { canonicalCaptionID == nil }

    init(draft: VoiceTutorAnswerDraftState, source: VoiceTutorAnswerQuestionSource?,
         captions: [VoiceTutorCaption], assistantResponseID: String? = nil) {
        guard let source, source.belongs(to: draft) else {
            canonicalCaptionID = nil
            coveredCaptionIDs = []
            hidesAssistantDraft = false
            return
        }
        let matches = captions.filter { caption in
            guard caption.speaker == .tutor, !caption.isInterrupted,
                  caption.responseID == source.responseID else { return false }
            let ids = caption.providerItemIDs.union(caption.providerItemID.map { [$0] } ?? [])
            return ids.isEmpty || !ids.isDisjoint(with: source.itemIDs)
        }
        canonicalCaptionID = matches.first?.id
        coveredCaptionIDs = Set(matches.map(\.id))
        hidesAssistantDraft = assistantResponseID == source.responseID
    }
}

/// Remembers final input items for one authenticated connection attempt, even
/// after their visible captions have been trimmed. Text itself is never used
/// as an identity: repeating the same words in a new utterance is legitimate.
struct VoiceTutorLearnerTranscriptState {
    static let maximumItemCount = 4_096
    private var attemptID: UUID?
    private var itemIDs: Set<String> = []

    mutating func beginAttempt(_ attemptID: UUID) {
        self.attemptID = attemptID
        itemIDs = []
    }

    mutating func endLocally() {
        attemptID = nil
        itemIDs = []
    }

    mutating func accept(
        transcript: String,
        itemID: String?,
        attemptID: UUID,
        requiresItemID: Bool
    ) -> Bool {
        guard self.attemptID == attemptID,
              !transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        guard let itemID else { return !requiresItemID }
        guard !itemID.isEmpty, itemID.utf8.count <= 191,
              itemIDs.count < Self.maximumItemCount else { return false }
        return itemIDs.insert(itemID).inserted
    }
}

enum VoiceTutorLiveTextBounds {
    static let maximumDraftCharacters = 16_000
    static let maximumCaptionCharacters = 16_000
    static let maximumTotalCaptionCharacters = 32_000
    static let maximumCaptionCount = 80

    static func appending(delta: String, to draft: String) -> String {
        guard draft.count < maximumDraftCharacters else {
            return draft
        }
        return String((draft + delta).prefix(maximumDraftCharacters))
    }

    static func boundedCaption(_ text: String) -> String {
        String(text.prefix(maximumCaptionCharacters))
    }

    static func trim(_ captions: inout [VoiceTutorCaption]) {
        var totalCharacters = captions.reduce(0) { $0 + $1.text.count }
        while captions.count > maximumCaptionCount ||
            (totalCharacters > maximumTotalCaptionCharacters && captions.count > 1) {
            totalCharacters -= captions.removeFirst().text.count
        }
    }
}

/// Capture the visible draft before cancellation without treating it as a
/// submitted turn or borrowing a replacement answer's text and source aliases.
struct VoiceTutorUnsubmittedAnswerSnapshot: Equatable {
    let answerID: String
    let change: VoiceTutorQuestionChange
    let text: String
    let sourceItemIDs: Set<String>
    let isSubmissionUnconfirmed: Bool

    init?(_ draft: VoiceTutorAnswerDraftState) {
        guard draft.isActive, !draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let answerID = draft.answerID, let studyID = draft.studyID, let recordID = draft.recordID,
              let change = VoiceTutorQuestionChange(studyID: studyID, recordID: recordID) else { return nil }
        self.answerID = answerID
        self.change = change
        text = draft.text
        sourceItemIDs = draft.sourceItemIDs
        isSubmissionUnconfirmed = draft.phase == .submitting
    }

    @discardableResult
    func retainCaption(in captions: inout [VoiceTutorCaption]) -> Bool {
        guard !captions.contains(where: { $0.speaker == .learner && $0.answerID == answerID }) else { return false }
        captions.append(VoiceTutorCaption(speaker: .learner,
            text: VoiceTutorLiveTextBounds.boundedCaption(text), providerItemIDs: sourceItemIDs,
            answerID: answerID, isUnsubmittedAnswer: !isSubmissionUnconfirmed))
        VoiceTutorLiveTextBounds.trim(&captions)
        return true
    }
}

/// Keeps a tutor sentence provisional until the provider confirms that the
/// whole response completed. Transcript `done` only means that transcription
/// for that output item finished; the enclosing response can still fail and be
/// retried, so publishing it to chat at that point would preserve a failed
/// partial answer beside its replacement. Intentional interruption may retain
/// the already visible text with an explicit interrupted marker instead.
struct VoiceTutorAssistantTranscriptState: Equatable {
    private struct Part: Equatable {
        var itemID: String?
        var outputIndex: Int?
        var text = ""
        var isFinal = false
    }
    static let maximumItemCount = 32
    private(set) var draft = ""
    private(set) var responseID: String?
    private var parts: [Part] = []
    private var deltaEventIDs: Set<String> = []

    var providerItemIDs: Set<String> { Set(orderedParts.filter { !$0.text.isEmpty }.compactMap(\.itemID)) }
    var lastProviderItemID: String? { orderedParts.last { !$0.text.isEmpty }?.itemID }

    private var orderedParts: [Part] {
        parts.enumerated().sorted {
            let left = $0.element.outputIndex ?? $0.offset
            let right = $1.element.outputIndex ?? $1.offset
            return left == right ? $0.offset < $1.offset : left < right
        }.map(\.element)
    }

    mutating func beginResponse(_ responseID: String) {
        guard !responseID.isEmpty, self.responseID != responseID else { return }
        discard()
        self.responseID = responseID
    }

    func matchesResponse(_ responseID: String?) -> Bool {
        guard let responseID else { return false }
        return self.responseID == responseID
    }

    mutating func append(delta: String, itemID: String? = nil, outputIndex: Int? = nil, eventID: String? = nil) {
        if let eventID {
            guard deltaEventIDs.count < 2_048, deltaEventIDs.insert(eventID).inserted else { return }
        }
        guard let index = partIndex(itemID: itemID, outputIndex: outputIndex), !parts[index].isFinal else { return }
        updateText(parts[index].text + delta, at: index)
    }

    mutating func stageCompletedTranscript(_ transcript: String?, itemID: String? = nil, outputIndex: Int? = nil) {
        guard let transcript, let index = partIndex(itemID: itemID, outputIndex: outputIndex), !parts[index].isFinal else { return }
        let normalized = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        // A final item replaces only its own partial deltas. Later items and
        // out-of-order item completion must never erase the rest of a response.
        parts[index].isFinal = true
        updateText(normalized, at: index)
    }

    private mutating func partIndex(itemID: String?, outputIndex: Int?) -> Int? {
        let existing = parts.firstIndex { part in
            if let itemID { return part.itemID == itemID }
            if let outputIndex { return part.outputIndex == outputIndex }
            return part.itemID == nil && part.outputIndex == nil
        }
        if let index = existing {
            if parts[index].outputIndex == nil { parts[index].outputIndex = outputIndex }
            return index
        }
        // Older transcript deltas may omit item_id while the final item has
        // it. Match its output position, or the sole unindexed provisional
        // part. Two indexed anonymous items must never overwrite each other.
        if itemID != nil, let index = parts.firstIndex(where: { part in
            guard part.itemID == nil else { return false }
            if let outputIndex, part.outputIndex == outputIndex { return true }
            return parts.count == 1 && part.outputIndex == nil && !part.isFinal
        }) {
            parts[index].itemID = itemID
            if parts[index].outputIndex == nil { parts[index].outputIndex = outputIndex }
            return index
        }
        guard parts.count < Self.maximumItemCount else { return nil }
        parts.append(Part(itemID: itemID, outputIndex: outputIndex))
        return parts.count - 1
    }

    private mutating func updateText(_ text: String, at index: Int) {
        let otherCharacters = parts.enumerated().filter { $0.offset != index }.reduce(0) { $0 + $1.element.text.count }
        let remaining = max(0, VoiceTutorLiveTextBounds.maximumDraftCharacters - otherCharacters)
        parts[index].text = String(text.prefix(remaining))
        draft = String(orderedParts.map(\.text).filter { !$0.isEmpty }.joined(separator: "\n")
            .prefix(VoiceTutorLiveTextBounds.maximumDraftCharacters))
    }

    mutating func commit() -> String? {
        let normalized = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        discard()
        return normalized.isEmpty ? nil : normalized
    }

    /// Preserve what was already visible, without claiming this was a completed
    /// or fully audible tutor turn. Exact ownership also prevents a late old
    /// interruption from taking text that belongs to the replacement response.
    mutating func takeInterruptedCaption(responseID: String, providerItemID: String?) -> VoiceTutorCaption? {
        let sourceIDs = providerItemIDs
        let lastItemID = lastProviderItemID ?? providerItemID
        guard matchesResponse(responseID), let text = commit() else { return nil }
        return VoiceTutorCaption(speaker: .tutor, text: text, responseID: responseID,
            providerItemID: lastItemID, providerItemIDs: sourceIDs, isInterrupted: true)
    }

    @discardableResult
    mutating func retainInterruptedCaption(responseID: String, providerItemID: String?,
                                          in captions: inout [VoiceTutorCaption]) -> Bool {
        guard let caption = takeInterruptedCaption(responseID: responseID, providerItemID: providerItemID),
              !captions.contains(where: { $0.speaker == .tutor && $0.responseID == responseID }) else { return false }
        captions.append(caption)
        VoiceTutorLiveTextBounds.trim(&captions)
        return true
    }

    mutating func discard() {
        draft = ""
        responseID = nil
        parts = []
        deltaEventIDs = []
    }
}

struct VoiceTutorDuplexPlaybackState: Equatable {
    private(set) var inputNeedsRepeat = false
    private(set) var isUserSpeaking = false
    private(set) var assistantResponseActive = false
    private(set) var activeResponseID: String?
    private(set) var tutorInterventionActive = false
    private(set) var isAwaitingActiveResponseAudio = false
    private var isAwaitingInitialResponse = false
    private var hasObservedTutorResponse = false
    private var hasPendingLearnerTurn = false
    private var currentLearnerSpeechSequence: Int?
    private var pendingLearnerSpeechSequence: Int?
    private var latestLearnerSpeechSequence: Int?
    private var activeResponseInputSequence: Int?
    private var interruptedResponseIDs: [String] = []
    private var acousticEpoch: UInt64 = 0
    private var repeatRecoveryEpoch: UInt64?
    private var repeatRejectedResponseID: String?
    private var repeatReplacementResponseID: String?
    private var lastAbandonedResponseID: String?

    var isAwaitingTutorResponse: Bool {
        !isUserSpeaking && !inputNeedsRepeat
            && (isAwaitingInitialResponse || hasPendingLearnerTurn || isAwaitingActiveResponseAudio)
    }

    mutating func setInputNeedsRepeat(_ value: Bool) {
        inputNeedsRepeat = value
        repeatRecoveryEpoch = value ? acousticEpoch : nil
        repeatRejectedResponseID = value ? activeResponseID ?? lastAbandonedResponseID : nil
        repeatReplacementResponseID = nil
    }

    mutating func learnerTranscriptReceived(usesWebRTC: Bool) {
        // Native transcription can arrive after the same turn has failed. Only
        // the next acoustic start is evidence that the learner tried again.
        if !usesWebRTC { setInputNeedsRepeat(false) }
    }

    mutating func awaitInitialResponse() {
        // A delayed ready event must not restart an already observed greeting.
        guard !hasObservedTutorResponse else { return }
        isAwaitingInitialResponse = true
    }

    @discardableResult
    mutating func stopWaitingForInitialResponse() -> Bool {
        guard !hasObservedTutorResponse else { return false }
        hasObservedTutorResponse = true
        isAwaitingInitialResponse = false
        return true
    }

    @discardableResult
    mutating func responseStarted(responseID: String?, isTutorIntervention: Bool) -> Bool {
        guard let responseID, !responseID.isEmpty, !wasInterrupted(responseID) else {
            return false
        }
        guard !matchesActiveResponse(responseID: responseID) else { return true }
        // A replacement may recover an older server's provisional retry hint.
        // Merely starting it is not evidence of audible recovery: retain the
        // hint until that exact response produces audio in this acoustic turn.
        repeatReplacementResponseID = inputNeedsRepeat && !isUserSpeaking
            && repeatRecoveryEpoch == acousticEpoch && responseID != repeatRejectedResponseID ? responseID : nil
        activeResponseInputSequence = pendingLearnerSpeechSequence ?? latestLearnerSpeechSequence ?? 0
        assistantResponseActive = true
        hasObservedTutorResponse = true
        isAwaitingInitialResponse = false
        isAwaitingActiveResponseAudio = true
        // Only learner turns stopped before this response began belong to it.
        // A later interruption keeps its own learner turn pending independently.
        hasPendingLearnerTurn = false
        pendingLearnerSpeechSequence = nil
        activeResponseID = responseID
        tutorInterventionActive = isTutorIntervention
        return true
    }

    func matchesActiveResponse(responseID: String?) -> Bool {
        guard assistantResponseActive,
              let activeResponseID,
              let responseID else {
            return false
        }
        return activeResponseID == responseID
    }

    mutating func assistantAudioBegan(responseID: String?) -> Bool {
        guard matchesActiveResponse(responseID: responseID) else {
            return false
        }
        assistantResponseActive = true
        isAwaitingActiveResponseAudio = false
        if inputNeedsRepeat, !isUserSpeaking, repeatRecoveryEpoch == acousticEpoch,
           responseID == repeatReplacementResponseID {
            setInputNeedsRepeat(false)
        }
        return true
    }

    @discardableResult
    mutating func userSpeechStarted(sequence: Int? = nil, interruptsTutor: Bool = true) -> String? {
        if let sequence, sequence <= 0 { return nil }
        acousticEpoch &+= 1
        setInputNeedsRepeat(false)
        if let sequence { latestLearnerSpeechSequence = sequence }
        isUserSpeaking = true
        currentLearnerSpeechSequence = sequence
        guard interruptsTutor, let responseID = activeResponseID else { return nil }
        interruptResponse(responseID: responseID)
        return responseID
    }

    mutating func userSpeechStopped(sequence: Int? = nil) {
        // A duplicate or stale acoustic stop cannot invent a waiting turn.
        guard isUserSpeaking else { return }
        if let sequence, sequence != currentLearnerSpeechSequence { return }
        hasPendingLearnerTurn = true
        pendingLearnerSpeechSequence = currentLearnerSpeechSequence
        currentLearnerSpeechSequence = nil
        isUserSpeaking = false
    }

    @discardableResult
    mutating func inputSettled(sequence: Int) -> Bool {
        if sequence == 0, !(hasPendingLearnerTurn && pendingLearnerSpeechSequence == 0) {
            // Only the server's silent opening uses zero. It cannot consume a
            // real learner turn or restart waiting when ready arrives later.
            return stopWaitingForInitialResponse()
        }
        guard sequence >= 0, hasPendingLearnerTurn,
              pendingLearnerSpeechSequence == sequence else { return false }
        // The opening can yield to learner input without ever being announced.
        // An exact settled learner turn consumes that initial expectation too,
        // including a ready event delivered after the silent completion.
        stopWaitingForInitialResponse()
        // A silent response is complete, not a request to repeat. Preserve any
        // newer live speech and any independently active tutor audio.
        hasPendingLearnerTurn = false
        pendingLearnerSpeechSequence = nil
        return true
    }

    /// A server recovery is distinct from a pause or learner interruption. Only
    /// the current generation may restore its already-consumed input wait;
    /// duplicate/late recovery cannot acquire the next utterance or response.
    @discardableResult
    mutating func recoverResponse(responseID: String, sequence: Int) -> Bool {
        guard sequence >= 0, !isUserSpeaking, !hasPendingLearnerTurn,
              matchesActiveResponse(responseID: responseID), activeResponseInputSequence == sequence,
              (sequence == 0 ? latestLearnerSpeechSequence == nil : latestLearnerSpeechSequence == sequence) else { return false }
        interruptResponse(responseID: responseID)
        setInputNeedsRepeat(false)
        hasPendingLearnerTurn = true
        pendingLearnerSpeechSequence = sequence
        return true
    }

    /// Retry exhaustion may precede any announced response. Match the acoustic
    /// input independently, while a known active response still requires its ID
    /// so an older failed generation cannot abandon a replacement. The caller
    /// owns discarding that exact response's transcript and native audio.
    @discardableResult
    mutating func acceptInputRetry(sequence: Int, responseID: String?) -> Bool {
        guard sequence >= 0, !isUserSpeaking else { return false }
        if sequence == 0 {
            guard latestLearnerSpeechSequence == nil,
                  !hasPendingLearnerTurn || pendingLearnerSpeechSequence == 0 else { return false }
        } else {
            guard latestLearnerSpeechSequence == sequence else { return false }
        }
        if assistantResponseActive {
            guard matchesActiveResponse(responseID: responseID) else { return false }
        } else {
            guard inputSettled(sequence: sequence) else { return false }
        }
        setInputNeedsRepeat(true)
        repeatRejectedResponseID = responseID
        return true
    }

    mutating func responseFinished(responseID: String) -> Bool {
        guard matchesActiveResponse(responseID: responseID) else { return false }
        assistantResponseActive = false
        isAwaitingActiveResponseAudio = false
        activeResponseID = nil
        activeResponseInputSequence = nil
        tutorInterventionActive = false
        return true
    }

    func wasInterrupted(_ responseID: String) -> Bool {
        interruptedResponseIDs.contains(responseID)
    }

    /// Remember interruptions even when the control notification beats created.
    /// Late audio, captions and completion cannot revive a cancelled answer.
    mutating func interruptResponse(responseID: String) {
        if !wasInterrupted(responseID) {
            interruptedResponseIDs.append(responseID)
            if interruptedResponseIDs.count > 256 { interruptedResponseIDs.removeFirst() }
        }
        _ = abandonResponse(responseID: responseID)
    }

    @discardableResult
    mutating func abandonResponse(responseID: String?) -> Bool {
        guard matchesActiveResponse(responseID: responseID) else { return false }
        lastAbandonedResponseID = responseID
        repeatReplacementResponseID = nil
        assistantResponseActive = false
        isAwaitingActiveResponseAudio = false
        activeResponseID = nil
        activeResponseInputSequence = nil
        tutorInterventionActive = false
        return true
    }

    mutating func reset() {
        self = VoiceTutorDuplexPlaybackState()
    }
}

/// Control-stream state, not an acoustic playout acknowledgement. WebRTC keeps
/// one ordered RTP track across responses, including any locally buffered tail.
/// Its renderer also produces nonzero comfort/concealment noise indefinitely;
/// waiting for PCM silence must never be a prerequisite for another response.
struct VoiceTutorWebRTCResponseState: Equatable {
    private(set) var responseID: String?
    private(set) var responseDone = false
    private(set) var outputBufferStarted = false
    private(set) var outputBufferStopped = false

    var mayIndicateSpeaking: Bool {
        responseID != nil && outputBufferStarted && !outputBufferStopped
    }

    mutating func responseStarted(_ responseID: String?) {
        guard let responseID, !responseID.isEmpty, self.responseID != responseID else { return }
        self.responseID = responseID
        responseDone = false
        outputBufferStarted = false
        outputBufferStopped = false
    }

    mutating func markOutputBufferStarted(_ responseID: String?) {
        guard matches(responseID), !outputBufferStopped else { return }
        outputBufferStarted = true
    }

    mutating func markResponseDone(_ responseID: String?) -> String? {
        guard matches(responseID) else { return nil }
        responseDone = true
        return finishIfReady()
    }

    mutating func markOutputBufferStopped(_ responseID: String?) -> String? {
        guard matches(responseID) else { return nil }
        outputBufferStopped = true
        return finishIfReady()
    }

    @discardableResult
    mutating func abandonResponse(_ responseID: String?) -> Bool {
        guard matches(responseID) else { return false }
        reset()
        return true
    }

    private mutating func finishIfReady() -> String? {
        guard responseDone, outputBufferStopped, let responseID else { return nil }
        reset()
        return responseID
    }

    mutating func reset() {
        self = VoiceTutorWebRTCResponseState()
    }

    private func matches(_ candidate: String?) -> Bool {
        guard let responseID else { return false }
        return candidate == responseID
    }
}

enum VoiceTutorServerEndPlayoutPolicy {
    static func permitsTail(reason: String?, usesWebRTC: Bool) -> Bool {
        usesWebRTC && VoiceTutorServerEndReasonPolicy.preservesFinalSpokenPlayout(reason)
    }

    static func acceptedQuotaNoticeResponseID(
        reason: String?,
        phase: VoiceTutorSessionPhase,
        responseID: String?,
        isQuotaExhaustionNotice: Bool
    ) -> String? {
        guard phase == .ending,
              VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(reason),
              isQuotaExhaustionNotice,
              let responseID,
              !responseID.isEmpty else { return nil }
        return responseID
    }

    static func isExactAbandonedQuotaNotice(
        reason: String?,
        phase: VoiceTutorSessionPhase,
        responseID: String?,
        quotaNoticeResponseID: String?
    ) -> Bool {
        phase == .ending &&
            VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(reason) &&
            responseID != nil && responseID == quotaNoticeResponseID
    }

    /// Only an unsolicited, server-verified spoken terminal response can use
    /// this path. Explicit UI end is already finalizing inside stop() and never
    /// calls it.
    static func spokenEndToken(
        reason: String?,
        usesWebRTC: Bool,
        pending: VoiceTutorLocalPlayoutTailToken?,
        quotaNoticeResponseID: String? = nil
    ) -> VoiceTutorLocalPlayoutTailToken? {
        guard permitsTail(reason: reason, usesWebRTC: usesWebRTC) else { return nil }
        if VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(reason) {
            guard let pending,
                  let quotaNoticeResponseID,
                  pending.responseID == quotaNoticeResponseID else { return nil }
            return pending
        }
        return pending
    }

    /// Ordinary responses report device playout for conversational confirmation.
    /// Monthly exhaustion additionally requires the exact server-marked notice;
    /// an ordinary response can never acknowledge that terminal boundary.
    static func playoutDrainedResponseID(
        reason: String?,
        phase: VoiceTutorSessionPhase,
        usesWebRTC: Bool,
        pending: VoiceTutorLocalPlayoutTailToken?,
        quotaNoticeResponseID: String? = nil
    ) -> String? {
        guard usesWebRTC, let pending else { return nil }
        if !VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(reason) {
            guard reason == nil, phase == .listening || phase == .speaking else { return nil }
            return pending.responseID
        }
        guard phase == .ending,
              let quotaNoticeResponseID,
              pending.responseID == quotaNoticeResponseID else { return nil }
        return quotaNoticeResponseID
    }

    /// The backend emits its spoken terminal lifecycle only after the exact
    /// active response reached both provider completion boundaries. It can
    /// race ahead of the second raw provider event on the control socket. In that
    /// ordering, the still-active response ID is the exact boundary attested by
    /// the server and may be sealed locally before transport teardown.
    static func serverVerifiedFallbackResponseID(
        reason: String?,
        usesWebRTC: Bool,
        pending: VoiceTutorLocalPlayoutTailToken?,
        activeResponseID: String?,
        quotaNoticeResponseID: String? = nil
    ) -> String? {
        guard permitsTail(reason: reason, usesWebRTC: usesWebRTC), pending == nil,
              let activeResponseID, !activeResponseID.isEmpty else { return nil }
        // QUOTA_EXHAUSTED session.ended is also emitted after the bounded
        // fallback when the provider rejected or interrupted the notice. Only
        // the raw exact response boundaries plus the device ACK can attest that
        // notice; the generic lifecycle is not sufficient fallback evidence.
        guard !VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(reason) else { return nil }
        return activeResponseID
    }
}

/// If the control socket disappears after the server has announced a monthly
/// quota ending, that frame alone is not proof that the final sentence played.
/// Keep the direct WebRTC media path alive through the same bounded server
/// grace instead of immediately turning a planned close into an audio cutoff.
enum VoiceTutorQuotaControlLossPolicy {
    static let serverNoticeGraceSeconds: TimeInterval = 20
    static let maximumMediaHoldSeconds: TimeInterval = 30

    static func mediaHoldSeconds(
        hardEndsAt: Date?,
        now: Date,
        hasSealedResponse: Bool
    ) -> TimeInterval {
        guard !hasSealedResponse else { return 0 }
        guard let hardEndsAt else {
            return min(maximumMediaHoldSeconds, serverNoticeGraceSeconds)
        }
        let absoluteNoticeDeadline = hardEndsAt.addingTimeInterval(serverNoticeGraceSeconds)
        return min(maximumMediaHoldSeconds, max(0, absoluteNoticeDeadline.timeIntervalSince(now)))
    }
}

@MainActor
final class VoiceTutorViewModel: ObservableObject {
    @Published private(set) var phase: VoiceTutorSessionPhase = .idle
    @Published private(set) var captions: [VoiceTutorCaption] = []
    @Published private var assistantTranscriptState = VoiceTutorAssistantTranscriptState()
    private var assistantTranscriptItemID: String?
    @Published private(set) var sessionQuota = VoiceTutorSessionQuotaState()
    @Published private(set) var sessionSecondsRemaining: Int?
    @Published private(set) var detail: BackendVoiceTutorSessionDetail?
    @Published private(set) var summaryRefreshState: VoiceTutorSummaryRefreshState = .idle
    @Published private(set) var errorMessage: String?
    @Published private(set) var failureCause: VoiceTutorFailureCause?
    @Published private(set) var isMuted = false
    @Published private(set) var pauseState = VoiceTutorCallPauseState()
    @Published private(set) var isRecording = false
    private(set) var inputNeedsRepeat: Bool {
        get { duplexPlaybackState.inputNeedsRepeat }
        set { duplexPlaybackState.setInputNeedsRepeat(newValue) }
    }
    @Published private(set) var serverEndReason: String?
    @Published private(set) var answerDraftState = VoiceTutorAnswerDraftState()
    @Published private(set) var answerQuestionSource: VoiceTutorAnswerQuestionSource?
    @Published private(set) var sessionState = VoiceTutorSessionState()
    @Published private var retainedGradingResultState = VoiceTutorGradingResultState()
    @Published private(set) var operationState = VoiceTutorOperationState()
    @Published private(set) var userInputState = VoiceTutorUserInputState()

    var quotaRemainingSeconds: Int { sessionQuota.remainingSeconds }
    var quotaLimitSeconds: Int { sessionQuota.limitSeconds }
    var quotaReservedSeconds: Int { sessionQuota.reservedSeconds }
    var assistantTranscriptDraft: String { assistantTranscriptState.draft }
    var assistantTranscriptResponseID: String? { duplexPlaybackState.activeResponseID }
    var isAwaitingTutorResponse: Bool {
        phase == .listening && !pauseState.holdsMicrophone && !inputNeedsRepeat
            && !answerDraftState.isActive && !userInputState.holdsMicrophone
            && duplexPlaybackState.isAwaitingTutorResponse
    }

    var answerDraftText: String { answerDraftState.text }
    var gradingResultState: VoiceTutorGradingResultState {
        guard let target = currentGradingResultTarget,
              retainedGradingResultState.target == target else { return VoiceTutorGradingResultState() }
        return retainedGradingResultState
    }
    var isAnswerCaptureActive: Bool { answerDraftState.phase == .listening }
    var isAnswerReviewAvailable: Bool { [.review, .failed].contains(answerDraftState.phase) }
    var isAnswerSubmitting: Bool { answerDraftState.phase == .submitting }
    var canSubmitReviewedAnswer: Bool { canControlAnswer && answerDraftState.canSubmit }
    var canSkipReviewedQuestion: Bool { canControlAnswer && answerDraftState.canSkip }
    var canCancelLearning: Bool {
        usesWebRTC && phase.isLive && !isFinalizing && !userInputState.holdsMicrophone
            && activeConnection?.isCurrent() == true
            && answerDraftState.canCancel(in: sessionState.snapshot, minimumRevision: studyFocus.revision)
    }
    var presentationCaptions: [VoiceTutorCaption] {
        let hidden = Set(answerSourceItemIDs.compactMap { learnerCaptionIDsByItemID[$0] }).union(heldAnswerCaptionIDs)
        return captions.filter { !hidden.contains($0.id) }
    }
    private var canControlAnswer: Bool {
        usesWebRTC && phase.isLive && !isFinalizing && !pauseState.holdsMicrophone && !userInputState.holdsMicrophone
            && !answerDraftState.isEditing
            && activeConnection?.isCurrent() == true && answerDraftState.belongsToCurrentLesson(sessionState.snapshot)
            && answerDraftState.revision >= studyFocus.revision
    }

    @Published private(set) var studyFocus = VoiceTutorStudyFocusState()

    private let appState: AppState
    private let audioEngine: VoiceTutorAudioEngine
    private let transport: VoiceTutorWebSocketTransport
    private let recordingConsent: Bool
    private var webRTCTransport: VoiceTutorWebRTCTransport?
    private var recorder: VoiceTutorSessionRecorder?
    private var usesWebRTC = false
    private var receiveTask: Task<Void, Never>?
    private var audioSendTask: Task<Void, Never>?
    private var audioSendContinuation: AsyncStream<Data>.Continuation?
    private var localSpeechEvents: VoiceTutorCallControlEventStream?
    private var localSpeechSendTask: Task<Void, Never>?
    private var pauseTimeoutTask: Task<Void, Never>?
    private var serverEndContinuation: AsyncStream<VoiceTutorRealtimeEnded>.Continuation?
    private var heartbeatTask: Task<Void, Never>?
    private var countdownTask: Task<Void, Never>?
    private var answerEditorID: String?
    private var answerEditorPauseOwned = false
    private var answerEditorClosingID: String?
    private var finishWordTask: Task<Void, Never>?
    private var finishWordRequestID: String?
    private var pendingGradingRefresh: VoiceTutorGradingResultState.Request?
    private var gradingResultTask: Task<Void, Never>?
    private var gradingResultTimeoutTask: Task<Void, Never>?
    private var activeConnection: VoiceTutorLiveConnection?
    private var sessionID: String?
    private var hardEndsAt: Date?
    private var isFinalizing = false
    private var backgroundFinalization = VoiceTutorBackgroundFinalization()
    private var didCloseLocalMedia = false
    private var didRequestLocalEnd = false
    @Published private var duplexPlaybackState = VoiceTutorDuplexPlaybackState()
    private var webRTCResponseState = VoiceTutorWebRTCResponseState()
    private var pendingSpokenEndPlayoutTail: VoiceTutorLocalPlayoutTailToken?
    private var quotaExhaustionNoticeResponseID: String?
    private var terminalPlayoutDrainTask: Task<Void, Never>?
    private var connectionAttemptFence = VoiceTutorConnectionAttemptFence()
    private var summaryRequestID = UUID()
    private var summaryContextValidity: (@MainActor @Sendable () -> Bool)?
    private var changedQuestions: [VoiceTutorQuestionChange] = []
    private var learnerCaptionIDsByItemID: [String: UUID] = [:]
    private var learnerTranscriptState = VoiceTutorLearnerTranscriptState()
    @Published private var answerSourceItemIDs: Set<String> = []
    private var heldAnswerCaptionIDs: Set<UUID> = []
    private var knownAnswerRecordIDs: [String: String] = [:]
    private var submittedAnswerIDs: Set<String> = []

    init(
        appState: AppState,
        recordingConsent: Bool = false,
        audioEngine: VoiceTutorAudioEngine = VoiceTutorAudioEngine(),
        transport: VoiceTutorWebSocketTransport = VoiceTutorWebSocketTransport()
    ) {
        self.appState = appState
        self.recordingConsent = recordingConsent
        self.audioEngine = audioEngine
        self.transport = transport
        let quota = appState.voiceTutorStatus?.quota ?? appState.billingStatus?.voiceTutor?.quota
        if let quota { sessionQuota.apply(quota) }
    }

    func start() async {
        guard !isFinalizing, !backgroundFinalization.isRunning,
              phase == .idle || phase == .failed else {
            return
        }
        let attemptID = connectionAttemptFence.begin()
        backgroundFinalization = VoiceTutorBackgroundFinalization()
        didCloseLocalMedia = false
        didRequestLocalEnd = false
        learnerTranscriptState.beginAttempt(attemptID)
        studyFocus.beginAttempt(attemptID)
        summaryRequestID = UUID()
        summaryContextValidity = nil
        summaryRefreshState = .idle
        changedQuestions = []
        answerDraftState = VoiceTutorAnswerDraftState()
        sessionState = VoiceTutorSessionState()
        clearGradingResult()
        operationState = VoiceTutorOperationState()
        userInputState = VoiceTutorUserInputState()
        learnerCaptionIDsByItemID = [:]
        answerSourceItemIDs = []
        heldAnswerCaptionIDs = []
        knownAnswerRecordIDs = [:]
        submittedAnswerIDs = []
        stopLocalSpeechEventPump()
        cancelTerminalPlayoutDrain()
        sessionID = nil
        clearSessionCountdown()
        isMuted = false
        pauseState = VoiceTutorCallPauseState()
        inputNeedsRepeat = false
        serverEndReason = nil
        errorMessage = nil
        failureCause = nil
        detail = nil
        captions = []
        answerQuestionSource = nil
        assistantTranscriptState.discard()
        assistantTranscriptItemID = nil
        duplexPlaybackState.reset()
        webRTCResponseState.reset()
        pendingSpokenEndPlayoutTail = nil
        quotaExhaustionNoticeResponseID = nil
        usesWebRTC = false
        isRecording = false
        phase = .requestingPermission
        let requestedOwnerID = appState.communityProfile?.id
        let requestedLifecycleGeneration = VoiceTutorRecordingStore.lifecycleGeneration()

        let hasMicrophonePermission = await VoiceTutorAudioEngine.requestMicrophonePermission()
        guard connectionAttemptFence.isCurrent(attemptID), phase == .requestingPermission else {
            return
        }
        guard hasMicrophonePermission else {
            errorMessage = appState.strings.voiceTutorMicrophoneDenied
            failureCause = .microphone
            phase = .failed
            return
        }
        guard phase == .requestingPermission else {
            return
        }
        guard appState.isCommunitySessionActive,
              appState.communityProfile?.id == requestedOwnerID,
              VoiceTutorRecordingStore.isCurrentLifecycleGeneration(requestedLifecycleGeneration) else {
            phase = .ended
            return
        }

        do {
            phase = .connecting
            // Validate and warm the bundled acoustic model before allocating a
            // quota reservation or provider call. No model/network download.
            let speechScorer = try await VoiceTutorSileroSpeechScorer.prepareBundled()
            guard connectionAttemptFence.isCurrent(attemptID), phase == .connecting else { return }
            guard appState.isCommunitySessionActive,
                  appState.communityProfile?.id == requestedOwnerID,
                  VoiceTutorRecordingStore.isCurrentLifecycleGeneration(requestedLifecycleGeneration) else {
                phase = .ended
                return
            }
            let connection = try await appState.createVoiceTutorConnection(
                recordingConsent: recordingConsent
            )
            guard connectionAttemptFence.isCurrent(attemptID),
                  phase == .connecting, connection.isCurrent() else {
                _ = try? await connection.endSession()
                return
            }
            activeConnection = connection
            sessionID = connection.session.sessionId
            hardEndsAt = connection.session.hardEndsAt
            sessionQuota.apply(connection.session.quota)
            updateSessionCountdown()

            if recordingConsent, connection.session.recording?.enabled == true {
                recorder = try VoiceTutorSessionRecorder(
                    sessionID: connection.session.sessionId,
                    ownerUserID: connection.ownerUserID,
                    lifecycleGeneration: connection.recordingLifecycleGeneration
                )
                isRecording = true
            }

            usesWebRTC = connection.webRTC != nil
            if let webRTCConnection = connection.webRTC {
                let webRTCTransport = VoiceTutorWebRTCTransport(preparedSpeechScorer: speechScorer)
                self.webRTCTransport = webRTCTransport
                let speechEvents = startLocalSpeechEventPump(attemptID: attemptID, connection: connection)
                webRTCTransport.onLocalSpeechActivity = { event in
                    // Called serially from the capture gate, with no actor hop
                    // per audio frame and no microphone bytes in the queue.
                    speechEvents.yield(event)
                }
                webRTCTransport.onRenderedPCM = { [weak self] uptime in
                    Task { @MainActor [weak self] in
                        guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                        self?.handleWebRTCRenderedPCM(at: uptime)
                    }
                }
                webRTCTransport.onInterruption = { [weak self] in
                    Task { @MainActor [weak self] in
                        guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                        await self?.handleAudioSessionInterruption()
                    }
                }
                webRTCTransport.onConnectionFailure = { [weak self] in
                    Task { @MainActor [weak self] in
                        guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                        await self?.handleWebRTCConnectionFailure()
                    }
                }
                webRTCTransport.onDiagnostic = { [weak self] message in
                    Task { @MainActor [weak self] in
                        guard let self,
                              self.connectionAttemptFence.isCurrent(attemptID),
                              connection.isCurrent() else { return }
                        self.appState.logVoiceTutorEvent(
                            "sessionId=\(connection.session.sessionId) phase=\(self.phase) \(message)"
                        )
                    }
                }
                try await webRTCTransport.connect(
                    sdpRequest: webRTCConnection.sdpRequest,
                    recorder: recorder
                )
                guard connectionAttemptFence.isCurrent(attemptID) else {
                    webRTCTransport.close()
                    return
                }
                guard connection.isCurrent() else { throw CancellationError() }
                guard phase == .connecting else {
                    webRTCTransport.close()
                    return
                }
                // The backend can claim /control only after SDP has attached
                // the provider call and transitioned this session to ACTIVE.
                try await transport.connect(
                    request: VoiceTutorTurnProtocol.addingCapability(to: webRTCConnection.controlRequest),
                    localSpeechAttemptID: attemptID
                )
            } else {
                try await transport.connect(request: connection.request)
                guard connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else {
                    throw CancellationError()
                }
                guard phase == .connecting else {
                    await transport.disconnect(closeCode: .goingAway)
                    return
                }
                let audioSendContinuation = startAudioSendPump()
                let recorder = recorder
                let pcmSampleRate = VoiceTutorAudioEngine.sampleRate
                try await audioEngine.start(
                    onAudioChunk: { audio in
                        if let frame = VoiceTutorPCMFrame(
                            pcm16Mono: audio,
                            sampleRate: pcmSampleRate,
                            capturedAtUptime: ProcessInfo.processInfo.systemUptime
                        ) {
                            recorder?.append(frame, participant: .learner)
                        }
                        audioSendContinuation.yield(audio)
                    },
                    onPlaybackCompleted: { [weak self] responseID in
                        Task { @MainActor [weak self] in
                            guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                            await self?.handleTutorPlaybackCompleted(responseID: responseID, attemptID: attemptID)
                        }
                    },
                    onInterruption: { [weak self] in
                        Task { @MainActor [weak self] in
                            guard self?.connectionAttemptFence.isCurrent(attemptID) == true else { return }
                            await self?.handleAudioSessionInterruption()
                        }
                    },
                    isCurrent: { [weak self] in
                        self?.connectionAttemptFence.isCurrent(attemptID) == true
                            && self?.phase == .connecting
                            && self?.didCloseLocalMedia == false && connection.isCurrent()
                    }
                )
            }
            guard connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else {
                throw CancellationError()
            }
            startReceivingEvents(attemptID: attemptID)
            startHeartbeat()
            guard phase == .connecting || phase == .listening || phase == .speaking else {
                closeLocalMedia()
                stopAudioSendPump()
                stopLocalSpeechEventPump()
                stopHeartbeat()
                await transport.disconnect(closeCode: .goingAway)
                // A dismissed SwiftUI startup task may already be cancelled.
                // It must not take the recorder away from retained cleanup,
                // whose uncancelled task owns manifest creation.
                if !isFinalizing && !backgroundFinalization.hasStarted {
                    await finishRecordingIfNeeded()
                }
                return
            }
            startCountdown()
        } catch is CancellationError where phase != .connecting {
            // A dismissal/user stop can close WebRTC while an awaited SDP
            // step is resuming. Re-run idempotent teardown without replacing the
            // terminal UI state chosen by the stop path.
            guard connectionAttemptFence.isCurrent(attemptID) else { return }
            webRTCTransport?.close()
            webRTCTransport = nil
            stopLocalSpeechEventPump()
            return
        } catch {
            guard connectionAttemptFence.isCurrent(attemptID), !isFinalizing,
                  phase.isLive else { return }
            let failure = VoiceTutorStartupFailurePolicy.presentation(
                for: error,
                strings: appState.strings
            )
            errorMessage = failure.message
            failureCause = failure.cause
            logDiagnostic("event=startup_failed \(VoiceTutorDiagnosticError.fields(for: error))", isWarning: true)
            await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .startupFailure)
        }
    }

    func beginAnswerEditing(answerID: String) -> Bool {
        guard usesWebRTC, phase.isLive, !isFinalizing, activeConnection?.isCurrent() == true,
              !userInputState.holdsMicrophone, pauseState.mode != .resuming,
              let controls = localSpeechEvents,
              answerDraftState.beginEditing(answerID: answerID) else { return false }
        answerEditorID = answerID
        answerEditorClosingID = nil
        answerEditorPauseOwned = false
        if pauseState.mode == .active, let pause = pauseState.requestPause() {
            answerEditorPauseOwned = true
            controls.yield(pause)
            guard webRTCTransport?.setMuted(true) == true else {
                Task { await failPause() }; return false
            }
            controls.yield(VoiceTutorPauseControl(kind: .inputQuiesced, sequence: pause.sequence))
            startPauseTimeout(sequence: pause.sequence)
        } else if webRTCTransport?.setMuted(true) != true {
            Task { await failPause() }; return false
        }
        return true
    }

    func endAnswerEditing(answerID: String) {
        guard answerEditorID == answerID else { return }
        answerEditorID = nil
        persistVoiceAnswerDraft(force: answerDraftState.hasUserEdited)
        if answerEditorPauseOwned, phase.isLive, !isFinalizing {
            answerEditorClosingID = answerID
            resumeAfterAnswerEditingIfReady()
        } else if pauseState.mode == .pausing, phase.isLive, !isFinalizing {
            // An earlier user pause still owns the call. Keep discarding its
            // pending ASR until that exact pause is acknowledged, without
            // acquiring ownership or scheduling an unwanted resume.
            answerEditorClosingID = answerID
        } else {
            answerDraftState.endEditing(answerID: answerID)
            restoreAnswerMicrophoneGate()
        }
    }

    private func resumeAfterAnswerEditingIfReady() {
        guard answerEditorPauseOwned, answerEditorClosingID != nil,
              let controls = localSpeechEvents, let resume = pauseState.requestResume() else { return }
        controls.yield(resume)
        startPauseTimeout(sequence: resume.sequence)
    }

    private func restoreAnswerMicrophoneGate() {
        guard phase.isLive, !isFinalizing, activeConnection?.isCurrent() == true else { return }
        if webRTCTransport?.setMuted(isMuted || pauseState.holdsMicrophone
            || answerDraftState.holdsMicrophone || userInputState.holdsMicrophone) != true {
            Task { await failPause() }
        }
    }

    func updateAnswerDraft(_ text: String) {
        guard phase.isLive, !isFinalizing, activeConnection?.isCurrent() == true,
              answerDraftState.edit(text) else { return }
        persistVoiceAnswerDraft(force: true)
    }

    /// Retry only the current saved grade. This never resubmits an answer or
    /// starts grading again, and repeated completion snapshots do not poll.
    func retryGradingResult() {
        guard let target = currentGradingResultTarget,
              retainedGradingResultState.target == target,
              let request = retainedGradingResultState.retry() else { return }
        startGradingResultRequest(request, refreshStatus: sessionState.snapshot?.phase == .gradingUnavailable)
    }

    private var currentGradingResultTarget: VoiceTutorGradingResultTarget? {
        guard usesWebRTC, phase.isLive, !isFinalizing,
              let connection = activeConnection, connection.isCurrent(),
              let sessionID, sessionID == connection.session.sessionId,
              studyFocus.revision == 0 || studyFocus.focus != nil,
              studyFocus.focus.map({ $0.studyID == sessionState.snapshot?.studyID }) ?? true else { return nil }
        if let current = VoiceTutorGradingResultTarget(snapshot: sessionState.snapshot, sessionID: sessionID,
            attemptID: connectionAttemptFence.currentID, ownerUserID: connection.ownerUserID,
            minimumRevision: studyFocus.revision) { return current }
        guard let retained = retainedGradingResultState.target,
              retained.sessionID == sessionID, retained.attemptID == connectionAttemptFence.currentID,
              retained.ownerUserID == connection.ownerUserID, retained.revision >= studyFocus.revision,
              retained.remainsVisible(after: sessionState.snapshot) else { return nil }
        return retained
    }

    private func reconcileGradingResult() {
        let target = currentGradingResultTarget
        if target == nil { clearGradingResult(); return }
        guard let request = retainedGradingResultState.reconcile(target: target) else { return }
        startGradingResultRequest(request)
    }

    private func startGradingResultRequest(_ request: VoiceTutorGradingResultState.Request, refreshStatus: Bool = false) {
        gradingResultTask?.cancel()
        gradingResultTimeoutTask?.cancel()
        pendingGradingRefresh = refreshStatus ? request : nil
        gradingResultTask = Task { [weak self] in
            guard let self, self.isCurrentGradingResultRequest(request) else { return }
            if refreshStatus {
                do { try await self.transport.sendGradingRefresh(recordID: request.target.recordID, attemptID: request.target.attemptID) }
                catch {
                    guard self.isCurrentGradingResultRequest(request) else { return }
                    _ = self.retainedGradingResultState.fail(for: request)
                    self.pendingGradingRefresh = nil
                    self.gradingResultTimeoutTask?.cancel()
                }
                // The authoritative graded snapshot starts the record GET.
                // Keep the request loading (and controls disabled) until then.
                return
            }
            // loadStudyRecordDetail uses the authenticated records use case and
            // fences account, backend and language changes before and after GET.
            // It does not replace the active answer or write a parallel cache.
            let record = await self.appState.loadStudyRecordDetail(recordID: request.target.recordID)
            guard !Task.isCancelled, self.isCurrentGradingResultRequest(request) else { return }
            _ = self.retainedGradingResultState.resolve(record, for: request)
            self.gradingResultTimeoutTask?.cancel()
            self.gradingResultTimeoutTask = nil
            self.gradingResultTask = nil
            if let target = self.retainedGradingResultState.claimReadyStatusRefresh(after: self.sessionState.snapshot),
               self.currentGradingResultTarget == target {
                do {
                    try await self.transport.sendGradingRefresh(recordID: target.recordID, attemptID: target.attemptID)
                } catch {
                    // An exact authenticated result is already available. A
                    // failed control refresh cannot erase it or resubmit work.
                    self.logDiagnostic("event=ready_grading_status_refresh_failed", isWarning: true)
                }
            }
        }
        gradingResultTimeoutTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(20)) } catch { return }
            guard let self, !Task.isCancelled, self.isCurrentGradingResultRequest(request) else { return }
            _ = self.retainedGradingResultState.fail(for: request, reason: .timedOut)
            self.pendingGradingRefresh = nil
            self.gradingResultTask?.cancel()
            self.gradingResultTask = nil
            self.gradingResultTimeoutTask = nil
        }
    }

    private func isCurrentGradingResultRequest(_ request: VoiceTutorGradingResultState.Request) -> Bool {
        currentGradingResultTarget == request.target && retainedGradingResultState.accepts(request)
    }

    private func clearGradingResult() {
        pendingGradingRefresh = nil
        gradingResultTask?.cancel()
        gradingResultTask = nil
        gradingResultTimeoutTask?.cancel()
        gradingResultTimeoutTask = nil
        retainedGradingResultState.clear()
    }

    func finishAnswerCapture() async {
        guard canControlAnswer, let controls = localSpeechEvents,
              let command = answerDraftState.requestFinish() else { return }
        // setMuted synchronously emits the final speech.stop into this FIFO.
        // The finish fence cannot overtake the last acoustic boundary.
        guard webRTCTransport?.setMuted(true) == true else {
            await failAnswerControl()
            return
        }
        controls.yield(command)
        persistVoiceAnswerDraft(force: answerDraftState.hasUserEdited)
    }

    func submitReviewedAnswer() async {
        guard canSubmitReviewedAnswer, let controls = localSpeechEvents,
              let command = answerDraftState.requestSubmit() else { return }
        persistVoiceAnswerDraft(force: true)
        guard webRTCTransport?.setMuted(true) == true else {
            await failAnswerControl()
            return
        }
        controls.yield(command)
    }

    func skipReviewedQuestion() async {
        guard canSkipReviewedQuestion, let controls = localSpeechEvents,
              let command = answerDraftState.requestSkip() else { return }
        persistVoiceAnswerDraft(force: answerDraftState.hasUserEdited)
        guard webRTCTransport?.setMuted(true) == true else {
            await failAnswerControl()
            return
        }
        controls.yield(command)
    }

    func cancelLearning() async {
        guard canCancelLearning, let controls = localSpeechEvents,
              let command = answerDraftState.requestCancel() else { return }
        // This abandons only the current voice exercise. Keep its unsubmitted
        // draft; the server must neither submit nor skip the saved question.
        persistVoiceAnswerDraft(force: true)
        guard webRTCTransport?.setMuted(true) == true else {
            await failAnswerControl()
            return
        }
        controls.yield(command)
    }

    private func persistVoiceAnswerDraft(force: Bool) {
        guard force || answerDraftState.shouldPersistAutomatically,
              let connection = activeConnection, connection.isCurrent(),
              let studyID = answerDraftState.studyID, let recordID = answerDraftState.recordID,
              let change = VoiceTutorQuestionChange(studyID: studyID, recordID: recordID) else { return }
        appState.saveVoiceTutorAnswerDraft(answerDraftState.text, for: change, validity: { connection.isCurrent() })
    }

    private func preserveUnsubmittedAnswerDraft(_ snapshot: VoiceTutorUnsubmittedAnswerSnapshot?) {
        guard let snapshot, let connection = activeConnection, connection.isCurrent() else { return }
        // Use the same record-ID draft store as typed answers. This never sends
        // an answer, grades it, or adds evidence to the server's learning record.
        appState.saveVoiceTutorAnswerDraft(snapshot.text, for: snapshot.change, validity: { connection.isCurrent() })
        snapshot.retainCaption(in: &captions)
    }

    private func failAnswerControl() async {
        errorMessage = appState.strings.voiceTutorConnectionFailed
        failureCause = .localControl
        await stop(shouldNotifyServerOverSocket: true, outcome: .failed, source: .localSpeechDeliveryFailure)
    }

    func toggleMute() {
        guard phase.isLive, !pauseState.holdsMicrophone, !answerDraftState.holdsMicrophone,
              !userInputState.holdsMicrophone,
              activeConnection?.isCurrent() == true else {
            return
        }
        isMuted.toggle()
        if usesWebRTC {
            webRTCTransport?.setMuted(isMuted)
        } else {
            audioEngine.setMuted(isMuted)
        }
    }

    func togglePause() async {
        guard usesWebRTC, phase == .listening || phase == .speaking, !isFinalizing,
              !userInputState.holdsMicrophone, !answerDraftState.isEditing,
              activeConnection?.isCurrent() == true, let controls = localSpeechEvents else { return }
        let command: VoiceTutorPauseControl
        if pauseState.mode == .active {
            guard let request = pauseState.requestPause() else { return }
            command = request
            // All three operations are synchronous on this actor. updateGate
            // emits any final stop under its own gate lock into this SAME FIFO.
            // Hold must reach the server before that stop can release a reply.
            controls.yield(request)
            guard webRTCTransport?.setMuted(true) == true else {
                await failPause()
                return
            }
            controls.yield(VoiceTutorPauseControl(kind: .inputQuiesced, sequence: request.sequence))
        } else {
            guard let request = pauseState.requestResume() else { return }
            command = request
            // Keep the microphone closed until the matching server clear ACK.
            controls.yield(request)
        }
        inputNeedsRepeat = false
        startPauseTimeout(sequence: command.sequence)
    }

    private func startPauseTimeout(sequence: Int64) {
        pauseTimeoutTask?.cancel()
        let attemptID = connectionAttemptFence.currentID
        let timeout = pauseState.acknowledgementTimeoutSeconds
        pauseTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: timeout * 1_000_000_000)
            } catch { return }
            guard let self, !Task.isCancelled, self.connectionAttemptFence.isCurrent(attemptID),
                  self.activeConnection?.isCurrent() == true, self.phase.isLive, !self.isFinalizing,
                  self.pauseState.sequence == sequence, self.pauseState.isAwaitingAcknowledgement else { return }
            // Do not cancel this task's own settlement from stop's cleanup.
            self.pauseTimeoutTask = nil
            await self.failPause()
        }
    }

    private func failPause() async {
        guard phase.isLive, !isFinalizing else { return }
        errorMessage = appState.strings.voiceTutorPauseFailed
        failureCause = .localControl
        await stop(shouldNotifyServerOverSocket: true, outcome: .failed, source: .pauseControlFailure)
    }

    func stopForUser() {
        stopLocallyAndFinalizeInBackground(source: .user)
    }

    func updateUserInput(requestID: String, answer: VoiceTutorUserInputAnswer) {
        userInputState.update(requestID: requestID, answer: answer)
    }

    func submitUserInput(requestID: String, cancel: Bool = false) {
        guard usesWebRTC, phase.isLive, !isFinalizing, activeConnection?.isCurrent() == true,
              let controls = localSpeechEvents,
              let command = userInputState.submit(requestID: requestID, cancel: cancel) else { return }
        controls.yield(command)
    }

    func appDidEnterBackground() {
        guard phase.isLive || phase == .ending else { return }
        // The active playAndRecord session and audio background mode keep the
        // existing media/control connection alive. Do not mute, pause, settle,
        // or invalidate the response that is currently speaking or using tools.
        persistVoiceAnswerDraft(force: answerDraftState.hasUserEdited)
        logDiagnostic("event=app_backgrounded callContinues=1")
    }

    func appDidBecomeActive() {
        guard phase.isLive || phase == .ending else { return }
        updateSessionCountdown()
        logDiagnostic("event=app_foregrounded callContinues=1")
    }

    func stopForDismissal() {
        if phase.shouldCloseFinalizingMediaForDismissal(isFinalizing: isFinalizing) {
            didRequestLocalEnd = true
            acquireFinalizationBackgroundTime()
            // A server-spoken-end tail may be awaiting its short local render
            // fence. Explicit dismissal wins: invalidate playout while the
            // already-running control/REST settlement continues on its own.
            closeLocalMedia()
            stopAudioSendPump()
            stopLocalSpeechEventPump()
            return
        }
        guard phase.isLive || phase == .ending, !isFinalizing else {
            return
        }
        stopLocallyAndFinalizeInBackground(source: .dismissal)
    }

    private func stopLocallyAndFinalizeInBackground(source: VoiceTutorStopSource) {
        guard phase.isLive || phase == .ending else { return }
        didRequestLocalEnd = true
        if isFinalizing {
            // A server-driven finalization already owns settlement. Dismissal
            // only closes its remaining local playout; never send a second end.
            acquireFinalizationBackgroundTime()
            closeLocalMedia()
            return
        }
        backgroundFinalization.begin(
            prepare: { prepareStop(outcome: .ended, source: source) },
            onExpiration: { [weak self, transport] in
                self?.clearServerEndWaiter()
                Task { await transport.disconnect(closeCode: .goingAway) }
            },
            operation: { [self] in
                await finalizeStop(shouldNotifyServerOverSocket: true, outcome: .ended)
            }
        )
    }

    private func acquireFinalizationBackgroundTime() {
        // A server-ended receive task already retains this model and owns
        // settlement. Add only background time when its UI is dismissed.
        backgroundFinalization.acquireBackgroundTime { [weak self, transport] in
            self?.clearServerEndWaiter()
            Task { await transport.disconnect(closeCode: .goingAway) }
        }
    }

    private func closeLocalMedia() {
        // A late completion from this call must not deactivate a newer call's
        // shared AVAudioSession. Teardown is synchronous and once per attempt.
        guard !didCloseLocalMedia else { return }
        didCloseLocalMedia = true
        recorder?.stopAcceptingFrames()
        audioEngine.stop()
        webRTCTransport?.close()
        webRTCTransport = nil
    }

    private func handleAudioSessionInterruption() async {
        guard phase.isLive, !isFinalizing else {
            return
        }
        errorMessage = appState.strings.voiceTutorAudioInterrupted
        failureCause = .audio
        await stop(shouldNotifyServerOverSocket: true, outcome: .failed, source: .audioInterruption)
    }

    private func handleWebRTCConnectionFailure() async {
        guard phase.isLive, !isFinalizing else { return }
        errorMessage = appState.strings.voiceTutorConnectionFailed
        failureCause = .connection
        await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .mediaFailure)
    }

    private func logDiagnostic(_ message: String, isWarning: Bool = false) {
        appState.logVoiceTutorEvent(
            "sessionId=\(sessionID ?? "none") phase=\(phase) \(message)",
            isWarning: isWarning
        )
    }

    private func diagnosticResponseID(_ responseID: String?) -> String {
        guard let responseID else { return "none" }
        return VoiceTutorOperationEvent.isSafeIdentifier(responseID, maximumLength: 191) ? responseID : "invalid"
    }

    private func stop(
        shouldNotifyServerOverSocket: Bool,
        outcome: VoiceTutorSessionPhase = .ended,
        source: VoiceTutorStopSource
    ) async {
        guard prepareStop(outcome: outcome, source: source) else { return }
        await finalizeStop(shouldNotifyServerOverSocket: shouldNotifyServerOverSocket, outcome: outcome)
    }

    private func prepareStop(outcome: VoiceTutorSessionPhase, source: VoiceTutorStopSource) -> Bool {
        guard !isFinalizing, phase.isLive || phase == .ending else {
            return false
        }
        logDiagnostic("event=stop_requested source=\(source.rawValue)", isWarning: outcome == .failed)
        isFinalizing = true
        phase = .ending
        closeLocalMedia()
        if source == .user || source == .dismissal, activeConnection?.isCurrent() == true {
            preserveInterruptedAssistantTranscript(responseID: duplexPlaybackState.activeResponseID)
        }
        sessionState.endLocally()
        clearGradingResult()
        operationState.endLocally()
        userInputState.endLocally()
        learnerTranscriptState.endLocally()
        preserveUnsubmittedAnswerDraft(VoiceTutorUnsubmittedAnswerSnapshot(answerDraftState))
        if answerDraftState.hasUserEdited { persistVoiceAnswerDraft(force: true) }
        answerDraftState.endLocally()
        cancelTerminalPlayoutDrain()
        stopLocalSpeechEventPump()
        stopHeartbeat()
        clearSessionCountdown()
        return true
    }

    private func finalizeStop(
        shouldNotifyServerOverSocket: Bool,
        outcome: VoiceTutorSessionPhase
    ) async {
        // Start sealing the local consented recording immediately. A stalled
        // socket/REST call must not leave only raw audio without a retryable
        // manifest. Upload is deferred until after the end request is sent.
        async let pendingRecording = finalizeRecordingIfNeeded()
        let shouldNotifyServerOverSocket = shouldNotifyServerOverSocket
            && activeConnection?.isCurrent() == true
        let finishingTransport = transport
        let serverEnded = await VoiceTutorSocketFinalization.run(disconnect: { [weak self] in
            await finishingTransport.disconnect(closeCode: .goingAway)
            self?.clearServerEndWaiter()
        }, operation: {
            await finishAudioSendPump()
            let serverEndStream = shouldNotifyServerOverSocket ? makeServerEndStream() : nil
            var didSendSocketEnd = false
            if shouldNotifyServerOverSocket {
                do {
                    try await finishingTransport.sendSessionEnd()
                    didSendSocketEnd = true
                } catch {
                    didSendSocketEnd = false
                }
            }
            let ended: VoiceTutorRealtimeEnded?
            if didSendSocketEnd, let serverEndStream {
                ended = await waitForServerEnd(from: serverEndStream)
            } else {
                ended = nil
            }
            clearServerEndWaiter()
            return ended
        })

        var endedDetail: BackendVoiceTutorSessionDetail?
        if serverEnded == nil, let activeConnection {
            logDiagnostic("event=rest_end_requested")
            endedDetail = try? await activeConnection.endSession()
        }
        // A socket failure reaches this method from receiveTask itself. Closing
        // the socket ends the receive loop without cancelling REST settlement.
        receiveTask = nil
        await transport.disconnect()
        await uploadFinalizedRecording(await pendingRecording)
        await finishSession(
            initialDetail: endedDetail,
            pollAfterMilliseconds: serverEnded?.pollAfterMilliseconds
                ?? endedDetail?.pollAfterMilliseconds,
            outcome: .completed(outcome: outcome, serverState: nil, serverReason: serverEnded?.reason)
        )
    }

    private func stopForInvalidatedContext() async {
        // Account replacement is terminal, not learner overlap. Close both
        // microphone and playback immediately; the captured old registration
        // owns best-effort REST cleanup and must never update the new account.
        detail = nil
        summaryRequestID = UUID()
        summaryContextValidity = nil
        summaryRefreshState = .idle
        captions = []
        answerDraftState = VoiceTutorAnswerDraftState()
        answerQuestionSource = nil
        userInputState = VoiceTutorUserInputState()
        sessionState.endLocally()
        clearGradingResult()
        operationState.endLocally()
        userInputState.endLocally()
        learnerTranscriptState.endLocally()
        learnerCaptionIDsByItemID = [:]
        answerSourceItemIDs = []
        heldAnswerCaptionIDs = []
        knownAnswerRecordIDs = [:]
        submittedAnswerIDs = []
        assistantTranscriptState.discard()
        cancelTerminalPlayoutDrain()
        closeLocalMedia()
        stopAudioSendPump()
        stopLocalSpeechEventPump()
        if isFinalizing {
            await transport.disconnect(closeCode: .goingAway)
            return
        }
        await stop(shouldNotifyServerOverSocket: false, source: .identityInvalidated)
    }

    private func startReceivingEvents(attemptID: UUID) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            guard let self else {
                return
            }
            await self.receiveEvents(attemptID: attemptID)
        }
    }

    private func startLocalSpeechEventPump(
        attemptID: UUID,
        connection: VoiceTutorLiveConnection
    ) -> VoiceTutorCallControlEventStream {
        stopLocalSpeechEventPump()
        let events = VoiceTutorCallControlEventStream()
        localSpeechEvents = events
        localSpeechSendTask = Task { [weak self] in
            do {
                for try await control in events.stream {
                    guard let self, !Task.isCancelled,
                          self.connectionAttemptFence.isCurrent(attemptID), connection.isCurrent(),
                          self.phase.isLive, !self.isFinalizing else { return }
                    let needsBoundary: Bool
                    switch control {
                    case .speech(let event): needsBoundary = event.activity == .started
                    case .pause(let command): needsBoundary = command.kind == .pause
                    default: needsBoundary = false
                    }
                    let interruptionResponseID = needsBoundary
                        ? self.duplexPlaybackState.activeResponseID ?? self.pendingSpokenEndPlayoutTail?.responseID
                        : nil
                    if let responseID = interruptionResponseID {
                        // Provider completion can precede the final audible RTP
                        // samples; protect that same tail before interrupting it.
                        await self.webRTCTransport?.waitForInterruptionBoundary(responseID: responseID)
                        guard !Task.isCancelled, self.connectionAttemptFence.isCurrent(attemptID),
                              connection.isCurrent(), self.phase.isLive, !self.isFinalizing else { return }
                    }
                    if case .speech(let event) = control {
                        switch event.activity {
                        case .started:
                            self.handleLearnerSpeechStarted(sequence: event.sequence,
                                interruptionResponseID: interruptionResponseID)
                        case .stopped:
                            self.duplexPlaybackState.userSpeechStopped(sequence: event.sequence)
                        }
                    }
                    // One consumer sends numbered acoustic boundaries and pause
                    // fences in order. The backend commits input and releases
                    // replies; Realtime interprets meaning and tool intent.
                    try await self.transport.sendCallControl(control, attemptID: attemptID)
                    guard !Task.isCancelled, self.connectionAttemptFence.isCurrent(attemptID),
                          connection.isCurrent(), self.phase.isLive, !self.isFinalizing else { return }
                    switch control {
                    case .speech(let event):
                        self.logDiagnostic("event=local_speech_\(event.activity.rawValue) sequence=\(event.sequence)")
                    case .pause(let command):
                        self.logDiagnostic("event=pause_control_sent kind=\(command.kind.rawValue) sequence=\(command.sequence)")
                    case .answer(let command):
                        self.logDiagnostic("event=answer_control_sent kind=\(command.kind.rawValue)")
                    case .userInput(let command):
                        self.logDiagnostic("event=user_input_control_sent cancelled=\(command.answers == nil ? 1 : 0)")
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                guard let self, !Task.isCancelled,
                      self.connectionAttemptFence.isCurrent(attemptID), connection.isCurrent(),
                      self.phase.isLive, !self.isFinalizing else { return }
                self.logDiagnostic(
                    "event=local_speech_delivery_failed \(VoiceTutorDiagnosticError.fields(for: error))",
                    isWarning: true
                )
                // Preserve this task's settlement work when stop() cancels peers.
                self.localSpeechSendTask = nil
                self.localSpeechEvents?.finish()
                self.localSpeechEvents = nil
                self.errorMessage = self.pauseState.holdsMicrophone
                    ? self.appState.strings.voiceTutorPauseFailed
                    : self.appState.strings.voiceTutorConnectionFailed
                self.failureCause = self.pauseState.holdsMicrophone ? .localControl : .connection
                await self.stop(
                    shouldNotifyServerOverSocket: false,
                    outcome: .failed,
                    source: .localSpeechDeliveryFailure
                )
            }
        }
        return events
    }

    private func stopLocalSpeechEventPump() {
        finishWordTask?.cancel()
        finishWordTask = nil
        finishWordRequestID = nil
        answerEditorID = nil
        answerEditorPauseOwned = false
        answerEditorClosingID = nil
        pauseTimeoutTask?.cancel()
        pauseTimeoutTask = nil
        localSpeechEvents?.finish()
        localSpeechEvents = nil
        localSpeechSendTask?.cancel()
        localSpeechSendTask = nil
    }

    private func startAudioSendPump() -> AsyncStream<Data>.Continuation {
        stopAudioSendPump()
        let (stream, continuation) = AsyncStream.makeStream(
            of: Data.self,
            bufferingPolicy: .bufferingNewest(8)
        )
        audioSendContinuation = continuation
        let transport = transport
        audioSendTask = Task {
            for await audio in stream {
                guard !Task.isCancelled else {
                    return
                }
                do {
                    try await transport.sendAudio(audio)
                } catch {
                    return
                }
            }
        }
        return continuation
    }

    private func stopAudioSendPump() {
        audioSendContinuation?.finish()
        audioSendContinuation = nil
        audioSendTask?.cancel()
        audioSendTask = nil
    }

    private func finishAudioSendPump() async {
        audioSendContinuation?.finish()
        audioSendContinuation = nil
        guard let task = audioSendTask else {
            return
        }
        audioSendTask = nil
        await withTaskGroup(of: Bool.self) { group in
            group.addTask {
                await task.value
                return true
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 500_000_000)
                return false
            }
            _ = await group.next()
            task.cancel()
            group.cancelAll()
        }
    }

    private func startHeartbeat() {
        stopHeartbeat()
        let transport = transport
        heartbeatTask = Task {
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 15_000_000_000)
                    guard !Task.isCancelled else {
                        return
                    }
                    try await transport.sendHeartbeat()
                } catch {
                    return
                }
            }
        }
    }

    private func stopHeartbeat() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    private func makeServerEndStream() -> AsyncStream<VoiceTutorRealtimeEnded> {
        clearServerEndWaiter()
        let (stream, continuation) = AsyncStream.makeStream(
            of: VoiceTutorRealtimeEnded.self,
            bufferingPolicy: .bufferingNewest(1)
        )
        serverEndContinuation = continuation
        return stream
    }

    private func clearServerEndWaiter() {
        serverEndContinuation?.finish()
        serverEndContinuation = nil
    }

    private func waitForServerEnd(
        from stream: AsyncStream<VoiceTutorRealtimeEnded>
    ) async -> VoiceTutorRealtimeEnded? {
        await withTaskGroup(of: VoiceTutorRealtimeEnded?.self) { group in
            group.addTask {
                var iterator = stream.makeAsyncIterator()
                return await iterator.next()
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    private func receiveEvents(attemptID: UUID) async {
        do {
            while !Task.isCancelled {
                guard connectionAttemptFence.isCurrent(attemptID) else { return }
                let event = try await transport.receive()
                guard connectionAttemptFence.isCurrent(attemptID) else { return }
                guard activeConnection?.isCurrent() == true else {
                    await stopForInvalidatedContext()
                    return
                }
                await handle(event, attemptID: attemptID)
                guard connectionAttemptFence.isCurrent(attemptID),
                      phase.isLive || phase == .ending else { return }
            }
        } catch is CancellationError {
            return
        } catch {
            guard connectionAttemptFence.isCurrent(attemptID),
                  !isFinalizing, phase.isLive || phase == .ending else {
                return
            }
            if VoiceTutorServerEndReasonPolicy.isGracefulServerCompletion(serverEndReason) {
                logDiagnostic("event=planned_server_close")
                errorMessage = nil
                failureCause = nil
                if VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(serverEndReason),
                   usesWebRTC {
                    let hasSealedQuotaNotice = VoiceTutorServerEndPlayoutPolicy
                        .playoutDrainedResponseID(
                            reason: serverEndReason,
                            phase: phase,
                            usesWebRTC: usesWebRTC,
                            pending: pendingSpokenEndPlayoutTail,
                            quotaNoticeResponseID: quotaExhaustionNoticeResponseID
                        ) != nil
                    let holdSeconds = VoiceTutorQuotaControlLossPolicy.mediaHoldSeconds(
                        hardEndsAt: hardEndsAt,
                        now: Date(),
                        hasSealedResponse: hasSealedQuotaNotice
                    )
                    if holdSeconds > 0 {
                        logDiagnostic("event=quota_control_loss_media_hold_started")
                        do {
                            try await Task.sleep(
                                nanoseconds: UInt64((holdSeconds * 1_000_000_000).rounded(.up))
                            )
                        } catch {
                            return
                        }
                        guard connectionAttemptFence.isCurrent(attemptID),
                              activeConnection?.isCurrent() == true,
                              !isFinalizing,
                              phase == .ending else { return }
                        logDiagnostic("event=quota_control_loss_media_hold_finished")
                    }
                }
                await finishFromServer(
                    VoiceTutorRealtimeEnded(
                        reason: serverEndReason,
                        endedAt: nil,
                        durationSeconds: 0,
                        chargedSeconds: 0,
                        resultStatus: nil,
                        pollAfterMilliseconds: nil
                    ),
                    permitsServerVerifiedResponseFallback: false
                )
                return
            }
            let closeCode = await transport.diagnosticCloseCode()
            guard connectionAttemptFence.isCurrent(attemptID), !isFinalizing,
                  phase.isLive || phase == .ending else { return }
            logDiagnostic(
                "event=control_receive_failed \(VoiceTutorDiagnosticError.fields(for: error)) wsCloseCode=\(closeCode.map(String.init) ?? "unknown")",
                isWarning: true
            )
            closeLocalMedia()
            let failure = VoiceTutorStartupFailurePolicy.presentation(for: error, strings: appState.strings)
            errorMessage = pauseState.holdsMicrophone ? appState.strings.voiceTutorPauseFailed : failure.message
            failureCause = pauseState.holdsMicrophone ? .localControl : failure.cause
            await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .controlReceiveFailure)
        }
    }

    private func handle(_ event: VoiceTutorRealtimeEvent, attemptID: UUID) async {
        guard connectionAttemptFence.isCurrent(attemptID) else { return }
        guard let connection = activeConnection, connection.isCurrent() else {
            await stopForInvalidatedContext()
            return
        }
        if isFinalizing {
            switch event {
            case .sessionEnded, .quotaUpdated, .questionChanged:
                break
            default:
                return
            }
        }
        switch event {
        case .userInputRequest(let request):
            guard usesWebRTC, phase.isLive, !isFinalizing, let sessionID,
                  userInputState.apply(request, sessionID: sessionID,
                    operation: operationState.visibleEntries(at: 0).first { $0.id == request.operationId },
                    afterCaptionID: presentationCaptions.last?.id,
                    responseID: assistantTranscriptDraft.isEmpty ? nil : assistantTranscriptResponseID) else { break }
            // Transfer the cancel-to-next-step hold to the accepted request;
            // cancelled exercise audio must never become a new learner turn.
            answerDraftState.didReceiveCancellationChoices()
            guard webRTCTransport?.setMuted(true) == true else { await failAnswerControl(); break }
        case .userInputState(let event):
            guard usesWebRTC, phase.isLive, !isFinalizing, userInputState.apply(event) else { break }
            let shouldMute = isMuted || pauseState.holdsMicrophone || answerDraftState.holdsMicrophone || userInputState.holdsMicrophone
            guard webRTCTransport?.setMuted(shouldMute) == true else { await failAnswerControl(); break }
        case .operationContext(let context):
            guard usesWebRTC, phase.isLive, !isFinalizing else { break }
            _ = operationState.applyContext(context)
        case .operation(let event):
            guard usesWebRTC, phase.isLive, !isFinalizing else { break }
            guard operationState.apply(event, at: ProcessInfo.processInfo.systemUptime,
                afterCaptionID: presentationCaptions.last?.id,
                responseID: assistantTranscriptDraft.isEmpty ? nil : assistantTranscriptResponseID) else { break }
            if let operation = operationState.visibleEntries(at: 0).first(where: { $0.id == event.operationID }) {
                userInputState.bindOperation(operation)
            }
        case .sessionState(let event):
            guard usesWebRTC, phase.isLive, !isFinalizing else { break }
            // Only the current authenticated control receive loop reaches this
            // path. Display snapshots never act as microphone or submit commands.
            let previousPhase = sessionState.snapshot?.phase
            if sessionState.apply(event, minimumRevision: studyFocus.revision) {
                reconcileGradingResult()
                if event.phase == .graded, let request = pendingGradingRefresh,
                   isCurrentGradingResultRequest(request) {
                    startGradingResultRequest(request)
                } else if event.phase == .gradingUnavailable, let request = pendingGradingRefresh,
                          isCurrentGradingResultRequest(request) {
                    _ = retainedGradingResultState.fail(for: request)
                    pendingGradingRefresh = nil
                    gradingResultTask?.cancel()
                    gradingResultTimeoutTask?.cancel()
                } else if previousPhase == .gradingUnavailable, event.phase == .graded,
                          let request = retainedGradingResultState.retry() {
                    startGradingResultRequest(request)
                }
            }
        case .sessionReady(let hardEndsAt, let remainingSeconds, let pauseProtocol, let turnProtocol):
            if usesWebRTC, !VoiceTutorTurnProtocol.acceptsReady(turnProtocol) {
                // Keep capture closed unless the server has accepted this
                // attempt's exact Realtime conversation contract.
                webRTCTransport?.closeMicrophoneInput()
                errorMessage = appState.strings.voiceTutorConnectionFailed
                failureCause = .connection
                await stop(shouldNotifyServerOverSocket: true, outcome: .failed, source: .localSpeechDeliveryFailure)
                return
            }
            recorder?.markSessionReady()
            if usesWebRTC {
                webRTCTransport?.setSessionMediaReady()
            }
            self.hardEndsAt = hardEndsAt ?? self.hardEndsAt
            if let remainingSeconds {
                sessionQuota.updateRemainingSeconds(remainingSeconds)
            }
            updateSessionCountdown()
            pauseState.isSupported = usesWebRTC && pauseProtocol == VoiceTutorCallPauseState.supportedProtocol
            duplexPlaybackState.awaitInitialResponse()
            phase = .listening
        case .pauseAcknowledged(let sequence, let paused):
            guard usesWebRTC, phase.isLive, !isFinalizing else { break }
            var acknowledged = pauseState
            guard acknowledged.acknowledge(sequence: sequence, paused: paused) else { break }
            if !paused, let answerID = answerEditorClosingID, answerEditorPauseOwned {
                answerDraftState.endEditing(answerID: answerID)
                answerEditorClosingID = nil
                answerEditorPauseOwned = false
            }
            if !paused, webRTCTransport?.setMuted(isMuted || answerDraftState.holdsMicrophone || userInputState.holdsMicrophone) != true {
                await failPause()
                break
            }
            pauseTimeoutTask?.cancel()
            pauseTimeoutTask = nil
            pauseState = acknowledged
            if paused, let answerID = answerEditorClosingID, !answerEditorPauseOwned {
                answerDraftState.endEditing(answerID: answerID)
                answerEditorClosingID = nil
            }
            if paused { resumeAfterAnswerEditingIfReady() }
            inputNeedsRepeat = false
            logDiagnostic("event=pause_acknowledged paused=\(paused ? 1 : 0) sequence=\(sequence)")
        case .quotaUpdated(let quota):
            let existingQuota = appState.voiceTutorStatus?.quota
            applyServerQuota(
                BackendVoiceTutorQuota(
                    periodStartedAt: existingQuota?.periodStartedAt,
                    resetAt: existingQuota?.resetAt,
                    limitSeconds: quota.limitSeconds,
                    usedSeconds: quota.usedSeconds,
                    reservedSeconds: quota.reservedSeconds,
                    remainingSeconds: quota.remainingSeconds
                )
            )
        case .sessionEnding(let reason, let hardEndsAt):
            clearGradingResult()
            serverEndReason = reason ?? serverEndReason
            if VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(serverEndReason) {
                errorMessage = nil
                failureCause = nil
                inputNeedsRepeat = false
                cancelTerminalPlayoutDrain()
                if pendingSpokenEndPlayoutTail?.responseID != quotaExhaustionNoticeResponseID {
                    // A completed ordinary tutor turn is not the terminal quota
                    // sentence and must never authorize immediate teardown.
                    pendingSpokenEndPlayoutTail = nil
                }
            }
            self.hardEndsAt = hardEndsAt ?? self.hardEndsAt
            if usesWebRTC {
                webRTCTransport?.closeMicrophoneInput()
            } else {
                audioEngine.setMuted(true)
            }
            phase = .ending
        case .sessionEnded(let ended):
            serverEndReason = ended.reason ?? serverEndReason
            if isFinalizing {
                serverEndContinuation?.yield(ended)
                serverEndContinuation?.finish()
                serverEndContinuation = nil
            } else {
                await finishFromServer(ended)
            }
        case .resultReady:
            let completedSessionID = connection.session.sessionId
            await VoiceTutorAttemptDelivery.deliver(
                isCurrent: { self.connectionAttemptFence.isCurrent(attemptID) && connection.isCurrent() },
                operation: {
                    let loader = self.appState.makeVoiceTutorSessionDetailLoader(
                        sessionID: completedSessionID,
                        validity: { [weak self] in
                            self?.connectionAttemptFence.isCurrent(attemptID) == true && connection.isCurrent()
                        }
                    )
                    return await loader?.load()
                },
                apply: { loaded in
                    guard let loaded else { return }
                    self.detail = loaded
                    if VoiceTutorSummaryState(detail: loaded).isTerminal {
                        self.summaryRequestID = UUID()
                        self.summaryRefreshState = .idle
                    }
                }
            )
        case .heartbeatAcknowledged:
            break
        case .inputRetry:
            // This is not a disconnected call. Keep native capture/output alive
            // and show a small retry hint after the tutor finishes speaking.
            guard VoiceTutorServerEndReasonPolicy.permitsInputRetry(
                reason: serverEndReason,
                isFinalizing: isFinalizing
            ) else { break }
            // A rejected opening can ask for another utterance before any
            // response.created. It no longer owes a greeting, but a stale retry
            // must never consume a newer learner turn's independent wait.
            duplexPlaybackState.stopWaitingForInitialResponse()
            inputNeedsRepeat = true
        case .inputRetryScoped(let sequence, let responseID):
            guard usesWebRTC, phase.isLive, !isFinalizing,
                  VoiceTutorServerEndReasonPolicy.permitsInputRetry(
                    reason: serverEndReason, isFinalizing: isFinalizing
                  ) else { break }
            let accepted = duplexPlaybackState.acceptInputRetry(sequence: sequence, responseID: responseID)
            logDiagnostic("event=input_retry sequence=\(sequence) responseId=\(diagnosticResponseID(responseID)) accepted=\(accepted ? 1 : 0)")
            guard accepted else { break }
            if let responseID, duplexPlaybackState.matchesActiveResponse(responseID: responseID) {
                abandonProviderTurn(responseID: responseID)
            }
        case .inputSettled(let sequence):
            guard usesWebRTC, phase.isLive, !isFinalizing else { break }
            let accepted = duplexPlaybackState.inputSettled(sequence: sequence)
            logDiagnostic("event=input_settled sequence=\(sequence) accepted=\(accepted ? 1 : 0)")
        case .providerTurnAbandoned(let responseID):
            abandonProviderTurn(responseID: responseID)
        case .finishWordRequested(let request):
            finishWordTask?.cancel()
            finishWordRequestID = request.requestID
            finishWordTask = Task { [weak self] in
                guard let self else { return }
                let isCurrent = { [weak self] in
                    guard let self else { return false }
                    return !Task.isCancelled && self.connectionAttemptFence.isCurrent(attemptID)
                        && connection.isCurrent() && self.phase.isLive && !self.isFinalizing
                        && self.finishWordRequestID == request.requestID
                }
                guard isCurrent() else { return }
                if self.duplexPlaybackState.matchesActiveResponse(responseID: request.responseID)
                    || self.pendingSpokenEndPlayoutTail?.responseID == request.responseID {
                    await self.webRTCTransport?.waitForInterruptionBoundary(responseID: request.responseID)
                }
                guard isCurrent() else { return }
                // Both interruption methods are response scoped. A newer turn
                // that arrived while waiting must keep playing untouched.
                if self.duplexPlaybackState.matchesActiveResponse(responseID: request.responseID)
                    || self.pendingSpokenEndPlayoutTail?.responseID == request.responseID {
                    self.interruptTutorResponse(responseID: request.responseID)
                }
                do { try await self.transport.sendWordFinished(request, attemptID: attemptID) }
                catch { self.logDiagnostic("event=word_finished_delivery_failed", isWarning: true) }
                if isCurrent() { self.finishWordTask = nil; self.finishWordRequestID = nil }
            }
        case .responseInterrupted(let responseID):
            interruptTutorResponse(responseID: responseID)
        case .responseRecovering(let responseID, let sequence):
            guard usesWebRTC, phase.isLive, !isFinalizing,
                  VoiceTutorServerEndReasonPolicy.permitsInputRetry(reason: serverEndReason, isFinalizing: isFinalizing) else { break }
            let accepted = duplexPlaybackState.recoverResponse(responseID: responseID, sequence: sequence)
            logDiagnostic("event=response_recovering sequence=\(sequence) responseId=\(diagnosticResponseID(responseID)) accepted=\(accepted ? 1 : 0)")
            guard accepted else { break }
            preserveInterruptedAssistantTranscript(responseID: responseID)
            assistantTranscriptState.discard()
            cancelTerminalPlayoutDrain()
            pendingSpokenEndPlayoutTail = nil
            _ = webRTCResponseState.abandonResponse(responseID)
            _ = webRTCTransport?.interruptLocalPlayoutResponse(responseID: responseID)
            phase = .listening
        case .studyFocused(let focus):
            guard phase.isLive, !isFinalizing else { break }
            guard studyFocus.apply(focus, attemptID: attemptID) else { break }
            reconcileGradingResult()
            if answerDraftState.isActive,
               focus?.studyID != answerDraftState.studyID || focus?.revision != answerDraftState.revision {
                preserveUnsubmittedAnswerDraft(VoiceTutorUnsubmittedAnswerSnapshot(answerDraftState))
                if answerDraftState.hasUserEdited { persistVoiceAnswerDraft(force: true) }
                answerDraftState.endLocally()
            }
        case .studyTreeChanged(let studyID), .studyTreeUpdated(let studyID):
            // A confirmed server-side MCP write refreshes only that node's
            // metadata. Keep the socket receive/audio path non-blocking and
            // never replace a learner's current question or answer draft.
            guard phase.isLive else { break }
            Task { [weak self] in
                guard let self else { return }
                await self.appState.refreshVoiceTutorCreatedStudy(
                    studyID: studyID,
                    validity: { [weak self] in
                        self?.connectionAttemptFence.isCurrent(attemptID) == true &&
                            connection.isCurrent() && self?.phase.isLive == true && self?.isFinalizing == false
                    }
                )
                // The server emits a new focus epoch for a focused-node edit.
                // This potentially delayed GET must not overwrite that snapshot.
            }
        case .answerQuestionSource(let source):
            guard usesWebRTC, phase.isLive, !isFinalizing,
                  source.revision >= max(studyFocus.revision, sessionState.snapshot?.revision ?? 0),
                  studyFocus.focus.map({ $0.studyID == source.studyID }) ?? true,
                  !answerDraftState.isActive || source.belongs(to: answerDraftState) else { break }
            if let snapshot = sessionState.snapshot, snapshot.revision == source.revision {
                guard snapshot.studyID.map({ $0 == source.studyID }) ?? true,
                      snapshot.recordID.map({ $0 == source.recordID }) ?? true,
                      snapshot.answerID.map({ $0 == source.answerID }) ?? true else { break }
            }
            // The source arrives before ready. Retain it without enabling
            // answer controls; the later exact ready receipt remains required.
            guard answerQuestionSource?.answerID != source.answerID || answerQuestionSource == source else { break }
            answerQuestionSource = source
        case .answerState(let event):
            guard usesWebRTC, phase.isLive, !isFinalizing,
                  connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else { break }
            if answerDraftState.isCancelling, event.code == "ANSWER_CANCEL_UNAVAILABLE",
               event.answerID == answerDraftState.answerID, event.recordID == answerDraftState.recordID,
               event.studyID == answerDraftState.studyID, event.revision == answerDraftState.revision {
                await failAnswerControl()
                break
            }
            let isNewAnswer = answerDraftState.answerID != event.answerID
            let cancelledDraft = event.phase == .cancelled
                ? VoiceTutorUnsubmittedAnswerSnapshot(answerDraftState) : nil
            if isNewAnswer {
                guard event.phase == .listening, event.revision >= studyFocus.revision,
                      studyFocus.focus.map({ $0.studyID == event.studyID }) ?? true else { break }
            }
            let change = VoiceTutorQuestionChange(studyID: event.studyID, recordID: event.recordID)!
            let existing = isNewAnswer
                ? appState.voiceTutorAnswerDraft(for: change, validity: { connection.isCurrent() }) ?? ""
                : ""
            guard answerDraftState.apply(event, existingDraft: existing,
                minimumRevision: max(studyFocus.revision, sessionState.snapshot?.revision ?? 0),
                currentLesson: sessionState.snapshot) else { break }
            knownAnswerRecordIDs[event.answerID] = event.recordID
            inputNeedsRepeat = false
            if [.review, .failed].contains(answerDraftState.phase) {
                persistVoiceAnswerDraft(force: answerDraftState.hasUserEdited)
            }
            if event.phase == .submitted, submittedAnswerIDs.insert(event.answerID).inserted {
                persistVoiceAnswerDraft(force: true)
                appendCaption(speaker: .learner, text: answerDraftState.text,
                              providerItemIDs: answerDraftState.sourceItemIDs, answerID: answerDraftState.answerID)
            } else if event.phase == .cancelled {
                preserveUnsubmittedAnswerDraft(cancelledDraft)
                if answerDraftState.hasUserEdited { persistVoiceAnswerDraft(force: true) }
            }
            let shouldMute = isMuted || pauseState.holdsMicrophone || answerDraftState.holdsMicrophone || userInputState.holdsMicrophone
            guard webRTCTransport?.setMuted(shouldMute) == true else {
                await failAnswerControl()
                break
            }
        case .answerTranscript(let event):
            guard phase.isLive, !isFinalizing, connectionAttemptFence.isCurrent(attemptID),
                  connection.isCurrent(), knownAnswerRecordIDs[event.answerID] == event.recordID else { break }
            // Late final ASR still identifies private raw history. After review
            // it cannot append to edited/submitted text or become another bubble.
            answerSourceItemIDs.insert(event.itemID)
            _ = answerDraftState.append(event)
        case .questionChanged(let change):
            guard connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else { break }
            // Keep the latest event order for the final refresh: an older
            // question must not become the study's new pending question.
            changedQuestions.removeAll { $0.recordID == change.recordID }
            changedQuestions.append(change)
            Task { [weak self] in
                guard let self else { return }
                await self.appState.refreshVoiceTutorQuestion(change, validity: { [weak self] in
                    self?.connectionAttemptFence.isCurrent(attemptID) == true && connection.isCurrent()
                })
            }
        case .studyTreeDeleted(let studyIDs):
            guard phase.isLive, connectionAttemptFence.isCurrent(attemptID),
                  connection.isCurrent(), !isFinalizing else { break }
            // Apply the tombstone immediately, before an older metadata fetch can
            // return. This is not a hang-up or a request to discard an answer draft.
            appState.applyVoiceTutorDeletedStudies(studyIDs: studyIDs)
            studyFocus.remove(studyIDs: studyIDs, attemptID: attemptID)
            if retainedGradingResultState.target.map({ studyIDs.contains($0.studyID) }) == true {
                clearGradingResult()
            }
        case .serviceError(let code, _, let retryable):
            let failure = VoiceTutorStartupFailurePolicy.servicePresentation(
                code: code, retryable: retryable, strings: appState.strings)
            errorMessage = failure.message
            failureCause = failure.cause
            if pauseState.holdsMicrophone && failure.cause == .unknown {
                errorMessage = appState.strings.voiceTutorPauseFailed
                failureCause = .localControl
            }
            await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .providerError)
        case .audioDelta(let delta):
            guard !usesWebRTC else { break }
            guard duplexPlaybackState.assistantAudioBegan(responseID: delta.responseID) else {
                break
            }
            if let frame = VoiceTutorPCMFrame(
                pcm16Mono: delta.audio,
                sampleRate: VoiceTutorAudioEngine.sampleRate,
                capturedAtUptime: ProcessInfo.processInfo.systemUptime
            ) {
                recorder?.append(frame, participant: .tutor)
            }
            do {
                try audioEngine.playPCM24(delta)
            } catch {
                errorMessage = appState.strings.voiceTutorAudioInterrupted
                failureCause = .audio
                await stop(shouldNotifyServerOverSocket: false, outcome: .failed, source: .pcmPlaybackFailure)
                break
            }
            phase = .speaking
        case .assistantTranscriptDelta(let responseID, let delta, let itemID, let outputIndex, let eventID):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID),
                  assistantTranscriptState.matchesResponse(responseID) else {
                break
            }
            assistantTranscriptState.append(delta: delta, itemID: itemID, outputIndex: outputIndex, eventID: eventID)
        case .assistantTranscriptDone(let responseID, let transcript, let itemID, let outputIndex):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID),
                  assistantTranscriptState.matchesResponse(responseID) else {
                break
            }
            // Keep this full transcript provisional. Only a completed
            // response.done is allowed to publish it as a tutor chat message.
            assistantTranscriptState.stageCompletedTranscript(transcript, itemID: itemID, outputIndex: outputIndex)
            assistantTranscriptItemID = itemID
        case .userTranscript(let transcript, let itemID):
            guard learnerTranscriptState.accept(
                transcript: transcript, itemID: itemID,
                attemptID: attemptID, requiresItemID: usesWebRTC
            ) else { break }
            duplexPlaybackState.learnerTranscriptReceived(usesWebRTC: usesWebRTC)
            if let captionID = appendCaption(speaker: .learner, text: transcript, providerItemID: itemID) {
                if answerDraftState.isActive { heldAnswerCaptionIDs.insert(captionID) }
                if let itemID { learnerCaptionIDsByItemID[itemID] = captionID }
                let visibleIDs = Set(captions.map(\.id))
                learnerCaptionIDsByItemID = learnerCaptionIDsByItemID.filter { visibleIDs.contains($0.value) }
                heldAnswerCaptionIDs.formIntersection(visibleIDs)
            }
        case .userSpeechStarted:
            handleLearnerSpeechStarted()
        case .userSpeechStopped:
            duplexPlaybackState.userSpeechStopped()
        case .responseStarted(
            let responseID,
            let isTutorIntervention,
            let isQuotaExhaustionNotice
        ):
            guard let responseID, !responseID.isEmpty,
                  !duplexPlaybackState.wasInterrupted(responseID) else { break }
            if usesWebRTC, phase.isLive, duplexPlaybackState.isUserSpeaking,
               !isQuotaExhaustionNotice {
                interruptTutorResponse(responseID: responseID)
                break
            }
            if let acceptedResponseID = VoiceTutorServerEndPlayoutPolicy
                .acceptedQuotaNoticeResponseID(
                    reason: serverEndReason,
                    phase: phase,
                    responseID: responseID,
                    isQuotaExhaustionNotice: isQuotaExhaustionNotice
                ) {
                quotaExhaustionNoticeResponseID = acceptedResponseID
            }
            if !duplexPlaybackState.matchesActiveResponse(responseID: responseID) {
                assistantTranscriptItemID = nil
                if duplexPlaybackState.assistantResponseActive {
                    // A provider retry replaces, rather than extends, the
                    // failed partial sentence. Never merge its draft into the
                    // replacement response or persist it as completed teaching.
                    assistantTranscriptState.discard()
                }
                assistantTranscriptState.beginResponse(responseID)
                cancelTerminalPlayoutDrain()
                pendingSpokenEndPlayoutTail = nil
                duplexPlaybackState.responseStarted(
                    responseID: responseID,
                    isTutorIntervention: isTutorIntervention
                )
                if usesWebRTC {
                    webRTCResponseState.responseStarted(responseID)
                    webRTCTransport?.beginLocalPlayoutResponse(responseID: responseID)
                }
                logDiagnostic("event=response_started responseId=\(diagnosticResponseID(responseID))")
            }
        case .responseFinished(let responseID):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            if usesWebRTC {
                logDiagnostic("event=response_done responseId=\(diagnosticResponseID(responseID))")
                finishWebRTCResponseIfReady(webRTCResponseState.markResponseDone(responseID))
            } else if let responseID {
                commitAssistantTranscript(responseID: responseID)
                audioEngine.finishResponseAudio(responseID: responseID)
            }
        case .outputAudioBufferStarted(let responseID):
            guard usesWebRTC,
                  duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            // Provider generation is not proof that the iPhone rendered audio.
            // The first nonzero local render changes the speaking indication.
            webRTCResponseState.markOutputBufferStarted(responseID)
            logDiagnostic("event=provider_output_started responseId=\(diagnosticResponseID(responseID))")
        case .outputAudioBufferStopped(let responseID):
            guard usesWebRTC else { break }
            logDiagnostic("event=provider_output_stopped responseId=\(diagnosticResponseID(responseID))")
            finishWebRTCResponseIfReady(webRTCResponseState.markOutputBufferStopped(responseID))
        case .outputAudioBufferCleared(let responseID):
            guard usesWebRTC else { break }
            // The server normally consumes this response-local anomaly and
            // retries it. If an older server or boundary race forwards it,
            // abandon only that exact turn; the call and RTP track stay alive.
            abandonProviderTurn(responseID: responseID)
        case .ignored:
            break
        }
    }

    private func handleTutorPlaybackCompleted(responseID: String, attemptID: UUID) async {
        guard connectionAttemptFence.isCurrent(attemptID), phase.isLive,
              let connection = activeConnection, connection.isCurrent(),
              duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
            return
        }
        try? await transport.sendPlaybackCompleted(responseID: responseID)
        guard !Task.isCancelled, connectionAttemptFence.isCurrent(attemptID),
              phase.isLive, connection.isCurrent(),
              duplexPlaybackState.responseFinished(responseID: responseID) else {
            return
        }
        phase = .listening
    }

    private func handleWebRTCRenderedPCM(at uptime: TimeInterval) {
        guard usesWebRTC, phase.isLive else { return }
        if webRTCResponseState.mayIndicateSpeaking,
           duplexPlaybackState.isAwaitingActiveResponseAudio {
            // A response-created event is only generation intent. Keep the
            // waiting indication until the native renderer receives audio.
            _ = duplexPlaybackState.assistantAudioBegan(responseID: webRTCResponseState.responseID)
        }
        phase = phase.afterRenderedTutorAudio(
            assistantResponseActive: webRTCResponseState.mayIndicateSpeaking
        )
    }

    private func finishWebRTCResponseIfReady(_ responseID: String?) {
        guard let responseID, phase.maySealServerResponse(isFinalizing: isFinalizing),
              duplexPlaybackState.responseFinished(responseID: responseID) else { return }
        // A completed response.done is not enough: the provider can still clear
        // its output buffer. Publish tutor chat only after both exact WebRTC
        // completion boundaries have arrived in either order.
        commitAssistantTranscript(responseID: responseID)
        let sealedToken = webRTCTransport?.sealLocalPlayoutResponse(
            responseID: responseID
        )
        if VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(serverEndReason) {
            pendingSpokenEndPlayoutTail = responseID == quotaExhaustionNoticeResponseID
                ? sealedToken
                : nil
        } else {
            pendingSpokenEndPlayoutTail = sealedToken
        }
        // This ends only the UI's server-streaming state. Never stop/mute/clear
        // the remote track: its remaining RTP samples play before the next
        // response on the same continuous stream, even after these controls.
        logDiagnostic("event=provider_response_stream_finished responseId=\(diagnosticResponseID(responseID))")
        scheduleTerminalPlayoutDrainIfReady()
        if phase.isLive { phase = .listening }
    }

    private func handleLearnerSpeechStarted(sequence: Int? = nil, interruptionResponseID: String? = nil) {
        guard phase.isLive, !isFinalizing else { return }
        if let sequence, sequence <= 0 { return }
        inputNeedsRepeat = false
        if usesWebRTC, let responseID = interruptionResponseID,
           responseID == (duplexPlaybackState.activeResponseID ?? pendingSpokenEndPlayoutTail?.responseID) {
            interruptTutorResponse(responseID: responseID)
        }
        // The boundary wait belongs to the captured response, not a replacement
        // that arrived during its suspension. Register the learner edge without
        // implicitly interrupting whichever response happens to be current now.
        duplexPlaybackState.userSpeechStarted(sequence: sequence, interruptsTutor: false)
        if !duplexPlaybackState.assistantResponseActive { phase = .listening }
    }

    private func interruptTutorResponse(responseID: String) {
        guard usesWebRTC, phase.isLive, !isFinalizing else { return }
        let affectsCurrentResponse = duplexPlaybackState.activeResponseID == responseID
            || (duplexPlaybackState.activeResponseID == nil && !duplexPlaybackState.wasInterrupted(responseID))
        if affectsCurrentResponse {
            preserveInterruptedAssistantTranscript(responseID: responseID)
        }
        duplexPlaybackState.interruptResponse(responseID: responseID)
        // A delayed interruption for an old answer must not silence its replacement.
        guard affectsCurrentResponse,
              webRTCTransport?.interruptLocalPlayoutResponse(responseID: responseID) == true else { return }
        assistantTranscriptState.discard()
        cancelTerminalPlayoutDrain()
        pendingSpokenEndPlayoutTail = nil
        _ = webRTCResponseState.abandonResponse(responseID)
        inputNeedsRepeat = false
        phase = .listening
        logDiagnostic("event=tutor_response_interrupted responseId=\(diagnosticResponseID(responseID))")
    }

    private func abandonProviderTurn(responseID: String?) {
        if VoiceTutorServerEndPlayoutPolicy.isExactAbandonedQuotaNotice(
            reason: serverEndReason,
            phase: phase,
            responseID: responseID,
            quotaNoticeResponseID: quotaExhaustionNoticeResponseID
        ), let responseID {
            assistantTranscriptState.discard()
            cancelTerminalPlayoutDrain()
            pendingSpokenEndPlayoutTail = nil
            quotaExhaustionNoticeResponseID = nil
            _ = duplexPlaybackState.abandonResponse(responseID: responseID)
            if usesWebRTC {
                _ = webRTCResponseState.abandonResponse(responseID)
                _ = webRTCTransport?.abandonLocalPlayoutResponse(responseID: responseID)
            }
            logDiagnostic("event=quota_notice_abandoned", isWarning: true)
            return
        }
        guard phase.isLive,
              let responseID,
              duplexPlaybackState.abandonResponse(responseID: responseID) else { return }
        assistantTranscriptState.discard()
        cancelTerminalPlayoutDrain()
        pendingSpokenEndPlayoutTail = nil
        if quotaExhaustionNoticeResponseID == responseID {
            quotaExhaustionNoticeResponseID = nil
        }
        if usesWebRTC {
            _ = webRTCResponseState.abandonResponse(responseID)
            _ = webRTCTransport?.abandonLocalPlayoutResponse(responseID: responseID)
        }
        inputNeedsRepeat = true
        errorMessage = nil
        failureCause = nil
        phase = .listening
        logDiagnostic("event=provider_turn_abandoned responseId=\(diagnosticResponseID(responseID))")
    }

    private func scheduleTerminalPlayoutDrainIfReady() {
        guard terminalPlayoutDrainTask == nil,
              let responseID = VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
                reason: serverEndReason,
                phase: phase,
                usesWebRTC: usesWebRTC,
                pending: pendingSpokenEndPlayoutTail,
                quotaNoticeResponseID: quotaExhaustionNoticeResponseID
              ),
              let token = pendingSpokenEndPlayoutTail,
              token.responseID == responseID,
              let webRTCTransport,
              activeConnection?.isCurrent() == true else { return }

        let attemptID = connectionAttemptFence.currentID
        logDiagnostic("event=device_playout_drain_started")
        terminalPlayoutDrainTask = Task { [weak self] in
            await webRTCTransport.waitForLocalPlayoutTail(token)
            guard let self else { return }
            defer {
                if self.connectionAttemptFence.isCurrent(attemptID) {
                    self.terminalPlayoutDrainTask = nil
                }
            }
            guard !Task.isCancelled,
                  self.connectionAttemptFence.isCurrent(attemptID),
                  self.activeConnection?.isCurrent() == true,
                  !self.isFinalizing,
                  VoiceTutorServerEndPlayoutPolicy.playoutDrainedResponseID(
                    reason: self.serverEndReason,
                    phase: self.phase,
                    usesWebRTC: self.usesWebRTC,
                    pending: self.pendingSpokenEndPlayoutTail,
                    quotaNoticeResponseID: self.quotaExhaustionNoticeResponseID
                  ) == responseID else { return }
            do {
                try await self.transport.sendPlayoutDrained(responseID: responseID)
                self.logDiagnostic("event=device_playout_drained")
            } catch {
                // The backend owns the provider-stop fallback and terminal
                // deadline. Losing this ACK must not cut off tutor playback.
                self.logDiagnostic(
                    "event=device_playout_drain_send_failed \(VoiceTutorDiagnosticError.fields(for: error))",
                    isWarning: true
                )
            }
        }
    }

    private func cancelTerminalPlayoutDrain() {
        terminalPlayoutDrainTask?.cancel()
        terminalPlayoutDrainTask = nil
    }

    private func finishFromServer(
        _ ended: VoiceTutorRealtimeEnded,
        permitsServerVerifiedResponseFallback: Bool = true
    ) async {
        guard !isFinalizing else {
            return
        }
        serverEndReason = ended.reason ?? serverEndReason
        let effectiveServerEndReason = serverEndReason
        let outcome = VoiceTutorSessionPhase.completed(
            outcome: .ended,
            serverState: nil,
            serverReason: effectiveServerEndReason
        )
        if outcome == .failed,
           failureCause == nil || effectiveServerEndReason?.uppercased() == "AUTH_REVOKED" {
            let cause = VoiceTutorServerEndReasonPolicy.failureCause(
                effectiveServerEndReason, isPaused: pauseState.holdsMicrophone)
            failureCause = cause
            errorMessage = appState.strings.voiceTutorFailureMessage(cause)
        }
        logDiagnostic("event=server_ended")
        sessionState.endLocally()
        clearGradingResult()
        operationState.endLocally()
        userInputState.endLocally()
        learnerTranscriptState.endLocally()
        preserveUnsubmittedAnswerDraft(VoiceTutorUnsubmittedAnswerSnapshot(answerDraftState))
        if answerDraftState.hasUserEdited { persistVoiceAnswerDraft(force: true) }
        answerDraftState.endLocally()
        isFinalizing = true
        phase = .ending
        cancelTerminalPlayoutDrain()
        stopLocalSpeechEventPump()
        stopHeartbeat()
        clearSessionCountdown()
        if usesWebRTC {
            // Server-verified spoken end reaches this path without a local stop
            // request. Keep the exact completed response's native output path
            // alive briefly so NetEq/Core Audio can render its tail. The red
            // button goes through stop() and intentionally never awaits this.
            webRTCTransport?.closeMicrophoneInput()
            var token = VoiceTutorServerEndPlayoutPolicy.spokenEndToken(
                reason: effectiveServerEndReason,
                usesWebRTC: usesWebRTC,
                pending: pendingSpokenEndPlayoutTail,
                quotaNoticeResponseID: quotaExhaustionNoticeResponseID
            )
            let serverVerifiedFallbackResponseID = token == nil && permitsServerVerifiedResponseFallback
                ? VoiceTutorServerEndPlayoutPolicy.serverVerifiedFallbackResponseID(
                   reason: effectiveServerEndReason,
                   usesWebRTC: usesWebRTC,
                   pending: pendingSpokenEndPlayoutTail,
                   activeResponseID: webRTCResponseState.responseID,
                   quotaNoticeResponseID: quotaExhaustionNoticeResponseID
                )
                : nil
            if let responseID = serverVerifiedFallbackResponseID {
                // A spoken terminal lifecycle is emitted only after the backend
                // observed both completion boundaries for this exact response.
                // It can beat the final raw response.done over the control
                // socket, so seal its provisional transcript before teardown.
                if duplexPlaybackState.matchesActiveResponse(responseID: responseID) {
                    commitAssistantTranscript(responseID: responseID)
                }
                token = webRTCTransport?.sealLocalPlayoutResponse(responseID: responseID)
            }
            pendingSpokenEndPlayoutTail = token
            if let token, let webRTCTransport {
                logDiagnostic("event=spoken_end_local_playout_tail_started")
                await webRTCTransport.waitForLocalPlayoutTail(token)
                logDiagnostic("event=spoken_end_local_playout_tail_finished")
            }
        }
        if outcome == .ended, activeConnection?.isCurrent() == true {
            preserveInterruptedAssistantTranscript(responseID: duplexPlaybackState.activeResponseID)
        }
        closeLocalMedia()
        stopAudioSendPump()
        // This method runs inside receiveTask. Cancelling it here would also cancel
        // the result polling and leave the completed learning summary unloaded.
        receiveTask = nil
        await transport.disconnect()
        await finishRecordingIfNeeded()
        await finishSession(
            initialDetail: nil,
            pollAfterMilliseconds: ended.pollAfterMilliseconds,
            outcome: outcome
        )
    }

    private func finishRecordingIfNeeded() async {
        await uploadFinalizedRecording(await finalizeRecordingIfNeeded())
    }

    private func finalizeRecordingIfNeeded() async -> VoiceTutorPendingRecording? {
        guard let recorder else {
            isRecording = false
            return nil
        }
        self.recorder = nil
        isRecording = false
        do {
            return try await recorder.finish()
        } catch {
            errorMessage = appState.strings.voiceTutorRecordingUnavailable
            return nil
        }
    }

    private func uploadFinalizedRecording(_ pending: VoiceTutorPendingRecording?) async {
        guard let pending else { return }
        do {
            try await appState.uploadVoiceTutorRecording(pending)
        } catch {
            // Upload failures retain a protected manifest for foreground retry.
            // Unlike a local finalize failure, no recording data was lost.
        }
    }

    private func finishSession(
        initialDetail: BackendVoiceTutorSessionDetail?,
        pollAfterMilliseconds: Int?,
        outcome: VoiceTutorSessionPhase = .ended
    ) async {
        let attemptID = connectionAttemptFence.currentID
        if let connection = activeConnection, connection.isCurrent() {
            summaryContextValidity = { [weak self] in
                self?.connectionAttemptFence.isCurrent(attemptID) == true && connection.isCurrent()
            }
        } else {
            summaryContextValidity = nil
        }
        detail = activeConnection?.isCurrent() == true ? initialDetail : nil
        if let quota = detail?.quota { applyServerQuota(quota) }
        if activeConnection?.isCurrent() == true, let sessionID {
            detail = await pollForResult(
                sessionID: sessionID,
                initialDetail: initialDetail,
                initialDelayMilliseconds: pollAfterMilliseconds
            )
        }
        if activeConnection?.isCurrent() == true, let quota = detail?.quota {
            applyServerQuota(quota)
        }
        if activeConnection?.isCurrent() == true {
            await appState.refreshVoiceTutorStatus()
        }
        if activeConnection?.isCurrent() == true, let quota = appState.voiceTutorStatus?.quota {
            sessionQuota.apply(quota)
        }
        if activeConnection?.isCurrent() == true {
            await appState.loadVoiceTutorSessions(reset: true)
        }
        if let connection = activeConnection, connection.isCurrent() {
            for change in changedQuestions {
                guard connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else { break }
                await appState.refreshVoiceTutorQuestion(change, validity: { [weak self] in
                    self?.connectionAttemptFence.isCurrent(attemptID) == true && connection.isCurrent()
                })
            }
        }
        if activeConnection?.isCurrent() != true {
            detail = nil
            summaryContextValidity = nil
            summaryRefreshState = .idle
            captions = []
            assistantTranscriptState.discard()
        }
        duplexPlaybackState.reset()
        webRTCResponseState.reset()
        cancelTerminalPlayoutDrain()
        pendingSpokenEndPlayoutTail = nil
        quotaExhaustionNoticeResponseID = nil
        pauseState = VoiceTutorCallPauseState()
        usesWebRTC = false
        isFinalizing = false
        clearSessionCountdown()
        phase = .completed(outcome: outcome, serverState: detail?.state)
        if phase == .failed, errorMessage == nil {
            failureCause = failureCause ?? .unknown
            errorMessage = appState.strings.voiceTutorFailureMessage(failureCause ?? .unknown)
        }
        activeConnection = nil
        backgroundFinalization.endBackgroundTime()
    }

    /// This refresh never opens another call or asks the server to regenerate a
    /// result. It remains bound to the owner and attempt that ended this call.
    func refreshSummary() async {
        guard !isFinalizing, phase == .ended || phase == .failed,
              summaryRefreshState != .loading, let sessionID else { return }
        let attemptID = connectionAttemptFence.currentID
        let refreshed = await pollForResult(
            sessionID: sessionID,
            initialDetail: detail,
            initialDelayMilliseconds: detail?.pollAfterMilliseconds,
            refreshCachedDetail: true
        )
        guard connectionAttemptFence.isCurrent(attemptID) else { return }
        detail = refreshed
    }

    private func pollForResult(
        sessionID: String,
        initialDetail: BackendVoiceTutorSessionDetail?,
        initialDelayMilliseconds: Int?,
        refreshCachedDetail: Bool = false
    ) async -> BackendVoiceTutorSessionDetail? {
        let attemptID = connectionAttemptFence.currentID
        let requestID = UUID()
        summaryRequestID = requestID
        guard let contextIsCurrent = summaryContextValidity, contextIsCurrent() else {
            summaryRefreshState = .idle
            return nil
        }
        let isCurrent: @MainActor @Sendable () -> Bool = { [weak self] in
            self?.summaryRequestID == requestID
                && self?.connectionAttemptFence.isCurrent(attemptID) == true
                && contextIsCurrent()
        }
        guard let loader = appState.makeVoiceTutorSessionDetailLoader(sessionID: sessionID, validity: isCurrent) else {
            summaryRefreshState = .unavailable
            return initialDetail
        }
        summaryRefreshState = .loading
        let outcome = await VoiceTutorSummaryPolling.poll(
            sessionID: sessionID,
            initialDetail: initialDetail,
            initialDelayMilliseconds: initialDelayMilliseconds,
            refreshCachedDetail: refreshCachedDetail,
            loader: loader,
            isCurrent: isCurrent,
            onUpdate: { [weak self] in self?.detail = $0 }
        )
        guard connectionAttemptFence.isCurrent(attemptID), contextIsCurrent() else { return nil }
        // A result-ready event may have published a newer terminal result while
        // this read was suspended. Never replace it with the older placeholder.
        guard summaryRequestID == requestID else { return detail }
        summaryRefreshState = outcome.refreshState
        return outcome.detail
    }

    private func commitAssistantTranscript(responseID: String) {
        let providerItemIDs = assistantTranscriptState.providerItemIDs
        let providerItemID = assistantTranscriptState.lastProviderItemID ?? assistantTranscriptItemID
        guard assistantTranscriptState.matchesResponse(responseID),
              let text = assistantTranscriptState.commit() else { return }
        appendCaption(speaker: .tutor, text: text, responseID: responseID,
                      providerItemID: providerItemID, providerItemIDs: providerItemIDs)
        assistantTranscriptItemID = nil
    }

    private func preserveInterruptedAssistantTranscript(responseID: String?) {
        guard let responseID, assistantTranscriptState.matchesResponse(responseID) else { return }
        // A server-completed caption is immutable. A duplicate local/server
        // interruption must never replace or append that response again.
        assistantTranscriptState.retainInterruptedCaption(responseID: responseID,
            providerItemID: assistantTranscriptItemID, in: &captions)
        assistantTranscriptItemID = nil
    }

    @discardableResult
    private func appendCaption(speaker: VoiceTutorCaption.Speaker, text: String,
                               responseID: String? = nil, providerItemID: String? = nil,
                               providerItemIDs: Set<String> = [], answerID: String? = nil) -> UUID? {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            return nil
        }
        let caption = VoiceTutorCaption(
            speaker: speaker,
            text: VoiceTutorLiveTextBounds.boundedCaption(normalized),
            responseID: responseID, providerItemID: providerItemID, providerItemIDs: providerItemIDs, answerID: answerID
        )
        captions.append(caption)
        VoiceTutorLiveTextBounds.trim(&captions)
        return caption.id
    }

    private func startCountdown() {
        countdownTask?.cancel()
        let attemptID = connectionAttemptFence.currentID
        let connection = activeConnection
        countdownTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled,
                      self.connectionAttemptFence.isCurrent(attemptID) else {
                    return
                }
                guard connection?.isCurrent() == true else {
                    // Context cleanup originates here; do not self-cancel it
                    // when stop() clears the displayed countdown.
                    self.countdownTask = nil
                    await self.stopForInvalidatedContext()
                    return
                }
                self.updateSessionCountdown()
            }
        }
    }

    private func clearSessionCountdown() {
        countdownTask?.cancel()
        countdownTask = nil
        hardEndsAt = nil
        sessionSecondsRemaining = nil
    }

    private func applyServerQuota(_ quota: BackendVoiceTutorQuota) {
        sessionQuota.apply(quota)
        // The dismissed call may settle after a new call has reserved time.
        // Its historical quota belongs only to its detail; the fresh status
        // refresh in finishSession updates the shared admission display.
        guard !didRequestLocalEnd else { return }
        appState.applyVoiceTutorQuota(quota)
    }

    private func updateSessionCountdown() {
        guard let hardEndsAt else {
            sessionSecondsRemaining = nil
            return
        }
        sessionSecondsRemaining = max(0, Int(hardEndsAt.timeIntervalSinceNow.rounded(.up)))
    }

}
#endif
