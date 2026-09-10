#if os(iOS)
import AVFoundation
import Foundation

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
    case connection
    case provider
    case providerUnavailable
    case updateRequired
    case requestRejected
    case microphone
    case audio
    case localControl
    case service
    case unknown
}

struct VoiceTutorStartupFailurePresentation: Equatable {
    let cause: VoiceTutorFailureCause
    let message: String
}

enum VoiceTutorStartupFailurePolicy {
    static func presentation(
        for error: Error,
        strings: AppStrings
    ) -> VoiceTutorStartupFailurePresentation {
        if let webRTCPresentation = webRTCFailurePresentation(for: error, strings: strings) {
            return webRTCPresentation
        }
        if error is VoiceTutorSileroError {
            return VoiceTutorStartupFailurePresentation(
                cause: .unknown,
                message: strings.voiceTutorInputPreparationFailed
            )
        }
        if let preparationError = error as? VoiceTutorPreparationError {
            let message: String
            switch preparationError {
            case .signInRequired:
                message = strings.voiceTutorSignInRequired
            case .missingRegistration:
                message = strings.voiceTutorAccountNotReady
            case .invalidWebSocketURL:
                message = strings.voiceTutorInvalidConnection
            }
            return VoiceTutorStartupFailurePresentation(cause: .unknown, message: message)
        }
        if let audioError = error as? VoiceTutorAudioEngine.AudioError {
            let message: String
            switch audioError {
            case .microphonePermissionDenied:
                message = strings.voiceTutorMicrophoneDenied
            case .unsupportedInputFormat, .invalidOutputAudio:
                message = strings.voiceTutorConnectionFailed
            }
            return VoiceTutorStartupFailurePresentation(cause: .unknown, message: message)
        }
        return VoiceTutorStartupFailurePresentation(
            cause: .unknown,
            message: strings.voiceTutorConnectionFailed
        )
    }

    private static func webRTCFailurePresentation(
        for error: Error,
        strings: AppStrings
    ) -> VoiceTutorStartupFailurePresentation? {
        guard let webRTCError = error as? VoiceTutorWebRTCError,
              case let .sdpExchangeFailed(statusCode, backendFailure) = webRTCError else {
            return nil
        }
        if statusCode == 503
            || backendFailure?.code?.uppercased() == "VOICE_TUTOR_PROVIDER_UNAVAILABLE" {
            return VoiceTutorStartupFailurePresentation(
                cause: .providerUnavailable,
                message: strings.serviceTemporarilyUnavailable
            )
        }
        if statusCode == 426 {
            return VoiceTutorStartupFailurePresentation(
                cause: .updateRequired,
                message: strings.voiceTutorUpdateRequiredMessage
            )
        }
        if [400, 413, 415, 422].contains(statusCode) {
            return VoiceTutorStartupFailurePresentation(
                cause: .requestRejected,
                message: strings.voiceTutorRequestRejected
            )
        }
        return nil
    }
}

private enum VoiceTutorStopSource: String {
    case startupFailure, user, dismissal, audioInterruption, mediaFailure
    case identityInvalidated, controlReceiveFailure, providerError, pcmPlaybackFailure
    case localSpeechDeliveryFailure, pauseControlFailure
}

/// Exact server protocol reasons, kept separate from natural-language intent.
/// This is deliberately an allow-list rather than fuzzy text matching: an
/// unknown reason must never turn a transport failure into a successful call.
enum VoiceTutorServerEndReasonPolicy {
    static let quotaExhausted = "QUOTA_EXHAUSTED"

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
        return "errorKind=\(kind) errorDomain=\(domain) errorCode=\(value.code)"
    }
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

    func containsProviderItemID(_ id: String) -> Bool {
        providerItemID == id || providerItemIDs.contains(id)
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

/// Keeps a tutor sentence provisional until the provider confirms that the
/// whole response completed. Transcript `done` only means that transcription
/// for that output item finished; the enclosing response can still fail and be
/// retried, so publishing it to chat at that point would preserve a failed
/// partial answer beside its replacement.
struct VoiceTutorAssistantTranscriptState: Equatable {
    private(set) var draft = ""

    mutating func append(delta: String) {
        draft = VoiceTutorLiveTextBounds.appending(delta: delta, to: draft)
    }

    mutating func stageCompletedTranscript(_ transcript: String?) {
        guard let transcript else { return }
        let normalized = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        // A non-nil final transcript is authoritative even when it is empty.
        // Keeping earlier deltas here would publish stale partial speech.
        draft = VoiceTutorLiveTextBounds.boundedCaption(normalized)
    }

    mutating func commit() -> String? {
        let normalized = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        draft = ""
        return normalized.isEmpty ? nil : normalized
    }

    mutating func discard() {
        draft = ""
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
    private var interruptedResponseIDs: [String] = []

    var isAwaitingTutorResponse: Bool {
        !isUserSpeaking && !inputNeedsRepeat
            && (isAwaitingInitialResponse || hasPendingLearnerTurn || isAwaitingActiveResponseAudio)
    }

    mutating func setInputNeedsRepeat(_ value: Bool) { inputNeedsRepeat = value }

    mutating func learnerTranscriptReceived(usesWebRTC: Bool) {
        // Native transcription can arrive after the same turn has failed. Only
        // the next acoustic start is evidence that the learner tried again.
        if !usesWebRTC { inputNeedsRepeat = false }
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
        return true
    }

    @discardableResult
    mutating func userSpeechStarted(sequence: Int? = nil, interruptsTutor: Bool = true) -> String? {
        if let sequence, sequence <= 0 { return nil }
        inputNeedsRepeat = false
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
        if sequence == 0 {
            // Only the server's silent opening uses zero. It cannot consume a
            // real learner turn or restart waiting when ready arrives later.
            return stopWaitingForInitialResponse()
        }
        guard sequence > 0, hasPendingLearnerTurn,
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

    /// Retry exhaustion may precede any announced response. Match the acoustic
    /// input independently, while a known active response still requires its ID
    /// so an older failed generation cannot abandon a replacement. The caller
    /// owns discarding that exact response's transcript and native audio.
    @discardableResult
    mutating func acceptInputRetry(sequence: Int, responseID: String?) -> Bool {
        guard sequence >= 0, !isUserSpeaking else { return false }
        if sequence == 0 {
            guard latestLearnerSpeechSequence == nil, !hasPendingLearnerTurn else { return false }
        } else {
            guard latestLearnerSpeechSequence == sequence else { return false }
        }
        if assistantResponseActive {
            guard matchesActiveResponse(responseID: responseID) else { return false }
        } else {
            guard inputSettled(sequence: sequence) else { return false }
        }
        inputNeedsRepeat = true
        return true
    }

    mutating func responseFinished(responseID: String) -> Bool {
        guard matchesActiveResponse(responseID: responseID) else { return false }
        assistantResponseActive = false
        isAwaitingActiveResponseAudio = false
        activeResponseID = nil
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
        assistantResponseActive = false
        isAwaitingActiveResponseAudio = false
        activeResponseID = nil
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
    @Published private(set) var sessionState = VoiceTutorSessionState()
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
    var isAnswerCaptureActive: Bool { answerDraftState.phase == .listening }
    var isAnswerReviewAvailable: Bool { [.review, .failed].contains(answerDraftState.phase) }
    var isAnswerSubmitting: Bool { answerDraftState.phase == .submitting }
    var canSubmitReviewedAnswer: Bool { canControlAnswer && answerDraftState.canSubmit }
    var canSkipReviewedQuestion: Bool { canControlAnswer && answerDraftState.canSkip }
    var presentationCaptions: [VoiceTutorCaption] {
        let hidden = Set(answerSourceItemIDs.compactMap { learnerCaptionIDsByItemID[$0] }).union(heldAnswerCaptionIDs)
        return captions.filter { !hidden.contains($0.id) }
    }
    private var canControlAnswer: Bool {
        usesWebRTC && phase.isLive && !isFinalizing && !pauseState.holdsMicrophone && !userInputState.holdsMicrophone && activeConnection?.isCurrent() == true
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
    private var activeConnection: VoiceTutorLiveConnection?
    private var sessionID: String?
    private var hardEndsAt: Date?
    private var isFinalizing = false
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
        guard !isFinalizing, phase == .idle || phase == .failed else {
            return
        }
        let attemptID = connectionAttemptFence.begin()
        learnerTranscriptState.beginAttempt(attemptID)
        studyFocus.beginAttempt(attemptID)
        summaryRequestID = UUID()
        summaryContextValidity = nil
        summaryRefreshState = .idle
        changedQuestions = []
        answerDraftState = VoiceTutorAnswerDraftState()
        sessionState = VoiceTutorSessionState()
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
                    }
                )
            }
            guard connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else {
                throw CancellationError()
            }
            startReceivingEvents(attemptID: attemptID)
            startHeartbeat()
            guard phase == .connecting || phase == .listening || phase == .speaking else {
                audioEngine.stop()
                webRTCTransport?.close()
                webRTCTransport = nil
                stopAudioSendPump()
                stopLocalSpeechEventPump()
                stopHeartbeat()
                await transport.disconnect(closeCode: .goingAway)
                await finishRecordingIfNeeded()
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

    func updateAnswerDraft(_ text: String) {
        guard phase.isLive, !isFinalizing, activeConnection?.isCurrent() == true,
              answerDraftState.edit(text) else { return }
        persistVoiceAnswerDraft(force: true)
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

    private func persistVoiceAnswerDraft(force: Bool) {
        guard force || answerDraftState.shouldPersistAutomatically,
              let connection = activeConnection, connection.isCurrent(),
              let studyID = answerDraftState.studyID, let recordID = answerDraftState.recordID,
              let change = VoiceTutorQuestionChange(studyID: studyID, recordID: recordID) else { return }
        appState.saveVoiceTutorAnswerDraft(answerDraftState.text, for: change, validity: { connection.isCurrent() })
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
              !userInputState.holdsMicrophone,
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

    func stopForUser() async {
        await stop(shouldNotifyServerOverSocket: true, source: .user)
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

    func stopForDismissal() async {
        if phase.shouldCloseFinalizingMediaForDismissal(isFinalizing: isFinalizing) {
            // A server-spoken-end tail may be awaiting its short local render
            // fence. Explicit dismissal wins: invalidate playout while the
            // already-running control/REST settlement continues on its own.
            recorder?.stopAcceptingFrames()
            audioEngine.stop()
            webRTCTransport?.close()
            webRTCTransport = nil
            stopAudioSendPump()
            stopLocalSpeechEventPump()
            return
        }
        guard phase.isLive || phase == .ending, !isFinalizing else {
            return
        }
        await stop(shouldNotifyServerOverSocket: true, source: .dismissal)
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
        guard !isFinalizing, phase.isLive || phase == .ending else {
            return
        }
        logDiagnostic("event=stop_requested source=\(source.rawValue) socketEnd=\(shouldNotifyServerOverSocket ? 1 : 0)", isWarning: outcome == .failed)
        sessionState.endLocally()
        operationState.endLocally()
        userInputState.endLocally()
        learnerTranscriptState.endLocally()
        if answerDraftState.hasUserEdited { persistVoiceAnswerDraft(force: true) }
        answerDraftState.endLocally()
        cancelTerminalPlayoutDrain()
        recorder?.stopAcceptingFrames()
        isFinalizing = true
        phase = .ending
        let shouldNotifyServerOverSocket = shouldNotifyServerOverSocket
            && activeConnection?.isCurrent() == true
        if activeConnection?.isCurrent() == false {
            audioEngine.stop()
            webRTCTransport?.close()
        }
        if usesWebRTC {
            webRTCTransport?.closeMicrophoneInput()
        } else {
            audioEngine.stop()
        }
        stopLocalSpeechEventPump()
        await finishAudioSendPump()
        stopHeartbeat()
        clearSessionCountdown()

        let serverEndStream = shouldNotifyServerOverSocket ? makeServerEndStream() : nil
        var didSendSocketEnd = false
        if shouldNotifyServerOverSocket {
            do {
                try await transport.sendSessionEnd()
                didSendSocketEnd = true
            } catch {
                didSendSocketEnd = false
            }
        }

        let serverEnded: VoiceTutorRealtimeEnded?
        if didSendSocketEnd, let serverEndStream {
            serverEnded = await waitForServerEnd(from: serverEndStream)
        } else {
            serverEnded = nil
        }
        clearServerEndWaiter()

        var endedDetail: BackendVoiceTutorSessionDetail?
        if serverEnded == nil, let activeConnection {
            logDiagnostic("event=rest_end_requested")
            endedDetail = try? await activeConnection.endSession()
        }
        // A socket failure reaches this method from receiveTask itself. Closing
        // the socket ends the receive loop without cancelling REST settlement.
        receiveTask = nil
        webRTCTransport?.close()
        webRTCTransport = nil
        await transport.disconnect()
        await finishRecordingIfNeeded()
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
        userInputState = VoiceTutorUserInputState()
        sessionState.endLocally()
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
        audioEngine.stop()
        webRTCTransport?.close()
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
                    if needsBoundary,
                       let responseID = self.duplexPlaybackState.activeResponseID
                        ?? self.pendingSpokenEndPlayoutTail?.responseID {
                        // Provider completion can precede the final audible RTP
                        // samples; protect that same tail before interrupting it.
                        await self.webRTCTransport?.waitForInterruptionBoundary(responseID: responseID)
                        guard !Task.isCancelled, self.connectionAttemptFence.isCurrent(attemptID),
                              connection.isCurrent(), self.phase.isLive, !self.isFinalizing else { return }
                    }
                    if case .speech(let event) = control {
                        switch event.activity {
                        case .started:
                            self.handleLearnerSpeechStarted(sequence: event.sequence)
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
            audioEngine.stop()
            errorMessage = pauseState.holdsMicrophone
                ? appState.strings.voiceTutorPauseFailed
                : appState.strings.voiceTutorConnectionFailed
            failureCause = pauseState.holdsMicrophone ? .localControl : .connection
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
                  userInputState.apply(request, sessionID: sessionID) else { break }
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
            _ = operationState.apply(event, at: ProcessInfo.processInfo.systemUptime,
                afterCaptionID: presentationCaptions.last?.id,
                responseID: assistantTranscriptDraft.isEmpty ? nil : assistantTranscriptResponseID)
        case .sessionState(let event):
            guard usesWebRTC, phase.isLive, !isFinalizing else { break }
            // Only the current authenticated control receive loop reaches this
            // path. Display snapshots never act as microphone or submit commands.
            _ = sessionState.apply(event, minimumRevision: studyFocus.revision)
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
            if !paused, webRTCTransport?.setMuted(isMuted || answerDraftState.holdsMicrophone || userInputState.holdsMicrophone) != true {
                await failPause()
                break
            }
            pauseTimeoutTask?.cancel()
            pauseTimeoutTask = nil
            pauseState = acknowledged
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
        case .responseInterrupted(let responseID):
            interruptTutorResponse(responseID: responseID)
        case .studyFocused(let focus):
            guard phase.isLive, !isFinalizing else { break }
            if studyFocus.apply(focus, attemptID: attemptID), answerDraftState.isActive,
               focus?.studyID != answerDraftState.studyID || focus?.revision != answerDraftState.revision {
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
        case .answerState(let event):
            guard usesWebRTC, phase.isLive, !isFinalizing,
                  connectionAttemptFence.isCurrent(attemptID), connection.isCurrent() else { break }
            let isNewAnswer = answerDraftState.answerID != event.answerID
            if isNewAnswer {
                guard event.phase == .listening, event.revision >= studyFocus.revision,
                      studyFocus.focus.map({ $0.studyID == event.studyID }) ?? true else { break }
            }
            let change = VoiceTutorQuestionChange(studyID: event.studyID, recordID: event.recordID)!
            let existing = isNewAnswer
                ? appState.voiceTutorAnswerDraft(for: change, validity: { connection.isCurrent() }) ?? ""
                : ""
            guard answerDraftState.apply(event, existingDraft: existing) else { break }
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
        case .serviceError(let code, _, _):
            switch code?.uppercased() {
            case "VOICE_TUTOR_PRO_REQUIRED":
                errorMessage = appState.strings.voiceTutorProRequiredMessage
                failureCause = .service
            case "VOICE_TUTOR_QUOTA_EXCEEDED":
                errorMessage = appState.strings.voiceTutorQuotaReached
                failureCause = .service
            case "VOICE_TUTOR_PROVIDER_UNAVAILABLE":
                errorMessage = appState.strings.serviceTemporarilyUnavailable
                failureCause = .provider
            default:
                errorMessage = pauseState.holdsMicrophone
                    ? appState.strings.voiceTutorPauseFailed
                    : appState.strings.serviceTemporarilyUnavailable
                failureCause = pauseState.holdsMicrophone ? .localControl : .provider
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
        case .assistantTranscriptDelta(let responseID, let delta):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            assistantTranscriptState.append(delta: delta)
        case .assistantTranscriptDone(let responseID, let transcript, let itemID):
            guard duplexPlaybackState.matchesActiveResponse(responseID: responseID) else {
                break
            }
            // Keep this full transcript provisional. Only a completed
            // response.done is allowed to publish it as a tutor chat message.
            assistantTranscriptState.stageCompletedTranscript(transcript)
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

    private func handleLearnerSpeechStarted(sequence: Int? = nil) {
        guard phase.isLive, !isFinalizing else { return }
        if let sequence, sequence <= 0 { return }
        inputNeedsRepeat = false
        if usesWebRTC,
           let responseID = duplexPlaybackState.activeResponseID ?? pendingSpokenEndPlayoutTail?.responseID {
            interruptTutorResponse(responseID: responseID)
        }
        duplexPlaybackState.userSpeechStarted(sequence: sequence, interruptsTutor: usesWebRTC)
        if !duplexPlaybackState.assistantResponseActive { phase = .listening }
    }

    private func interruptTutorResponse(responseID: String) {
        guard usesWebRTC, phase.isLive, !isFinalizing else { return }
        let affectsCurrentResponse = duplexPlaybackState.activeResponseID == responseID
            || (duplexPlaybackState.activeResponseID == nil && !duplexPlaybackState.wasInterrupted(responseID))
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
        if outcome == .failed, pauseState.holdsMicrophone, errorMessage == nil {
            errorMessage = appState.strings.voiceTutorPauseFailed
            failureCause = .localControl
        } else if outcome == .failed, failureCause == nil {
            failureCause = ended.reason?.uppercased() == "PROVIDER_ERROR" ? .provider : .connection
        }
        logDiagnostic("event=server_ended")
        sessionState.endLocally()
        operationState.endLocally()
        userInputState.endLocally()
        learnerTranscriptState.endLocally()
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
        recorder?.stopAcceptingFrames()
        audioEngine.stop()
        webRTCTransport?.close()
        webRTCTransport = nil
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
        guard let recorder else {
            isRecording = false
            return
        }
        self.recorder = nil
        isRecording = false
        let pending: VoiceTutorPendingRecording
        do {
            pending = try await recorder.finish()
        } catch {
            errorMessage = appState.strings.voiceTutorRecordingUnavailable
            return
        }
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
            errorMessage = failureCause == .provider
                ? appState.strings.serviceTemporarilyUnavailable
                : appState.strings.voiceTutorConnectionFailed
        }
        activeConnection = nil
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
        guard let text = assistantTranscriptState.commit() else { return }
        appendCaption(speaker: .tutor, text: text, responseID: responseID,
                      providerItemID: assistantTranscriptItemID)
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
