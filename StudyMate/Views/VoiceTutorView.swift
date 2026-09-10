#if os(iOS)
import AVFoundation
import SwiftUI

/// One explicit Home shortcut tap may admit one call after a fresh status read.
/// Consume that intent before suspension so a return to this entry, retry, or
/// cancelled presentation cannot unexpectedly start another call.
@MainActor
final class VoiceTutorCallEntryAdmission {
    private var hasAttemptedStart = false

    func refresh(
        startCallOnEntry: Bool,
        isCurrent: () -> Bool,
        loadStatus: () async -> BackendVoiceTutorStatus?
    ) async -> Bool {
        let shouldAttemptStart = startCallOnEntry && !hasAttemptedStart
        hasAttemptedStart = true
        let status = await loadStatus()
        return shouldAttemptStart
            && !Task.isCancelled
            && isCurrent()
            && VoiceTutorCallStartPolicy.canStart(status: status)
    }
}

struct VoiceTutorView: View {
    let startCallOnEntry: Bool
    @EnvironmentObject private var appState: AppState
    @State private var showsMembership = false
    @State private var recordingConsent = false
    @State private var callRecordingConsent = false
    @State private var showsCall = false
    @State private var callEntryAdmission = VoiceTutorCallEntryAdmission()
    @State private var isPreparingEntryStatus = true

    init(startCallOnEntry: Bool = false) {
        self.startCallOnEntry = startCallOnEntry
    }

    private var strings: AppStrings { appState.strings }

    private var status: BackendVoiceTutorStatus? {
        appState.voiceTutorStatus
    }

    private var canStart: Bool {
        VoiceTutorCallStartPolicy.canStart(status: status)
    }

    var body: some View {
        List {
            voiceAccessSection

            if status?.activeSession != nil
                || (status?.eligible == true && (status?.quota.remainingSeconds ?? 0) > 0) {
                Section(strings.voiceTutorCallTitle) {
                    if status?.activeSession != nil {
                        VStack(alignment: .leading, spacing: 8) {
                            Label(
                                strings.voiceTutorActiveSessionPending,
                                systemImage: "clock.arrow.circlepath"
                            )
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                            Button(strings.retry) {
                                Task {
                                    await refreshEntryStatus()
                                }
                            }
                            .buttonStyle(.bordered)
                        }
                        .padding(.vertical, 3)
                    } else {
                        if status?.recording?.enabled == true {
                            VStack(alignment: .leading, spacing: 8) {
                                Toggle(
                                    strings.voiceTutorRecordingConsentTitle,
                                    isOn: $recordingConsent
                                )
                                .font(.subheadline.weight(.semibold))

                                Text(strings.voiceTutorRecordingConsentDescription)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)

                                Text(
                                    strings.voiceTutorRecordingRetention(
                                        status?.recording?.retentionDays ?? 30
                                    )
                                )
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 4)
                        }

                        Button {
                            // The destination must outlive quota reservation:
                            // reserving the allowance removes this start row.
                            callRecordingConsent = recordingConsent
                            showsCall = true
                        } label: {
                            Label(strings.voiceTutorCallStart, systemImage: "phone.fill")
                                .fontWeight(.semibold)
                        }
                        .disabled(!canStart)
                    }
                }
            }

            Section(strings.voiceTutorRecentSessions) {
                if appState.isLoadingVoiceTutorSessions && appState.voiceTutorSessions.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(strings.loading)
                            .foregroundStyle(.secondary)
                    }
                } else if appState.voiceTutorSessions.isEmpty {
                    Text(strings.voiceTutorNoSessions)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(appState.voiceTutorSessions) { session in
                        NavigationLink {
                            VoiceTutorSessionDetailView(sessionID: session.sessionId)
                        } label: {
                            VoiceTutorSessionRow(session: session, strings: strings)
                        }
                    }

                    if appState.voiceTutorNextCursor != nil {
                        Button {
                            Task {
                                await appState.loadVoiceTutorSessions(reset: false)
                            }
                        } label: {
                            HStack {
                                Spacer()
                                if appState.isLoadingVoiceTutorSessions {
                                    ProgressView()
                                } else {
                                    Text(strings.more)
                                }
                                Spacer()
                            }
                        }
                        .disabled(appState.isLoadingVoiceTutorSessions)
                    }
                }
            }

            if let error = appState.voiceTutorErrorMessage, !error.isEmpty {
                Section {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(strings.voiceTutorTitle)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showsCall) {
            VoiceTutorSessionView(
                appState: appState,
                recordingConsent: callRecordingConsent,
                onRecordingConsentConsumed: { recordingConsent = false }
            )
        }
        .task {
            recordingConsent = false
            let identity = appState.commonRecordsIdentity
            let shouldStart = await callEntryAdmission.refresh(
                startCallOnEntry: startCallOnEntry,
                isCurrent: {
                    appState.isVoiceTutorEntryIdentityCurrent(identity)
                },
                loadStatus: { await refreshEntryStatus() }
            )
            guard !Task.isCancelled else { return }
            if shouldStart, !showsCall {
                // A shortcut starts an unrecorded call. Recording still requires
                // the separate, explicit consent control for that exact call.
                recordingConsent = false
                callRecordingConsent = false
                showsCall = true
                return
            }
            // History and old recording uploads must not delay quick admission.
            await appState.retryPendingVoiceTutorRecordingUploads()
            await appState.loadVoiceTutorSessions(reset: true)
        }
        .refreshable {
            recordingConsent = false
            await refreshEntryStatus()
            await appState.loadVoiceTutorSessions(reset: true)
        }
        .onChange(of: status?.recording?.enabled) { _, isEnabled in
            if isEnabled != true {
                recordingConsent = false
            }
        }
        .sheet(isPresented: $showsMembership) {
            NavigationStack {
                MobileMembershipManagementView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(strings.close) {
                                showsMembership = false
                            }
                        }
                    }
            }
            .environmentObject(appState)
            .presentationDragIndicator(.visible)
        }
    }

    @ViewBuilder
    private var voiceAccessSection: some View {
        Section(strings.voiceTutorMonthlyUsage) {
            if (isPreparingEntryStatus || appState.isLoadingVoiceTutorStatus) && status == nil {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(strings.loading)
                        .foregroundStyle(.secondary)
                }
            } else if let status, status.eligible {
                VoiceTutorQuotaView(status: status, strings: strings)
            } else if let status,
                      status.reason?.uppercased() == "QUOTA_EXHAUSTED" {
                VoiceTutorQuotaView(status: status, strings: strings)
            } else if let status,
                      status.reason?.uppercased() == "PRO_REQUIRED"
                        || (status.reason == nil && status.quota.limitSeconds == 0) {
                proUpgradeContent
            } else {
                unavailableContent
            }
        }
    }

    private var proUpgradeContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(strings.voiceTutorProRequired, systemImage: "lock.fill")
                .font(.headline)
            Text(strings.voiceTutorProRequiredMessage)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(strings.voiceTutorUpgrade) {
                showsMembership = true
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(.vertical, 4)
    }

    private var unavailableContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(strings.serviceTemporarilyUnavailable, systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(strings.retry) {
                Task {
                    await refreshEntryStatus()
                }
            }
            .buttonStyle(.bordered)
        }
        .padding(.vertical, 4)
    }

    @discardableResult
    private func refreshEntryStatus() async -> BackendVoiceTutorStatus? {
        isPreparingEntryStatus = true
        defer { isPreparingEntryStatus = false }
        return await appState.prepareVoiceTutorStatusForEntry()
    }

}

private struct VoiceTutorQuotaView: View {
    var status: BackendVoiceTutorStatus
    var strings: AppStrings

    private var usedSeconds: Int {
        max(0, status.quota.usedSeconds)
    }

    private var progress: Double {
        guard status.quota.limitSeconds > 0 else {
            return 0
        }
        return min(1, Double(usedSeconds) / Double(status.quota.limitSeconds))
    }

    private var isUnlimited: Bool {
        VoiceTutorQuotaPresentation.isUnlimited(limitSeconds: status.quota.limitSeconds)
    }

    var body: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 8) {
                ProgressView(value: progress)

                HStack {
                    Text(strings.voiceTutorUsedTime(usedSeconds))
                    Spacer()
                    if let resetAt = status.quota.resetAt {
                        Text(strings.monthlyQuotaReset(resetAt))
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)

                if status.quota.reservedSeconds > 0 {
                    Text(strings.voiceTutorReservedTime(status.quota.reservedSeconds))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if status.maxSessionSeconds > 0 {
                    Text(strings.voiceTutorSessionLimit(status.maxSessionSeconds))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                if !isUnlimited,
                   status.quota.remainingSeconds == 0,
                   status.quota.reservedSeconds == 0 {
                    Text(strings.voiceTutorQuotaReached)
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }
            .padding(.vertical, 6)
        } label: {
            HStack(alignment: .firstTextBaseline) {
                Text(isUnlimited
                    ? strings.voiceTutorUnlimited
                    : status.quota.reservedSeconds > 0
                        ? strings.voiceTutorUnreservedTime(status.quota.remainingSeconds)
                        : strings.voiceTutorRemainingTime(status.quota.remainingSeconds))
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(strings.membershipTierName(status.tierCode))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tint)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct VoiceTutorSessionRow: View {
    var session: BackendVoiceTutorSessionListItem
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(session.topic.isEmpty ? strings.voiceTutorTitle : session.topic)
                .font(.body.weight(.medium))
                .lineLimit(1)

            HStack(spacing: 8) {
                if let startedAt = session.startedAt {
                    Text(
                        StudyDateDisplayFormatter.relativeOrShortDateString(
                            for: startedAt,
                            language: strings.language
                        )
                    )
                }
                if session.chargedSeconds > 0 {
                    Text(strings.voiceTutorDuration(session.chargedSeconds))
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            if let preview = session.summaryPreview?.trimmingCharacters(in: .whitespacesAndNewlines),
               !preview.isEmpty {
                Text(preview)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .padding(.vertical, 3)
    }
}

struct VoiceTutorCallDisclosureState: Equatable {
    var showsTranscript = false
    var showsSummary = false

    mutating func resetForNewAttempt() {
        showsTranscript = false
        showsSummary = false
    }
}

enum VoiceTutorOrbTranscriptAction: Equatable {
    case reveal, hide
}

/// A deliberate vertical swipe changes only transcript presentation. Keeping
/// this threshold above ordinary tap movement lets the orb's existing tap own
/// pause/resume without an accidental disclosure transition.
struct VoiceTutorOrbGestureRouting {
    static let dragRecognitionDistance: CGFloat = 12
    static let minimumVerticalTranslation: CGFloat = 44
    static let verticalDominanceRatio: CGFloat = 1.15

    static func transcriptAction(
        for translation: CGSize,
        showsTranscript: Bool
    ) -> VoiceTutorOrbTranscriptAction? {
        let vertical = translation.height
        let verticalDistance = abs(vertical)
        let horizontalDistance = abs(translation.width)
        guard verticalDistance >= minimumVerticalTranslation,
              verticalDistance >= horizontalDistance * verticalDominanceRatio else { return nil }
        if vertical < 0, !showsTranscript { return .reveal }
        if vertical > 0, showsTranscript { return .hide }
        return nil
    }

    static func expansion(
        for translation: CGSize,
        startsExpanded: Bool,
        travel: CGFloat
    ) -> CGFloat {
        let start: CGFloat = startsExpanded ? 1 : 0
        guard translation.width.isFinite, translation.height.isFinite,
              travel.isFinite, travel > 0,
              abs(translation.height) >= abs(translation.width) * verticalDominanceRatio else { return start }
        return min(1, max(0, start - translation.height / travel))
    }

    static func settlesExpanded(
        translation: CGSize,
        predictedTranslation: CGSize,
        startsExpanded: Bool
    ) -> Bool {
        if let action = transcriptAction(for: translation, showsTranscript: startsExpanded) {
            return action == .reveal
        }
        // A short, deliberate flick can finish the gesture, but ordinary tap
        // movement and horizontal drags still belong to the original control.
        guard abs(translation.height) >= dragRecognitionDistance,
              abs(translation.height) >= abs(translation.width) * verticalDominanceRatio,
              translation.height * predictedTranslation.height > 0,
              let action = transcriptAction(for: predictedTranslation, showsTranscript: startsExpanded)
        else { return startsExpanded }
        return action == .reveal
    }

    /// A short/coalesced swipe may deliver its travel only in onEnded. Use
    /// the same thresholds and velocity policy as continuously sampled drags.
    static func releaseAction(
        for translation: CGSize,
        predictedTranslation: CGSize,
        showsTranscript: Bool
    ) -> VoiceTutorOrbTranscriptAction? {
        guard translation.width.isFinite, translation.height.isFinite,
              predictedTranslation.width.isFinite, predictedTranslation.height.isFinite else { return nil }
        let expanded = settlesExpanded(
            translation: translation,
            predictedTranslation: predictedTranslation,
            startsExpanded: showsTranscript
        )
        guard expanded != showsTranscript else { return nil }
        return expanded ? .reveal : .hide
    }

    static func orbFrame(from compact: CGRect, to transcript: CGRect, expansion: CGFloat) -> CGRect {
        let progress = min(1, max(0, expansion.isFinite ? expansion : 0))
        return CGRect(
            x: compact.minX + (transcript.minX - compact.minX) * progress,
            y: compact.minY + (transcript.minY - compact.minY) * progress,
            width: compact.width + (transcript.width - compact.width) * progress,
            height: compact.height + (transcript.height - compact.height) * progress
        )
    }
}

/// One touch owns tap, swipe, or hold. A cancelled or deliberate hold can
/// never fall through to pause on release, and end is emitted once per touch.
struct VoiceTutorOrbInteractionState: Equatable {
    enum HoldStage: Equatable { case idle, anticipating, warning, committed }

    static let anticipationDuration: TimeInterval = 0.35
    static let warningDuration: TimeInterval = 0.9
    static let endDuration: TimeInterval = 2.2
    static let movementTolerance: CGFloat = 12

    private(set) var startedAt: TimeInterval?
    private(set) var elapsed: TimeInterval = 0
    private(set) var stage: HoldStage = .idle
    private(set) var hasMoved = false
    private(set) var isCancelled = false
    private(set) var didCommitEnd = false

    var isActive: Bool { startedAt != nil }
    var progress: Double {
        guard !hasMoved, !isCancelled else { return 0 }
        return min(1, max(0, (elapsed - Self.warningDuration) / (Self.endDuration - Self.warningDuration)))
    }

    mutating func begin(at time: TimeInterval) {
        guard !isActive, time.isFinite else { return }
        self = Self()
        startedAt = time
    }

    mutating func move(translation: CGSize) {
        guard isActive else { return }
        guard translation.width.isFinite, translation.height.isFinite else {
            cancel()
            return
        }
        if hypot(translation.width, translation.height) >= Self.movementTolerance {
            hasMoved = true
            stage = .idle
        }
    }

    /// Returns true only at the first eligible crossing of the end threshold.
    @discardableResult
    mutating func advance(to time: TimeInterval, canEnd: Bool) -> Bool {
        guard let startedAt, time.isFinite, time >= startedAt,
              !hasMoved, !isCancelled, !didCommitEnd else { return false }
        guard canEnd else {
            cancel()
            return false
        }
        elapsed = max(elapsed, time - startedAt)
        if elapsed >= Self.endDuration {
            stage = .committed
            didCommitEnd = true
            return true
        }
        stage = elapsed >= Self.warningDuration ? .warning
            : elapsed >= Self.anticipationDuration ? .anticipating : .idle
        return false
    }

    /// A release after anticipation cancels the hold instead of becoming a tap.
    mutating func release(at time: TimeInterval) -> Bool {
        let isTap = startedAt.map { start in
            time.isFinite && time >= start && time - start < Self.anticipationDuration
                && !hasMoved && !isCancelled && !didCommitEnd
        } ?? false
        self = Self()
        return isTap
    }

    mutating func cancel() {
        isCancelled = true
        stage = .idle
    }
}

struct VoiceTutorSessionView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel: VoiceTutorViewModel
    @State private var disclosureState = VoiceTutorCallDisclosureState()
    private let strings: AppStrings
    private let onRecordingConsentConsumed: () -> Void

    init(
        appState: AppState,
        recordingConsent: Bool,
        onRecordingConsentConsumed: @escaping () -> Void = {}
    ) {
        _viewModel = StateObject(
            wrappedValue: VoiceTutorViewModel(
                appState: appState,
                recordingConsent: recordingConsent
            )
        )
        strings = appState.strings
        self.onRecordingConsentConsumed = onRecordingConsentConsumed
    }

    var body: some View {
        VoiceTutorCallScreen(
            topic: viewModel.studyFocus.focus?.topic ?? strings.voiceTutorDiscoveryTeacher,
            discoveryPrompt: viewModel.studyFocus.focus == nil && viewModel.phase.isLive
                ? strings.voiceTutorDiscoveryPrompt : nil,
            presentation: VoiceTutorCallPresentation(
                phase: viewModel.phase,
                failureCause: viewModel.failureCause,
                serverEndReason: viewModel.serverEndReason,
                isRecording: viewModel.isRecording,
                inputNeedsRepeat: viewModel.inputNeedsRepeat,
                isAwaitingTutorResponse: viewModel.isAwaitingTutorResponse,
                pauseState: viewModel.pauseState,
                sessionState: viewModel.sessionState,
                sessionSecondsRemaining: viewModel.sessionSecondsRemaining,
                quotaRemainingSeconds: viewModel.quotaRemainingSeconds,
                quotaReservedSeconds: viewModel.quotaReservedSeconds,
                quotaLimitSeconds: viewModel.quotaLimitSeconds,
                detail: viewModel.detail,
                summaryRefreshState: viewModel.summaryRefreshState
            ),
            strings: strings,
            operationState: viewModel.operationState,
            captions: viewModel.presentationCaptions,
            assistantTranscriptDraft: viewModel.assistantTranscriptDraft,
            errorMessage: viewModel.errorMessage,
            showsTranscript: $disclosureState.showsTranscript,
            showsSummary: $disclosureState.showsSummary,
            onPause: { Task { await viewModel.togglePause() } },
            onEnd: { Task { await viewModel.stopForUser() } },
            onRetry: {
                disclosureState.resetForNewAttempt()
                Task { await viewModel.start() }
            },
            onDismiss: { dismiss() },
            onSummaryRefresh: { Task { await viewModel.refreshSummary() } },
            answerDraftState: viewModel.answerDraftState,
            answerDraftText: Binding(
                get: { viewModel.answerDraftText },
                set: { viewModel.updateAnswerDraft($0) }
            ),
            canSubmitAnswer: viewModel.canSubmitReviewedAnswer,
            onFinishAnswer: { Task { await viewModel.finishAnswerCapture() } },
            onSubmitAnswer: { Task { await viewModel.submitReviewedAnswer() } },
            canSkipAnswer: viewModel.canSkipReviewedQuestion,
            onSkipAnswer: { Task { await viewModel.skipReviewedQuestion() } }
        )
        .navigationTitle(strings.voiceTutorCallTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task {
            disclosureState.resetForNewAttempt()
            onRecordingConsentConsumed()
            await viewModel.start()
        }
        .onChange(of: scenePhase) { _, newPhase in
            switch newPhase {
            case .background:
                viewModel.appDidEnterBackground()
            case .active:
                viewModel.appDidBecomeActive()
            default:
                break
            }
        }
        .onDisappear {
            // App switching/locking can remove the rendered surface without
            // dismissing the user's call. Only foreground navigation ends it.
            guard scenePhase == .active else { return }
            Task {
                await viewModel.stopForDismissal()
            }
        }
    }
}

/// Keeps essential call chrome reachable without capping the user's preferred
/// text size. Accessibility categories use vertical controls and compact icon
/// buttons beside the scrollable call content.
struct VoiceTutorCallAdaptiveLayout {
    static func usesAccessibilityChrome(for size: DynamicTypeSize) -> Bool {
        size.isAccessibilitySize
    }
}

/// Display-only state. Time and connection truth still belong to the existing
/// session model; UI expansion never starts, stops, or replaces a call.
struct VoiceTutorCallPresentation {
    enum RemainingTime: Equatable {
        case call(Int)
        case monthly(Int)
        case monthlyUnlimited
    }

    enum SummaryState: Equatable {
        case hidden, pending, ready, failed, deferred, unavailable
    }

    enum PrimaryAction: Equatable {
        case end, retry, dismiss, wait
    }

    enum OrbState: Equatable {
        case connecting, listening, thinking, speaking, pausing, paused, resuming, ending, ended, failed
        case questionReady, capturingAnswer, reviewingAnswer, graded, learningFailed
    }

    enum OrbAction: Equatable {
        case none, pause, resume
    }

    var phase: VoiceTutorSessionPhase
    var failureCause: VoiceTutorFailureCause? = nil
    var serverEndReason: String? = nil
    var isRecording = false
    var inputNeedsRepeat = false
    var isAwaitingTutorResponse = false
    var pauseState = VoiceTutorCallPauseState()
    var sessionState = VoiceTutorSessionState()
    var sessionSecondsRemaining: Int?
    var quotaRemainingSeconds = 0
    var quotaReservedSeconds = 0
    var quotaLimitSeconds = 0
    var detail: BackendVoiceTutorSessionDetail?
    var summaryRefreshState: VoiceTutorSummaryRefreshState = .idle

    var showsPauseControl: Bool { pauseState.isSupported && phase.isLive }
    var canChangePause: Bool {
        showsPauseControl && (phase == .listening || phase == .speaking) && !pauseState.isAwaitingAcknowledgement
    }
    var orbState: OrbState {
        if isMonthlyQuotaExhausted, phase == .failed {
            return .ended
        }
        if phase == .listening || phase == .speaking {
            switch pauseState.mode {
            case .pausing: return .pausing
            case .paused: return .paused
            case .resuming: return .resuming
            case .active: break
            }
            if isServerPaused { return .paused }
            switch lessonPhase {
            case .questionLoading, .questionGenerating, .answerFinalizing, .answerSubmitting, .grading:
                return .thinking
            case .questionReady: return .questionReady
            case .questionReading: return .speaking
            case .answering: return .capturingAnswer
            case .answerReview: return .reviewingAnswer
            case .graded: return .graded
            case .questionFailed, .gradingFailed, .answerFailed: return .learningFailed
            case .ending: return .ending
            case .ended: return .ended
            case .failed: return .failed
            case .conversation, nil: break
            }
        }
        switch phase {
        case .idle, .requestingPermission, .connecting: return .connecting
        case .listening: return isAwaitingTutorResponse && !inputNeedsRepeat ? .thinking : .listening
        case .speaking: return .speaking
        case .ending: return .ending
        case .ended: return .ended
        case .failed: return .failed
        }
    }

    var orbAction: OrbAction {
        guard canChangePause else { return .none }
        return pauseState.mode == .paused ? .resume : .pause
    }

    var orbAnimates: Bool {
        orbState == .listening || orbState == .thinking || orbState == .speaking
    }

    /// Lesson progress comes from the backend; transport and pending local
    /// controls remain authoritative for whether the user can interact.
    var lessonPhase: VoiceTutorSessionStateEvent.Phase? {
        guard phase == .listening || phase == .speaking else { return nil }
        return sessionState.snapshot?.phase
    }

    var isServerPaused: Bool {
        lessonPhase != nil && pauseState.mode == .active && sessionState.snapshot?.paused == true
    }

    var canDisplayActiveAnswer: Bool {
        guard phase == .listening || phase == .speaking,
              pauseState.mode == .active, !isServerPaused else { return false }
        return lessonPhase != .ending && lessonPhase != .ended && lessonPhase != .failed
    }

    var lessonSymbolName: String? {
        guard pauseState.mode == .active, !isServerPaused else { return nil }
        switch lessonPhase {
        case .questionLoading: return "tray.and.arrow.down"
        case .questionGenerating: return "sparkles"
        case .questionReady: return "book.closed"
        case .questionReading: return "speaker.wave.2.fill"
        case .answering: return "mic.fill"
        case .answerFinalizing, .answerSubmitting, .grading: return "ellipsis"
        case .answerReview: return "text.cursor"
        case .graded: return "checkmark"
        case .questionFailed, .gradingFailed, .answerFailed, .failed: return "exclamationmark"
        case .ending, .ended: return "phone.down.fill"
        case .conversation, nil: return nil
        }
    }

    var remainingTime: RemainingTime? {
        if phase.isLive || hasQuotaTerminalCountdown {
            // Monthly availability excludes the current reservation and can be
            // zero during a healthy call. A pre-armed final quota notice uses
            // the last reserved seconds, so keep showing that same countdown
            // until the exact hard boundary instead of claiming it ended early.
            return sessionSecondsRemaining.map { .call(max(0, $0)) }
        }
        guard phase == .ended || phase == .failed,
              quotaLimitSeconds > 0, quotaReservedSeconds == 0 else { return nil }
        if VoiceTutorQuotaPresentation.isUnlimited(limitSeconds: quotaLimitSeconds) {
            return .monthlyUnlimited
        }
        return .monthly(max(0, quotaRemainingSeconds))
    }

    var primaryAction: PrimaryAction {
        if phase.isLive { return .end }
        if isMonthlyQuotaExhausted, phase == .ended || phase == .failed {
            return .dismiss
        }
        switch phase {
        case .failed:
            switch failureCause {
            case .updateRequired, .requestRejected: return .dismiss
            default: return .retry
            }
        case .ended: return .dismiss
        default: return .wait
        }
    }

    var summaryState: SummaryState {
        guard phase == .ended || phase == .failed else { return .hidden }
        let state = VoiceTutorSummaryState(detail: detail)
        switch state {
        case .ready: return .ready
        case .failed: return .failed
        case .empty: return .hidden
        case .unknown, .pending:
            switch summaryRefreshState {
            case .loading: return .pending
            case .deferred: return .deferred
            case .unavailable: return .unavailable
            case .idle: return state == .pending ? .pending : .hidden
            }
        }
    }

    func showsConnectionFailure(_ strings: AppStrings, errorMessage: String?) -> Bool {
        if isMonthlyQuotaExhausted { return false }
        if let failureCause {
            return (phase == .failed || phase == .ending) && failureCause == .connection
        }
        return phase == .failed || (phase == .ending
            && errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines) == strings.voiceTutorConnectionFailed)
    }

    func statusText(
        _ strings: AppStrings,
        errorMessage: String? = nil,
        answerDraftState: VoiceTutorAnswerDraftState = VoiceTutorAnswerDraftState()
    ) -> String {
        if isMonthlyQuotaExhausted,
           phase == .ended || phase == .failed ||
            (phase == .ending && !isQuotaNoticeUsingFinalSeconds) {
            return strings.voiceTutorCallQuotaEnded
        }
        if showsConnectionFailure(strings, errorMessage: errorMessage) {
            return strings.voiceTutorCallFailed
        }
        if phase == .listening || phase == .speaking {
            switch pauseState.mode {
            case .pausing: return strings.voiceTutorPausing
            case .paused: return strings.voiceTutorPaused
            case .resuming: return strings.voiceTutorResuming
            case .active: break
            }
            if isServerPaused { return strings.voiceTutorPaused }
            if canDisplayActiveAnswer {
                switch answerDraftState.phase {
                case .listening: return strings.voiceTutorAnswerListening
                case .finalizing: return strings.voiceTutorAnswerFinalizing
                case .review: return strings.voiceTutorAnswerReview
                case .submitting: return strings.voiceTutorAnswerSubmitting
                case .failed: return strings.voiceTutorAnswerFailed
                case .inactive, .submitted, .cancelled: break
                }
            }
            if let lessonStatus = lessonStatusText(strings) { return lessonStatus }
        }
        switch phase {
        case .idle, .requestingPermission, .connecting: return strings.voiceTutorCallConnecting
        case .listening:
            if inputNeedsRepeat { return strings.voiceTutorInputRepeat }
            return isAwaitingTutorResponse ? strings.voiceTutorCallThinking : strings.voiceTutorCallListening
        case .speaking: return strings.voiceTutorCallSpeaking
        case .ending: return strings.voiceTutorCallEnding
        case .ended: return strings.voiceTutorCallEnded
        case .failed:
            switch failureCause {
            case .provider: return strings.voiceTutorProviderCallFailed
            case .providerUnavailable: return strings.voiceTutorCallUnavailable
            case .updateRequired: return strings.updateRequired
            case .requestRejected: return strings.voiceTutorCallUnavailable
            case .connection, .none: return strings.voiceTutorCallFailed
            case .microphone, .audio, .localControl, .service, .unknown:
                return strings.voiceTutorCallEnded
            }
        }
    }

    private func lessonStatusText(_ strings: AppStrings) -> String? {
        switch lessonPhase {
        case .questionLoading: return strings.voiceTutorQuestionLoading
        case .questionGenerating: return strings.voiceTutorQuestionGenerating
        case .questionReady: return strings.voiceTutorQuestionReady
        case .questionReading: return strings.voiceTutorQuestionReading
        case .answering: return strings.voiceTutorAnswerListening
        case .answerFinalizing: return strings.voiceTutorAnswerFinalizing
        case .answerReview: return strings.voiceTutorAnswerReview
        case .answerSubmitting: return strings.voiceTutorAnswerSubmitting
        case .grading: return strings.voiceTutorAnswerGrading
        case .graded: return strings.voiceTutorAnswerGraded
        case .questionFailed: return strings.voiceTutorQuestionFailed
        case .gradingFailed: return strings.voiceTutorGradingFailed
        case .answerFailed: return strings.voiceTutorAnswerFailed
        case .ending: return strings.voiceTutorCallEnding
        case .ended: return strings.voiceTutorCallEnded
        case .failed: return strings.voiceTutorCallFailed
        case .conversation, nil: return nil
        }
    }

    func supplementaryError(_ strings: AppStrings, errorMessage: String?) -> String? {
        guard let error = errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines),
              !error.isEmpty else { return nil }
        if isMonthlyQuotaExhausted,
           error == strings.voiceTutorConnectionFailed || error == strings.voiceTutorQuotaReached {
            return nil
        }
        if showsConnectionFailure(strings, errorMessage: error),
           error == strings.voiceTutorConnectionFailed { return nil }
        return error
    }

    func needsVisibleStatus(_ strings: AppStrings, errorMessage: String?) -> Bool {
        guard phase == .listening || phase == .speaking else { return true }
        return pauseState.mode != .active || inputNeedsRepeat
            || isServerPaused || (lessonPhase != nil && lessonPhase != .conversation)
            || supplementaryError(strings, errorMessage: errorMessage) != nil
    }

    var isMonthlyQuotaExhausted: Bool {
        VoiceTutorServerEndReasonPolicy.isMonthlyQuotaExhausted(serverEndReason)
    }

    private var hasQuotaTerminalCountdown: Bool {
        isMonthlyQuotaExhausted && phase == .ending && sessionSecondsRemaining != nil
    }

    private var isQuotaNoticeUsingFinalSeconds: Bool {
        hasQuotaTerminalCountdown && (sessionSecondsRemaining ?? 0) > 0
    }
}

/// Shared scroll-edge rule for both the compact call and the integrated
/// full-screen transcript. Presentation changes never own call state.
struct VoiceTutorTranscriptInteraction {
    static func transcriptIsAtLatest(
        contentFrame: CGRect,
        viewportHeight: CGFloat,
        tolerance: CGFloat = 8
    ) -> Bool {
        guard contentFrame.isUsableForGestureRouting,
              viewportHeight.isFinite, viewportHeight > 0,
              tolerance.isFinite, tolerance >= 0 else { return false }
        return contentFrame.maxY <= viewportHeight + tolerance
    }
}

/// Keeps live captions pinned only while the learner is actually following the
/// newest turn. Appending text grows `height`/`maxY` before ScrollViewReader can
/// restore the bottom anchor, so edge distance alone cannot be treated as an
/// intentional departure. A move toward an older offset (`minY` increasing) is
/// the user-owned signal; this also observes VoiceOver scrolls that do not emit a
/// SwiftUI DragGesture.
struct VoiceTutorTranscriptFollowState: Equatable {
    static let offsetTolerance: CGFloat = 2

    private(set) var followsLatest = true
    private(set) var isUserInteracting = false
    private var lastContentMinY: CGFloat?

    var shouldAutoScrollForContentChange: Bool {
        followsLatest && !isUserInteracting
    }

    func shouldScheduleSettlement(isGestureActive: Bool) -> Bool {
        isUserInteracting && !isGestureActive
    }

    mutating func reset() {
        followsLatest = true
        isUserInteracting = false
        lastContentMinY = nil
    }

    mutating func beginUserInteraction() {
        isUserInteracting = true
    }

    mutating func observeLayout(contentFrame: CGRect, viewportHeight: CGFloat) {
        guard contentFrame.isUsableForGestureRouting,
              contentFrame.minY.isFinite,
              viewportHeight.isFinite, viewportHeight > 0 else { return }

        let previousMinY = lastContentMinY
        lastContentMinY = contentFrame.minY
        if VoiceTutorTranscriptInteraction.transcriptIsAtLatest(
            contentFrame: contentFrame,
            viewportHeight: viewportHeight
        ) {
            followsLatest = true
            return
        }

        // New caption/draft content changes height while preserving minY. Only
        // movement toward older content may suspend following.
        if let previousMinY,
           contentFrame.minY > previousMinY + Self.offsetTolerance {
            followsLatest = false
        }
    }

    @discardableResult
    mutating func endUserInteraction() -> Bool {
        isUserInteracting = false
        return followsLatest
    }
}

private extension CGRect {
    var isUsableForGestureRouting: Bool {
        !isNull && !isInfinite && width > 0 && height > 0
    }
}

private struct VoiceTutorTranscriptContentFramePreferenceKey: PreferenceKey {
    static let defaultValue = CGRect.null

    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if next.isUsableForGestureRouting {
            value = next
        }
    }
}

private struct VoiceTutorTranscriptViewportHeightPreferenceKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next.isFinite, next > 0 {
            value = next
        }
    }
}

private enum VoiceTutorOrbPlacement: Hashable {
    case call, transcript
}

private struct VoiceTutorOrbAnchorPreferenceKey: PreferenceKey {
    static let defaultValue: [VoiceTutorOrbPlacement: Anchor<CGRect>] = [:]

    static func reduce(
        value: inout [VoiceTutorOrbPlacement: Anchor<CGRect>],
        nextValue: () -> [VoiceTutorOrbPlacement: Anchor<CGRect>]
    ) {
        value.merge(nextValue(), uniquingKeysWith: { _, next in next })
    }
}

private struct VoiceTutorAnswerEditorSession: Identifiable {
    let id: String
}

/// A single native scrolling editor keeps caret movement and text selection out
/// of the transcript's drag gestures, clipping and automatic scroll updates.
struct VoiceTutorAnswerEditor: View {
    @Environment(\.dismiss) private var dismiss
    @FocusState private var isFocused: Bool
    let strings: AppStrings
    @Binding var text: String

    var body: some View {
        NavigationStack {
            ZStack(alignment: .topLeading) {
                if text.isEmpty {
                    Text(strings.voiceTutorAnswerPlaceholder)
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 8)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                TextEditor(text: $text)
                    .font(.body)
                    .scrollContentBackground(.hidden)
                    .scrollDismissesKeyboard(.never)
                    .focused($isFocused)
                    .accessibilityLabel(strings.voiceTutorAnswerEdit)
                    .accessibilityHint(strings.voiceTutorAnswerReviewHelp)
                    .accessibilityIdentifier("voiceCall.answerEditor")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(Color(uiColor: .systemBackground))
            .navigationTitle(strings.voiceTutorAnswerEdit)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.visible, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.done) {
                        isFocused = false
                        dismiss()
                    }
                    .accessibilityIdentifier("voiceCall.answerKeyboardDone")
                }
            }
            .task { isFocused = true }
        }
    }
}

/// The same non-networking surface is rendered by device visual tests.
struct VoiceTutorCallScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @ScaledMetric(relativeTo: .largeTitle) private var preferredOrbDiameter: CGFloat = 184
    @State private var orbPulseExpanded = false
    @State private var transcriptContentFrame = CGRect.null
    @State private var transcriptViewportHeight: CGFloat = 0
    @State private var transcriptFollowState = VoiceTutorTranscriptFollowState()
    @State private var transcriptAutoScrollTask: Task<Void, Never>?
    @State private var transcriptScrollAnimates = false
    @State private var transcriptScrollSettleTask: Task<Void, Never>?
    @GestureState private var transcriptDragIsActive = false
    @State private var transcriptExpansion: CGFloat?
    @State private var orbDragStartsExpanded: Bool?
    @State private var orbInteraction = VoiceTutorOrbInteractionState()
    @State private var orbHoldTask: Task<Void, Never>?
    @State private var didRequestEnd = false
    @State private var showsEndConfirmation = false
    @State private var expiredOperationSequence: Int64?
    @GestureState private var orbDragIsActive = false
    @State private var answerEditorSession: VoiceTutorAnswerEditorSession?
    let topic: String
    var discoveryPrompt: String? = nil
    let presentation: VoiceTutorCallPresentation
    let strings: AppStrings
    var operationState = VoiceTutorOperationState()
    var captions: [VoiceTutorCaption] = []
    var assistantTranscriptDraft = ""
    var errorMessage: String?
    @Binding var showsTranscript: Bool
    @Binding var showsSummary: Bool
    var onPause: () -> Void = {}
    var onEnd: () -> Void = {}
    var onRetry: () -> Void = {}
    var onDismiss: () -> Void = {}
    var onSummaryRefresh: () -> Void = {}
    var answerDraftState = VoiceTutorAnswerDraftState()
    var answerDraftText: Binding<String> = .constant("")
    var canSubmitAnswer = false
    var onFinishAnswer: () -> Void = {}
    var onSubmitAnswer: () -> Void = {}
    var canSkipAnswer = false
    var onSkipAnswer: () -> Void = {}

    private var usesAccessibilityChrome: Bool {
        VoiceTutorCallAdaptiveLayout.usesAccessibilityChrome(for: dynamicTypeSize)
    }

    private var displayTopic: String {
        let trimmed = topic.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? strings.voiceTutorDiscoveryTeacher : trimmed
    }

    private var expansion: CGFloat {
        transcriptExpansion ?? (showsTranscript ? 1 : 0)
    }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                ZStack {
                    compactCall(in: geometry)
                        .opacity(1 - expansion)
                        .allowsHitTesting(!showsTranscript && !orbDragIsActive)
                        .accessibilityHidden(showsTranscript)

                    fullScreenTranscript
                        .allowsHitTesting(showsTranscript && !orbDragIsActive)
                        .accessibilityHidden(!showsTranscript)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlayPreferenceValue(VoiceTutorOrbAnchorPreferenceKey.self) { anchors in
                    GeometryReader { orbGeometry in
                        if let compact = anchors[.call], let transcript = anchors[.transcript] {
                            let frame = VoiceTutorOrbGestureRouting.orbFrame(
                                from: orbGeometry[compact],
                                to: orbGeometry[transcript],
                                expansion: expansion
                            )
                            callOrb(diameter: frame.width)
                                .highPriorityGesture(orbTranscriptGesture(
                                    travel: min(360, max(180, orbGeometry.size.height * 0.55))
                                ))
                                .position(x: frame.midX, y: frame.midY)
                        }
                    }
                }
                .clipped()

                interactionDock
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
        }
        .background(Color(uiColor: .systemBackground))
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            updateOrbAnimation()
        }
        .onChange(of: presentation.orbState) { _, _ in
            updateOrbAnimation()
        }
        .onChange(of: reduceMotion) { _, _ in
            updateOrbAnimation()
        }
        .onChange(of: showsTranscript) { _, isShowingTranscript in
            withAnimation(disclosureAnimation) {
                transcriptExpansion = isShowingTranscript ? 1 : 0
            }
        }
        .onChange(of: answerDraftState.phase) { _, phase in
            cancelOrbInteraction()
            updateOrbAnimation()
            if phase == .review {
                setTranscriptExpanded(true)
            }
            if !answerDraftIsEditable { answerEditorSession = nil }
        }
        .onChange(of: answerDraftState.answerID) { _, _ in
            answerEditorSession = nil
        }
        .sheet(item: $answerEditorSession) { session in
            VoiceTutorAnswerEditor(strings: strings, text: Binding(
                get: { answerDraftState.answerID == session.id ? answerDraftText.wrappedValue : "" },
                set: { text in
                    guard answerDraftState.answerID == session.id, answerDraftIsEditable else { return }
                    answerDraftText.wrappedValue = text
                }
            ))
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationContentInteraction(.scrolls)
        }
        .onChange(of: orbDragIsActive) { _, isTouching in
            guard !isTouching else { return }
            // GestureState also resets on system cancellation. Let onEnded
            // consume a normal release before cleaning up an abandoned touch.
            Task { @MainActor in
                await Task.yield()
                guard !orbDragIsActive, orbInteraction.isActive else { return }
                cancelOrbInteraction()
            }
        }
        .onChange(of: presentation.phase) { oldPhase, newPhase in
            if !newPhase.isLive {
                answerEditorSession = nil
                showsEndConfirmation = false
                cancelOrbInteraction()
            }
            if !oldPhase.isLive && newPhase.isLive { didRequestEnd = false }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active {
                showsEndConfirmation = false
                cancelOrbInteraction()
            }
        }
        .onChange(of: captions.isEmpty) { _, isEmpty in
            if isEmpty { resetTranscriptInteraction() }
        }
        .onDisappear {
            cancelOrbInteraction()
            transcriptAutoScrollTask?.cancel()
            transcriptAutoScrollTask = nil
            transcriptScrollSettleTask?.cancel()
            transcriptScrollSettleTask = nil
        }
        .accessibilityAction(
            named: Text(showsTranscript ? strings.voiceTutorCallCollapseConversation : strings.voiceTutorCallRevealConversation)
        ) {
            setTranscriptExpanded(!showsTranscript)
        }
        .confirmationDialog(
            strings.voiceTutorOrbEndConfirmation,
            isPresented: $showsEndConfirmation,
            titleVisibility: .visible
        ) {
            Button(strings.voiceTutorCallEnd, role: .destructive) { requestEndFromOrb() }
            Button(strings.cancel, role: .cancel) {}
        }
    }

    private func compactCall(in geometry: GeometryProxy) -> some View {
        ScrollView {
            VStack(spacing: 0) {
                VStack(spacing: 6) {
                    Text(displayTopic)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    callTime
                }
                .multilineTextAlignment(.center)
                .padding(.top, 18)

                Spacer(minLength: 44)
                VStack(spacing: 28) {
                    orbPlaceholder(.call, diameter: compactOrbDiameter(in: geometry))
                    callNotices
                    if hasAnswerDraft { answerDraftPreview }
                }
                .frame(maxWidth: .infinity)
                Spacer(minLength: 44)

                summaryRow
                if showsSummary && presentation.summaryState == .ready {
                    VoiceTutorResultSections(detail: presentation.detail, strings: strings)
                }
            }
            .padding(.horizontal, 28)
            .padding(.bottom, 24)
            .frame(maxWidth: .infinity, minHeight: max(320, geometry.size.height))
        }
        .scrollBounceBehavior(.basedOnSize)
        .accessibilityIdentifier("voiceCall.callView")
    }

    private var fullScreenTranscript: some View {
        VStack(spacing: 0) {
            transcriptHeader
                .opacity(expansion)

            Divider()
                .opacity(expansion)

            GeometryReader { geometry in
                transcriptPanel
                    .offset(y: reduceMotion ? 0 : geometry.size.height * (1 - expansion))
                    .opacity(min(1, expansion * 2))
            }
            .clipped()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemBackground).opacity(expansion))
        .accessibilityIdentifier("voiceCall.fullTranscript")
    }

    private var transcriptHeader: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                transcriptReturnButton
                if !usesAccessibilityChrome {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(displayTopic)
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                            .fixedSize(horizontal: false, vertical: true)
                        transcriptStatus
                    }
                }
                Spacer(minLength: 0)
                orbPlaceholder(.transcript, diameter: hasAnswerDraft ? (usesAccessibilityChrome ? 96 : 64) : (usesAccessibilityChrome ? 56 : 48))
            }
            if usesAccessibilityChrome {
                Text(strings.voiceTutorCallConversationAction)
                    .font(.headline)
                    .fixedSize(horizontal: false, vertical: true)
                transcriptStatus
            }
            HStack(spacing: 12) {
                callTime
                if presentation.isRecording { recordingIndicator }
            }
            operationStatus
            if answerCaptureIsListening, orbInteraction.stage == .idle {
                answerCaptureHelp
            }
            if orbInteraction.stage == .warning {
                Text(strings.voiceTutorOrbReleaseCancels)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if presentation.phase.isLive && presentation.pauseState.holdsMicrophone {
                Text(strings.voiceTutorPauseUsesTime)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 16)
    }

    private var transcriptReturnButton: some View {
        Button {
            setTranscriptExpanded(false)
        } label: {
            Image(systemName: "chevron.left")
                .font(.body.weight(.medium))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.secondary)
        .accessibilityLabel(strings.voiceTutorCallCollapseConversation)
        .accessibilityIdentifier("voiceCall.collapseTranscript")
    }

    private var transcriptStatus: some View {
        Text(orbStatusText)
            .font(.caption)
            .foregroundStyle(orbStatusColor)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("voiceCall.status")
    }

    private func callOrb(diameter: CGFloat) -> some View {
        orbVisual(diameter: diameter)
            .accessibilityElement(children: .ignore)
            .accessibilityAddTraits(.isButton)
            .accessibilityLabel(hasAnswerDraft || presentation.showsPauseControl ? orbActionLabel : strings.voiceTutorTeacher)
            .accessibilityValue(orbAccessibilityValue)
            .accessibilityHint(orbActionHint)
            .accessibilityAddTraits(presentation.pauseState.holdsMicrophone ? .isSelected : [])
            .accessibilityIdentifier("voiceCall.orb")
            .accessibilityAction {
                performOrbPrimaryAction()
            }
            .accessibilityAction(named: Text(orbActionLabel)) {
                performOrbPrimaryAction()
            }
            .accessibilityAction(
                named: Text(showsTranscript ? strings.voiceTutorCallCollapseConversation : strings.voiceTutorCallRevealConversation)
            ) {
                setTranscriptExpanded(!showsTranscript)
            }
            .accessibilityAction(named: Text(strings.voiceTutorCallEnd)) {
                guard presentation.primaryAction == .end, !didRequestEnd else { return }
                cancelOrbInteraction()
                showsEndConfirmation = true
            }
    }

    private func orbPlaceholder(_ placement: VoiceTutorOrbPlacement, diameter: CGFloat) -> some View {
        Color.clear
            .frame(width: diameter, height: diameter)
            .anchorPreference(key: VoiceTutorOrbAnchorPreferenceKey.self, value: .bounds) { [placement: $0] }
            .accessibilityHidden(true)
    }

    private func orbVisual(diameter: CGFloat) -> some View {
        Circle()
            .fill(
                RadialGradient(
                    colors: [orbSurfaceColor, orbSurfaceColor.opacity(0.85)],
                    center: .topLeading,
                    startRadius: 0,
                    endRadius: diameter
                )
            )
            .overlay {
                if answerCaptureIsListening, orbInteraction.stage == .idle {
                    Circle()
                        .stroke(answerCaptureAccent.opacity(0.5), lineWidth: 1.5)
                        .padding(-8)
                } else if orbInteraction.stage == .warning || orbInteraction.stage == .committed {
                    Circle()
                        .trim(from: 0, to: orbInteraction.progress)
                        .stroke(Color.red, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .padding(-7)
                }
            }
            .overlay {
                if hasAnswerDraft, presentation.canDisplayActiveAnswer, orbInteraction.stage == .idle {
                    if answerDraftIsBusy {
                        ProgressView()
                            .tint(Color.black.opacity(0.75))
                    } else if let label = answerOrbLabel {
                        VStack(spacing: diameter < 112 ? 3 : 10) {
                            Image(systemName: answerCaptureIsListening ? "mic.fill" : "mic.slash.fill")
                                .font(diameter < 112 ? .caption2 : .title3)
                            Text(label)
                                .font(diameter < 112 ? .caption2.weight(.semibold) : .headline)
                                .opacity(answerDraftState.phase == .listening || canSubmitAnswer ? 1 : 0.45)
                                .multilineTextAlignment(.center)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .foregroundStyle(Color.black.opacity(0.8))
                        .padding(.horizontal, diameter < 112 ? 7 : 20)
                    }
                } else if orbInteraction.stage == .idle, let symbol = presentation.lessonSymbolName {
                    Image(systemName: symbol)
                        .font(diameter < 112 ? .caption : .title2.weight(.medium))
                        .foregroundStyle(Color.black.opacity(0.75))
                        .accessibilityHidden(true)
                }
            }
            .frame(width: diameter, height: diameter)
            .scaleEffect(orbScale)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: orbInteraction.stage)
            .contentShape(Circle())
    }

    private var orbSurfaceColor: Color {
        if orbInteraction.stage == .warning || orbInteraction.stage == .committed {
            return colorScheme == .dark
                ? Color(red: 0.77, green: 0.51, blue: 0.49)
                : Color(red: 0.78, green: 0.51, blue: 0.49)
        }
        if presentation.orbState == .paused || presentation.orbState == .pausing {
            return Color.secondary.opacity(colorScheme == .dark ? 0.45 : 0.22)
        }
        if answerCaptureIsListening {
            return colorScheme == .dark
                ? Color(red: 0.94, green: 0.73, blue: 0.39)
                : Color(red: 0.90, green: 0.68, blue: 0.33)
        }
        if !hasAnswerDraft {
            switch presentation.orbState {
            case .thinking:
                return colorScheme == .dark
                    ? Color(red: 0.72, green: 0.76, blue: 0.88)
                    : Color(red: 0.63, green: 0.69, blue: 0.83)
            case .capturingAnswer:
                return colorScheme == .dark
                    ? Color(red: 0.94, green: 0.73, blue: 0.39)
                    : Color(red: 0.90, green: 0.68, blue: 0.33)
            case .learningFailed:
                return Color(red: 0.85, green: 0.63, blue: 0.55)
            default: break
            }
        }
        return colorScheme == .dark
            ? Color(red: 0.77, green: 0.86, blue: 0.84)
            : Color(red: 0.65, green: 0.78, blue: 0.75)
    }

    private var orbScale: CGFloat {
        switch orbInteraction.stage {
        case .anticipating: return reduceMotion ? 1 : 0.97
        case .warning, .committed: return reduceMotion ? 1 : 0.92
        case .idle:
            guard !hasAnswerDraft, presentation.orbAnimates, !reduceMotion else { return 1 }
            return orbPulseExpanded ? (presentation.orbState == .speaking ? 1.04 : 1.015) : 1
        }
    }

    private var orbStatusText: String {
        if orbInteraction.stage == .warning { return strings.voiceTutorOrbKeepHoldingToEnd }
        if orbInteraction.stage == .committed { return strings.voiceTutorCallEnding }
        return presentation.statusText(
            strings, errorMessage: errorMessage, answerDraftState: answerDraftState
        )
    }

    private var orbStatusColor: Color {
        if orbInteraction.stage == .warning { return .red }
        return answerCaptureIsListening ? answerCaptureAccent : .secondary
    }

    private var answerCaptureAccent: Color {
        colorScheme == .dark
            ? Color(red: 0.94, green: 0.73, blue: 0.39)
            : Color(red: 0.52, green: 0.31, blue: 0.08)
    }

    private var answerCaptureHelp: some View {
        Text(strings.voiceTutorAnswerCaptureHelp)
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("voiceCall.answerCaptureHelp")
    }

    private var callNotices: some View {
        VStack(spacing: 12) {
            Text(orbStatusText)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(orbStatusColor)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("voiceCall.status")

            operationStatus

            if answerCaptureIsListening, orbInteraction.stage == .idle {
                answerCaptureHelp
                    .multilineTextAlignment(.center)
            }

            if orbInteraction.stage == .warning {
                Text(strings.voiceTutorOrbReleaseCancels)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            } else if presentation.phase == .failed || presentation.phase == .ended {
                Text(callExplanation)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                supplementaryErrorNotice
            }

            if presentation.isRecording { recordingIndicator }
            if presentation.phase.isLive && presentation.pauseState.holdsMicrophone {
                Text(strings.voiceTutorPauseUsesTime)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("voiceCall.pauseUsesTime")
            }
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var operationStatus: some View {
        if presentation.phase.isLive,
           !operationState.active.isEmpty || (operationState.latestFinished != nil
               && operationState.latestFinished?.event.sequence != expiredOperationSequence) {
            TimelineView(.animation(minimumInterval: 0.1,
                                    paused: scenePhase != .active || operationState.active.isEmpty)) { _ in
                let uptime = ProcessInfo.processInfo.systemUptime
                VStack(alignment: .leading, spacing: 3) {
                    ForEach(operationState.visibleEntries(at: uptime)) { entry in
                        Text(strings.voiceTutorOperationStatus(
                            name: entry.event.name,
                            phase: entry.event.phase,
                            elapsedMilliseconds: entry.elapsedMilliseconds(at: uptime)
                        ))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("voiceCall.operationStatus")
                    }
                }
            }
            .task(id: operationState.latestFinished?.event.sequence) {
                guard let finished = operationState.latestFinished else {
                    expiredOperationSequence = nil
                    return
                }
                let remaining = max(0, 5 - (ProcessInfo.processInfo.systemUptime - finished.receivedAt))
                do { try await Task.sleep(for: .seconds(remaining)) } catch { return }
                expiredOperationSequence = finished.event.sequence
            }
        }
    }

    private var recordingIndicator: some View {
        Label(strings.voiceTutorCallRecording, systemImage: "record.circle.fill")
            .font(.caption.weight(.medium))
            .foregroundStyle(.red)
            .accessibilityIdentifier("voiceCall.recording")
    }

    private var hasAnswerDraft: Bool {
        switch answerDraftState.phase {
        case .listening, .finalizing, .review, .submitting, .failed: return true
        case .inactive, .submitted, .cancelled: return false
        }
    }

    private var answerCaptureIsListening: Bool {
        presentation.canDisplayActiveAnswer && answerDraftState.phase == .listening
    }

    private var answerDraftIsEditable: Bool {
        presentation.phase.isLive && (answerDraftState.phase == .listening
            || answerDraftState.phase == .review || answerDraftState.phase == .failed)
    }

    private var answerDraftIsBusy: Bool {
        answerDraftState.phase == .finalizing || answerDraftState.phase == .submitting
    }

    private var answerOrbLabel: String? {
        guard presentation.canDisplayActiveAnswer else { return nil }
        switch answerDraftState.phase {
        case .listening: return strings.voiceTutorAnswerFinish
        case .review, .failed: return strings.voiceTutorAnswerSubmit
        default: return nil
        }
    }

    private var answerDraftStatus: String {
        switch answerDraftState.phase {
        case .listening: return strings.voiceTutorAnswerListening
        case .finalizing: return strings.voiceTutorAnswerFinalizing
        case .review: return strings.voiceTutorAnswerReview
        case .submitting: return strings.voiceTutorAnswerSubmitting
        case .failed: return strings.voiceTutorAnswerFailed
        case .inactive, .submitted, .cancelled:
            return presentation.statusText(strings, errorMessage: errorMessage)
        }
    }

    private func openAnswerEditor() {
        guard answerDraftIsEditable, let answerID = answerDraftState.answerID else { return }
        transcriptAutoScrollTask?.cancel()
        transcriptAutoScrollTask = nil
        transcriptScrollSettleTask?.cancel()
        transcriptScrollSettleTask = nil
        transcriptScrollAnimates = false
        answerEditorSession = VoiceTutorAnswerEditorSession(id: answerID)
    }

    private var answerDraftPreview: some View {
        Button {
            if answerDraftIsEditable {
                openAnswerEditor()
            } else {
                setTranscriptExpanded(true)
            }
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    Text(strings.voiceTutorAnswerDraftTitle)
                    Spacer(minLength: 8)
                    Image(systemName: answerDraftIsEditable ? "pencil" : "chevron.right")
                }
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                Text(answerDraftText.wrappedValue.isEmpty
                     ? strings.voiceTutorAnswerPlaceholder : answerDraftText.wrappedValue)
                    .font(.subheadline)
                    .foregroundStyle(answerDraftText.wrappedValue.isEmpty ? .secondary : .primary)
                    .lineLimit(3)
                    .multilineTextAlignment(.leading)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.secondary.opacity(0.065), in: RoundedRectangle(cornerRadius: 16))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(strings.voiceTutorAnswerEdit)
        .accessibilityValue(answerDraftText.wrappedValue)
        .accessibilityIdentifier("voiceCall.answerPreview")
    }

    private var answerDraftCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: openAnswerEditor) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        Text(strings.voiceTutorAnswerDraftTitle)
                            .font(.subheadline.weight(.semibold))
                        Spacer(minLength: 8)
                        if answerDraftIsEditable {
                            Label(strings.voiceTutorAnswerEdit, systemImage: "pencil")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(voiceAccent)
                        }
                    }
                    Text(answerDraftText.wrappedValue.isEmpty
                         ? strings.voiceTutorAnswerPlaceholder : answerDraftText.wrappedValue)
                        .font(.body)
                        .foregroundStyle(answerDraftText.wrappedValue.isEmpty ? .secondary : .primary)
                        .lineLimit(6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .multilineTextAlignment(.leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!answerDraftIsEditable)
            .accessibilityLabel(strings.voiceTutorAnswerEdit)
            .accessibilityValue(answerDraftText.wrappedValue)
            .accessibilityIdentifier("voiceCall.answerEdit")
            if answerDraftState.phase != .listening {
                Text(strings.voiceTutorAnswerReviewHelp)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if answerDraftState.phase == .failed {
                Text(answerDraftState.failureCode == "ANSWER_TOO_LONG"
                     ? strings.voiceTutorAnswerTooLong : strings.voiceTutorAnswerFailedHelp)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if answerDraftState.phase == .review || answerDraftState.phase == .failed {
                Button {
                    performOrbPrimaryAction()
                } label: {
                    Text(strings.voiceTutorAnswerSubmit)
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                .tint(voiceAccent)
                .disabled(!canSubmitAnswer || !presentation.phase.isLive)
                .accessibilityIdentifier("voiceCall.answerSubmit")
            } else if answerDraftIsBusy {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(answerDraftStatus).font(.caption)
                }
                .foregroundStyle(.secondary)
            }
            if canSkipAnswer {
                Button(strings.voiceTutorAnswerSkip) {
                    answerEditorSession = nil
                    onSkipAnswer()
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(minHeight: 44)
                .accessibilityIdentifier("voiceCall.answerSkip")
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.065), in: RoundedRectangle(cornerRadius: 16))
        .id("voiceCall.answerDraft")
        .accessibilityIdentifier("voiceCall.answerCard")
    }

    @ViewBuilder
    private var supplementaryErrorNotice: some View {
        if let error = presentation.supplementaryError(strings, errorMessage: errorMessage),
           error != callExplanation {
            Text(error)
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 12))
                .accessibilityIdentifier("voiceCall.error")
        }
    }

    private var hasTutorReply: Bool {
        captions.contains { $0.speaker == .tutor } || !assistantTranscriptDraft.isEmpty
            || presentation.summaryState == .ready
    }

    private var callExplanation: String {
        if presentation.isMonthlyQuotaExhausted,
           presentation.phase == .ended || presentation.phase == .failed {
            return strings.voiceTutorQuotaReached
        }
        switch presentation.orbState {
        case .connecting:
            return strings.voiceTutorCallConnectingHelp
        case .thinking:
            return hasTutorReply ? strings.voiceTutorCallThinkingHelp : strings.voiceTutorCallFirstReplyHelp
        case .listening:
            return presentation.inputNeedsRepeat ? strings.voiceTutorCallListeningHelp
                : discoveryPrompt ?? strings.voiceTutorCallListeningHelp
        case .speaking:
            return strings.voiceTutorCallSpeakingHelp
        case .capturingAnswer:
            return strings.voiceTutorAnswerCaptureHelp
        case .reviewingAnswer:
            return strings.voiceTutorAnswerReviewHelp
        case .questionReady, .graded, .learningFailed:
            return presentation.statusText(strings)
        case .pausing:
            return strings.voiceTutorCallPausingHelp
        case .resuming:
            return strings.voiceTutorCallResumingHelp
        case .paused:
            return strings.voiceTutorCallPausedHelp
        case .ending:
            return strings.voiceTutorCallEndingHelp
        case .ended:
            return captions.isEmpty ? strings.voiceTutorCallEndedWithoutCaptions : strings.voiceTutorCallEndedHelp
        case .failed:
            if presentation.primaryAction != .retry {
                return presentation.supplementaryError(strings, errorMessage: errorMessage)
                    ?? strings.voiceTutorCallInterruptedHelp
            }
            return hasTutorReply ? strings.voiceTutorCallInterruptedHelp : strings.voiceTutorCallNoReplyHelp
        }
    }

    private var callTime: some View {
        Group {
            switch presentation.remainingTime {
            case .call(let seconds):
                Label(strings.voiceTutorCallRemaining(seconds), systemImage: "clock")
                    .accessibilityIdentifier("voiceCall.remainingTime")
            case .monthly(let seconds):
                Label(strings.voiceTutorCallMonthlyRemaining(seconds), systemImage: "clock")
                    .accessibilityIdentifier("voiceCall.remainingTime")
            case .monthlyUnlimited:
                Label(strings.voiceTutorUnlimited, systemImage: "infinity")
                    .accessibilityIdentifier("voiceCall.remainingTime")
            case nil:
                EmptyView()
            }
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
    }

    private var transcriptPanel: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 24) {
                    if usesAccessibilityChrome {
                        Text(displayTopic)
                            .font(.headline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if captions.isEmpty && assistantTranscriptDraft.isEmpty, !hasAnswerDraft,
                       presentation.phase != .failed && presentation.phase != .ended {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(strings.voiceTutorCallEmptyConversation)
                                .font(.headline)
                                .foregroundStyle(.primary)
                            Text(callExplanation)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.vertical, 20)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    ForEach(captions) { caption in
                        VoiceTutorCaptionBubble(caption: caption, strings: strings)
                    }
                    if hasAnswerDraft { answerDraftCard }
                    if !assistantTranscriptDraft.isEmpty {
                        VoiceTutorCaptionBubble(
                            caption: VoiceTutorCaption(speaker: .tutor, text: assistantTranscriptDraft),
                            strings: strings
                        )
                    } else if presentation.orbState == .thinking && !captions.isEmpty && !hasAnswerDraft
                        && (presentation.lessonPhase == nil || presentation.lessonPhase == .conversation) {
                        Label(strings.voiceTutorCallThinking, systemImage: "ellipsis")
                            .font(.subheadline)
                            .foregroundStyle(voiceAccent)
                            .padding(.vertical, 8)
                            .accessibilityIdentifier("voiceCall.pendingReply")
                    }
                    if presentation.phase == .failed || presentation.phase == .ended {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(callExplanation)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            supplementaryErrorNotice
                            summaryRow
                            if showsSummary && presentation.summaryState == .ready {
                                VoiceTutorResultSections(detail: presentation.detail, strings: strings)
                            }
                        }
                        .padding(16)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.secondary.opacity(0.055), in: RoundedRectangle(cornerRadius: 16))
                        .accessibilityIdentifier("voiceCall.conversationNotice")
                    }
                    Color.clear.frame(height: 1).id("voiceCall.latestCaption")
                }
                .padding(.vertical, 24)
                .padding(.horizontal, 24)
                .background {
                    GeometryReader { contentGeometry in
                        Color.clear.preference(
                            key: VoiceTutorTranscriptContentFramePreferenceKey.self,
                            value: contentGeometry.frame(in: .named(transcriptScrollCoordinateSpace))
                        )
                    }
                }
            }
            .coordinateSpace(name: transcriptScrollCoordinateSpace)
            .background {
                GeometryReader { viewportGeometry in
                    Color.clear.preference(
                        key: VoiceTutorTranscriptViewportHeightPreferenceKey.self,
                        value: viewportGeometry.size.height
                    )
                }
            }
            .simultaneousGesture(transcriptFollowGesture, including: .all)
            .onAppear {
                transcriptFollowState.reset()
                scheduleTranscriptAutoScroll(using: proxy)
            }
            .onChange(of: captions.last?.id) { _, _ in
                scheduleTranscriptAutoScroll(using: proxy, animated: true)
            }
            .onChange(of: assistantTranscriptDraft) { _, _ in
                scheduleTranscriptAutoScroll(using: proxy)
            }
            .onChange(of: answerDraftText.wrappedValue) { _, _ in
                scheduleTranscriptAutoScroll(using: proxy)
            }
            .onChange(of: showsTranscript) { _, isShowingTranscript in
                if isShowingTranscript { scheduleTranscriptAutoScroll(using: proxy) }
            }
            .onChange(of: transcriptDragIsActive) { wasActive, isActive in
                guard wasActive, !isActive else { return }
                observeTranscriptLayout()
                scheduleTranscriptScrollSettlement(using: proxy)
            }
            .onPreferenceChange(VoiceTutorTranscriptContentFramePreferenceKey.self) { frame in
                let contentHeightChanged = abs(frame.height - transcriptContentFrame.height) > 1
                transcriptContentFrame = frame
                observeTranscriptLayout()
                if contentHeightChanged { scheduleTranscriptAutoScroll(using: proxy) }
                if transcriptFollowState.shouldScheduleSettlement(
                    isGestureActive: transcriptDragIsActive
                ) {
                    scheduleTranscriptScrollSettlement(using: proxy)
                }
            }
            .onPreferenceChange(VoiceTutorTranscriptViewportHeightPreferenceKey.self) { height in
                transcriptViewportHeight = height
                observeTranscriptLayout()
                scheduleTranscriptAutoScroll(using: proxy)
                if transcriptFollowState.shouldScheduleSettlement(
                    isGestureActive: transcriptDragIsActive
                ) {
                    scheduleTranscriptScrollSettlement(using: proxy)
                }
            }
        }
        .accessibilityIdentifier("voiceCall.transcript")
    }

    private var transcriptFollowGesture: some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .named(transcriptScrollCoordinateSpace))
            .updating($transcriptDragIsActive) { _, isActive, _ in
                isActive = true
            }
            .onChanged { _ in
                if !transcriptFollowState.isUserInteracting {
                    observeTranscriptLayout()
                    transcriptFollowState.beginUserInteraction()
                }
                transcriptAutoScrollTask?.cancel()
                transcriptAutoScrollTask = nil
                transcriptScrollAnimates = false
                transcriptScrollSettleTask?.cancel()
                transcriptScrollSettleTask = nil
            }
            .onEnded { _ in
                observeTranscriptLayout()
            }
    }

    private func observeTranscriptLayout() {
        transcriptFollowState.observeLayout(
            contentFrame: transcriptContentFrame,
            viewportHeight: transcriptViewportHeight
        )
    }

    private func scheduleTranscriptAutoScroll(using proxy: ScrollViewProxy, animated: Bool = false) {
        // The mounted, hidden transcript follows too, so the first drag already
        // reveals the latest turn instead of jumping there only after release.
        guard answerEditorSession == nil, transcriptFollowState.shouldAutoScrollForContentChange else { return }
        transcriptScrollAnimates = transcriptScrollAnimates || (animated && showsTranscript)
        transcriptAutoScrollTask?.cancel()
        transcriptAutoScrollTask = Task { @MainActor in
            // Caption mutations arrive before SwiftUI has necessarily laid out
            // the new bottom anchor. Wait one main-actor turn, then re-check the
            // learner's position so a concurrent scroll toward history wins.
            await Task.yield()
            guard !Task.isCancelled, answerEditorSession == nil,
                  transcriptFollowState.shouldAutoScrollForContentChange else { return }
            let animate = transcriptScrollAnimates && !reduceMotion
            transcriptScrollAnimates = false
            withAnimation(animate ? .easeOut(duration: 0.18) : nil) {
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
        }
    }

    private func scheduleTranscriptScrollSettlement(using proxy: ScrollViewProxy) {
        guard answerEditorSession == nil, transcriptFollowState.shouldScheduleSettlement(
            isGestureActive: transcriptDragIsActive
        ) else { return }
        transcriptScrollSettleTask?.cancel()
        transcriptScrollSettleTask = Task { @MainActor in
            do {
                try await Task.sleep(nanoseconds: 180_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled, answerEditorSession == nil else { return }
            guard transcriptFollowState.shouldScheduleSettlement(
                isGestureActive: transcriptDragIsActive
            ) else {
                transcriptScrollSettleTask = nil
                return
            }
            observeTranscriptLayout()
            let shouldRestoreLatest = transcriptFollowState.endUserInteraction()
            transcriptScrollSettleTask = nil
            if shouldRestoreLatest {
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
        }
    }

    @ViewBuilder
    private var interactionDock: some View {
        if presentation.primaryAction == .retry || presentation.primaryAction == .dismiss {
            stableCallControls
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
                .background(Color(uiColor: .systemBackground))
        }
    }

    @ViewBuilder
    private var stableCallControls: some View {
        switch presentation.primaryAction {
        case .retry, .dismiss: terminalControls
        case .end, .wait: EmptyView()
        }
    }

    @ViewBuilder
    private var terminalControls: some View {
        switch presentation.primaryAction {
        case .retry:
            responsiveControlLayout {
                primaryControl(
                    title: strings.voiceTutorCallReconnect,
                    symbol: "arrow.clockwise",
                    tint: voiceAccent,
                    identifier: "voiceCall.retry",
                    action: onRetry
                )
                neutralControl(
                    title: strings.done,
                    symbol: "checkmark",
                    identifier: "voiceCall.done",
                    action: onDismiss
                )
            }
        case .dismiss:
            primaryControl(
                title: strings.done,
                symbol: "checkmark",
                tint: voiceAccent,
                identifier: "voiceCall.done",
                action: onDismiss
            )
        case .end, .wait:
            EmptyView()
        }
    }

    private func responsiveControlLayout<Content: View>(
        @ViewBuilder content: () -> Content
    ) -> some View {
        let layout = usesAccessibilityChrome
            ? AnyLayout(VStackLayout(spacing: 10))
            : AnyLayout(HStackLayout(spacing: 12))
        return layout { content() }
            .frame(maxWidth: .infinity)
    }

    private func neutralControl(
        title: String,
        symbol: String,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 48)
                .padding(.horizontal, 12)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 15))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.primary)
        .accessibilityIdentifier(identifier)
    }

    private func primaryControl(
        title: String,
        symbol: String,
        tint: Color,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 48)
                .padding(.horizontal, 12)
                .background(tint, in: RoundedRectangle(cornerRadius: 15))
        }
        .buttonStyle(.plain)
        .foregroundStyle(colorScheme == .dark ? Color.black : Color.white)
        .accessibilityIdentifier(identifier)
    }

    private var transcriptScrollCoordinateSpace: String {
        "voiceTutorCall.transcriptScroll"
    }

    private func setTranscriptExpanded(_ expanded: Bool) {
        orbDragStartsExpanded = nil
        withAnimation(disclosureAnimation) {
            showsTranscript = expanded
            transcriptExpansion = expanded ? 1 : 0
        }
    }

    private var disclosureAnimation: Animation? {
        reduceMotion ? nil : .interactiveSpring(response: 0.34, dampingFraction: 1)
    }

    private func resetTranscriptInteraction() {
        transcriptAutoScrollTask?.cancel()
        transcriptAutoScrollTask = nil
        transcriptScrollAnimates = false
        transcriptScrollSettleTask?.cancel()
        transcriptScrollSettleTask = nil
        transcriptFollowState.reset()
        transcriptContentFrame = .null
        transcriptViewportHeight = 0
    }

    private func compactOrbDiameter(in geometry: GeometryProxy) -> CGFloat {
        let available = min(geometry.size.width - 112, geometry.size.height * 0.3)
        return max(112, min(preferredOrbDiameter, 192, available))
    }

    private func orbTranscriptGesture(travel: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .global)
            .updating($orbDragIsActive) { _, isActive, transaction in
                transaction.animation = nil
                isActive = true
            }
            .onChanged { value in
                guard scenePhase == .active else { return }
                if !orbInteraction.isActive {
                    orbInteraction.begin(at: ProcessInfo.processInfo.systemUptime)
                    startOrbHoldTimer()
                }
                guard !orbInteraction.didCommitEnd else { return }
                orbInteraction.move(translation: value.translation)
                guard orbInteraction.hasMoved, !orbInteraction.isCancelled else { return }
                orbHoldTask?.cancel()
                orbHoldTask = nil
                guard abs(value.translation.height) >= abs(value.translation.width)
                        * VoiceTutorOrbGestureRouting.verticalDominanceRatio else { return }
                let startsExpanded = orbDragStartsExpanded ?? showsTranscript
                orbDragStartsExpanded = startsExpanded
                var transaction = Transaction()
                transaction.animation = nil
                withTransaction(transaction) {
                    transcriptExpansion = VoiceTutorOrbGestureRouting.expansion(
                        for: value.translation,
                        startsExpanded: startsExpanded,
                        travel: travel
                    )
                }
            }
            .onEnded { value in
                orbHoldTask?.cancel()
                orbHoldTask = nil
                orbInteraction.move(translation: value.translation)
                let releaseAction = orbInteraction.isActive && !orbInteraction.isCancelled && !orbInteraction.didCommitEnd
                    ? VoiceTutorOrbGestureRouting.releaseAction(
                        for: value.translation,
                        predictedTranslation: value.predictedEndTranslation,
                        showsTranscript: showsTranscript
                    ) : nil
                let startsExpanded = orbDragStartsExpanded ?? (releaseAction != nil ? showsTranscript : nil)
                let shouldActivate = orbInteraction.release(at: ProcessInfo.processInfo.systemUptime)
                if let startsExpanded {
                    setTranscriptExpanded(VoiceTutorOrbGestureRouting.settlesExpanded(
                        translation: value.translation,
                        predictedTranslation: value.predictedEndTranslation,
                        startsExpanded: startsExpanded
                    ))
                } else if shouldActivate {
                    performOrbPrimaryAction()
                }
            }
    }

    private func startOrbHoldTimer() {
        orbHoldTask?.cancel()
        guard presentation.primaryAction == .end, !didRequestEnd,
              let startedAt = orbInteraction.startedAt else { return }
        orbHoldTask = Task { @MainActor in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(32)) }
                catch { return }
                guard !Task.isCancelled, scenePhase == .active,
                      orbInteraction.startedAt == startedAt,
                      !orbInteraction.hasMoved, !orbInteraction.isCancelled,
                      presentation.primaryAction == .end, !didRequestEnd else { return }
                let previousStage = orbInteraction.stage
                let shouldEnd = orbInteraction.advance(
                    to: ProcessInfo.processInfo.systemUptime,
                    canEnd: presentation.primaryAction == .end
                )
                if orbInteraction.stage != previousStage {
                    UIImpactFeedbackGenerator(
                        style: orbInteraction.stage == .anticipating ? .soft : .medium
                    ).impactOccurred()
                }
                if shouldEnd {
                    requestEndFromOrb()
                    return
                }
            }
        }
    }

    private func requestEndFromOrb() {
        guard presentation.primaryAction == .end, !didRequestEnd else { return }
        didRequestEnd = true
        orbHoldTask?.cancel()
        orbHoldTask = nil
        onEnd()
    }

    private func cancelOrbInteraction() {
        orbHoldTask?.cancel()
        orbHoldTask = nil
        orbInteraction.cancel()
        if !orbDragIsActive { orbInteraction = VoiceTutorOrbInteractionState() }
        if orbDragStartsExpanded != nil { setTranscriptExpanded(showsTranscript) }
    }

    private var voiceAccent: Color {
        colorScheme == .dark
            ? Color(red: 0.42, green: 0.82, blue: 0.77)
            : Color(red: 0.04, green: 0.43, blue: 0.40)
    }

    private var orbActionLabel: String {
        if let answerOrbLabel { return answerOrbLabel }
        if hasAnswerDraft, presentation.pauseState.mode == .active { return answerDraftStatus }
        return orbRepresentsResume ? strings.voiceTutorResumeLesson : strings.voiceTutorTakeBreak
    }

    private var orbActionHint: String {
        let tapHint: String
        if answerOrbLabel != nil {
            tapHint = answerDraftState.phase == .listening
                ? strings.voiceTutorAnswerFinishHint : strings.voiceTutorAnswerSubmitHint
        } else {
            tapHint = orbRepresentsResume ? strings.voiceTutorOrbResumeHint : strings.voiceTutorOrbPauseHint
        }
        return "\(tapHint) \(orbTranscriptGestureHint) \(strings.voiceTutorOrbHoldToEndHint)"
    }

    private func performOrbPrimaryAction() {
        guard presentation.phase.isLive, !didRequestEnd else { return }
        if hasAnswerDraft, presentation.pauseState.mode == .active {
            switch answerDraftState.phase {
            case .listening:
                answerEditorSession = nil
                onFinishAnswer()
            case .review, .failed:
                guard canSubmitAnswer else { return }
                answerEditorSession = nil
                onSubmitAnswer()
            default: break
            }
            return
        }
        guard presentation.canChangePause else { return }
        onPause()
    }

    private var orbTranscriptGestureHint: String {
        showsTranscript
            ? strings.voiceTutorOrbHideConversationHint
            : strings.voiceTutorOrbRevealConversationHint
    }

    private var orbRepresentsResume: Bool {
        presentation.pauseState.mode == .paused || presentation.pauseState.mode == .resuming
    }

    private var orbAccessibilityValue: String {
        var parts = [displayTopic, orbStatusText]
        if let discoveryPrompt,
           !discoveryPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            parts.append(discoveryPrompt)
        }
        switch presentation.remainingTime {
        case .call(let seconds): parts.append(strings.voiceTutorCallRemaining(seconds))
        case .monthly(let seconds): parts.append(strings.voiceTutorCallMonthlyRemaining(seconds))
        case .monthlyUnlimited: parts.append(strings.voiceTutorUnlimited)
        case nil: break
        }
        if presentation.isRecording { parts.append(strings.voiceTutorCallRecording) }
        if hasAnswerDraft, answerDraftState.holdsMicrophone { parts.append(strings.voiceTutorCallMuted) }
        return parts.joined(separator: ", ")
    }

    private func updateOrbAnimation() {
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            orbPulseExpanded = false
        }
        guard !hasAnswerDraft, presentation.orbAnimates, !reduceMotion else { return }
        let duration = presentation.orbState == .speaking ? 0.72 : 1.35
        withAnimation(.easeInOut(duration: duration).repeatForever(autoreverses: true)) {
            orbPulseExpanded = true
        }
    }

    @ViewBuilder
    private var summaryRow: some View {
        switch presentation.summaryState {
        case .hidden:
            EmptyView()
        case .pending:
            HStack(spacing: 7) {
                ProgressView().controlSize(.mini)
                Text(strings.voiceTutorCallSummaryPending)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        case .failed, .deferred, .unavailable:
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                : AnyLayout(HStackLayout(alignment: .center, spacing: 8))
            layout {
                Label(summaryStatusText, systemImage: presentation.summaryState == .deferred ? "clock" : "exclamationmark.circle")
                    .foregroundStyle(.secondary)
                Button(strings.voiceTutorSummaryRefresh, action: onSummaryRefresh)
                    .frame(minHeight: 44)
                    .disabled(presentation.summaryRefreshState == .loading)
                    .accessibilityIdentifier("voiceCall.summaryRefresh")
            }
            .font(.caption)
        case .ready:
            Button {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
                    showsSummary.toggle()
                }
            } label: {
                Label(
                    strings.voiceTutorCallSummaryReady,
                    systemImage: showsSummary ? "chevron.up" : "chevron.down"
                )
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .font(.subheadline)
            .buttonStyle(.plain)
            .foregroundStyle(.tint)
            .accessibilityIdentifier("voiceCall.summary")
        }
    }

    private var summaryStatusText: String {
        switch presentation.summaryState {
        case .failed: return strings.voiceTutorCallSummaryFailed
        case .unavailable: return strings.voiceTutorCallSummaryUnavailable
        default: return strings.voiceTutorCallSummaryDeferred
        }
    }


}

private struct VoiceTutorCaptionBubble: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var caption: VoiceTutorCaption
    var strings: AppStrings

    private var isLearner: Bool { caption.speaker == .learner }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            if isLearner { Spacer(minLength: dynamicTypeSize.isAccessibilitySize ? 12 : 36) }

            VStack(alignment: .leading, spacing: 8) {
                Text(isLearner ? strings.voiceTutorYou : strings.voiceTutorTeacher)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                Text(caption.text)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
            }
            .padding(isLearner ? 16 : 0)
            .background(
                isLearner ? Color.secondary.opacity(0.09) : .clear,
                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
            )

            if !isLearner { Spacer(minLength: dynamicTypeSize.isAccessibilitySize ? 12 : 24) }
        }
        .frame(maxWidth: .infinity, alignment: isLearner ? .trailing : .leading)
        .accessibilityElement(children: .combine)
    }
}

private struct VoiceTutorSessionDetailView: View {
    @EnvironmentObject private var appState: AppState
    let sessionID: String
    @State private var summaryRefreshID = UUID()
    @State private var summaryRefreshState: VoiceTutorSummaryRefreshState = .loading

    private var strings: AppStrings { appState.strings }
    private var detail: BackendVoiceTutorSessionDetail? {
        appState.voiceTutorSessionDetails[sessionID]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let detail {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(detail.topic.isEmpty ? strings.voiceTutorTitle : detail.topic)
                            .font(.title2.weight(.semibold))
                        HStack(spacing: 8) {
                            if let startedAt = detail.startedAt {
                                Text(
                                    StudyDateDisplayFormatter.relativeOrShortDateString(
                                        for: startedAt,
                                        language: strings.language
                                    )
                                )
                            }
                            Text(strings.voiceTutorDuration(detail.chargedSeconds))
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }

                    VoiceTutorResultSections(
                        detail: detail,
                        strings: strings,
                        refreshState: summaryRefreshState,
                        onRetry: { summaryRefreshID = UUID() }
                    )

                    if let recording = detail.recording,
                       recording.available
                        || ["PENDING", "UPLOADING", "PROCESSING", "READY"]
                            .contains(recording.status?.uppercased() ?? "") {
                        VoiceTutorRecordingPlaybackView(
                            sessionID: sessionID,
                            recording: recording
                        )
                    }

                    if !detail.transcriptTurns.isEmpty {
                        VoiceTutorSourceConversation(
                            turns: detail.transcriptTurns,
                            strings: strings
                        )
                        .padding(14)
                        .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                    }
                } else if summaryRefreshState == .loading {
                    ProgressView()
                        .frame(maxWidth: .infinity, minHeight: 180)
                } else {
                    VoiceTutorResultSections(
                        detail: nil,
                        strings: strings,
                        refreshState: summaryRefreshState,
                        onRetry: { summaryRefreshID = UUID() }
                    )
                        .frame(maxWidth: .infinity, minHeight: 180)
                }
            }
            .padding(16)
        }
        .navigationTitle(strings.voiceTutorLearningSummary)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: summaryRefreshID) {
            await refreshSummary(requestID: summaryRefreshID)
        }
    }

    @MainActor
    private func refreshSummary(requestID: UUID) async {
        guard !Task.isCancelled, summaryRefreshID == requestID else { return }
        summaryRefreshState = .loading
        guard let loader = appState.makeVoiceTutorSessionDetailLoader(
            sessionID: sessionID,
            validity: { summaryRefreshID == requestID }
        ) else {
            summaryRefreshState = .unavailable
            return
        }
        let outcome = await VoiceTutorSummaryPolling.poll(
            sessionID: sessionID,
            initialDetail: detail,
            // A cache hit must not skip the first GET when reopening history.
            refreshCachedDetail: true,
            loader: loader,
            isCurrent: { summaryRefreshID == requestID }
        )
        guard !Task.isCancelled, summaryRefreshID == requestID else { return }
        summaryRefreshState = outcome.reason == .invalidated ? .unavailable : outcome.refreshState
    }
}

private struct VoiceTutorRecordingPlaybackView: View {
    @EnvironmentObject private var appState: AppState
    let sessionID: String
    let recording: BackendVoiceTutorRecording

    @State private var player: AVPlayer?
    @State private var isPlaying = false
    @State private var isLoading = false
    @State private var isDeleting = false
    @State private var showsDeleteConfirmation = false
    @State private var errorMessage: String?

    private var strings: AppStrings { appState.strings }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(strings.voiceTutorSavedRecording, systemImage: "waveform")
                .font(.headline)

            Text(strings.voiceTutorRecordingRetention(recording.retentionDays ?? 30))
                .font(.caption)
                .foregroundStyle(.secondary)

            if recording.available {
                HStack(spacing: 12) {
                    Button {
                        Task { await togglePlayback() }
                    } label: {
                        Label(
                            isPlaying
                                ? strings.voiceTutorPauseRecording
                                : strings.voiceTutorPlayRecording,
                            systemImage: isPlaying ? "pause.fill" : "play.fill"
                        )
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isLoading || isDeleting)

                    Button(role: .destructive) {
                        showsDeleteConfirmation = true
                    } label: {
                        Label(strings.voiceTutorDeleteRecording, systemImage: "trash")
                    }
                    .buttonStyle(.bordered)
                    .disabled(isLoading || isDeleting)

                    if isLoading || isDeleting {
                        ProgressView()
                    }
                }
            } else {
                HStack(spacing: 9) {
                    ProgressView()
                    Text(strings.voiceTutorRecordingPreparing)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }

            if let errorMessage {
                Text(errorMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
        .confirmationDialog(
            strings.voiceTutorDeleteRecordingConfirmation,
            isPresented: $showsDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(strings.voiceTutorDeleteRecording, role: .destructive) {
                Task { await deleteRecording() }
            }
            Button(strings.cancel, role: .cancel) {}
        }
        .onDisappear {
            player?.pause()
            player = nil
            isPlaying = false
        }
    }

    private func togglePlayback() async {
        if isPlaying {
            player?.pause()
            isPlaying = false
            return
        }
        errorMessage = nil
        if player == nil {
            isLoading = true
            defer { isLoading = false }
            do {
                let access = try await appState.voiceTutorRecordingAccess(sessionID: sessionID)
                player = AVPlayer(url: access.url)
            } catch {
                errorMessage = strings.voiceTutorRecordingUnavailable
                return
            }
        }
        await player?.seek(to: .zero)
        player?.play()
        isPlaying = true
    }

    private func deleteRecording() async {
        guard !isDeleting else { return }
        isDeleting = true
        errorMessage = nil
        player?.pause()
        player = nil
        isPlaying = false
        do {
            try await appState.deleteVoiceTutorRecording(sessionID: sessionID)
        } catch {
            errorMessage = strings.voiceTutorRecordingUnavailable
        }
        isDeleting = false
    }
}

private struct VoiceTutorResultSections: View {
    var detail: BackendVoiceTutorSessionDetail?
    var strings: AppStrings
    var refreshState: VoiceTutorSummaryRefreshState = .idle
    var onRetry: (() -> Void)?

    var body: some View {
        if VoiceTutorSummaryState(detail: detail) == .ready, let result = detail?.result {
            let strengths = VoiceTutorSummaryState.nonemptyItems(result.strengths)
            let improvements = VoiceTutorSummaryState.nonemptyItems(result.improvements)
            let nextSteps = VoiceTutorSummaryState.nonemptyItems(result.nextSteps)
            VStack(alignment: .leading, spacing: 18) {
                if !result.summaryMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    resultSection(title: strings.voiceTutorLearningSummary) {
                        // Tutor summaries are private model output. Render them as
                        // inert text so Markdown images, HTML, and links can never
                        // trigger an automatic third-party network request.
                        Text(verbatim: result.summaryMarkdown)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if !strengths.isEmpty {
                    bulletSection(title: strings.voiceTutorStrengths, values: strengths)
                }
                if !improvements.isEmpty {
                    bulletSection(title: strings.voiceTutorImprovements, values: improvements)
                }
                if !nextSteps.isEmpty {
                    bulletSection(title: strings.voiceTutorNextSteps, values: nextSteps)
                }
                if result.explorations.contains(where: VoiceTutorExplorationPresentation.hasContent) {
                    VoiceTutorExplorationSections(
                        explorations: result.explorations,
                        transcript: detail?.transcriptTurns ?? [],
                        strings: strings
                    )
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text(strings.voiceTutorLearningSummary)
                    .font(.headline)
                Text(statusText)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let onRetry, refreshState != .loading,
                   VoiceTutorSummaryState(detail: detail) != .empty {
                    Button(strings.voiceTutorSummaryRefresh, action: onRetry)
                        .font(.subheadline)
                        .frame(minHeight: 44)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private var statusText: String {
        switch VoiceTutorSummaryState(detail: detail) {
        case .failed: return strings.voiceTutorSummaryFailed
        case .empty: return strings.voiceTutorSummaryEmpty
        case .ready: return ""
        case .unknown, .pending:
            switch refreshState {
            case .unavailable: return strings.voiceTutorSummaryUnavailable
            case .deferred: return strings.voiceTutorSummaryDeferred
            case .idle, .loading:
                return VoiceTutorSummaryState(detail: detail) == .pending
                    ? strings.voiceTutorSummaryPending : strings.voiceTutorSummaryDeferred
            }
        }
    }

    private func bulletSection(title: String, values: [String]) -> some View {
        resultSection(title: title) {
            VStack(alignment: .leading, spacing: 7) {
                ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                    HStack(alignment: .top, spacing: 7) {
                        Text("•")
                        Text(value)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .font(.subheadline)
        }
    }

    private func resultSection<Content: View>(
        title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text(title)
                .font(.headline)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
    }
}

/// Expanding a private result never creates questions, writes studies, or fetches
/// the user's entire record history. Only this session's bounded data is shown.
private struct VoiceTutorExplorationSections: View {
    let explorations: [BackendVoiceTutorExploration]
    let transcript: [BackendVoiceTutorTranscriptTurn]
    let strings: AppStrings
    @State private var isExpanded = false
    @State private var visibleCount = VoiceTutorExplorationPresentation.topicPageSize

    private var items: [BackendVoiceTutorExploration] {
        explorations.filter(VoiceTutorExplorationPresentation.hasContent)
    }

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            if isExpanded {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(items.prefix(visibleCount).enumerated()), id: \.offset) { index, exploration in
                        if index > 0 { Divider() }
                        VoiceTutorExplorationRow(
                            exploration: exploration,
                            transcript: transcript,
                            strings: strings
                        )
                    }
                    if visibleCount < items.count {
                        Button(strings.voiceTutorExplorationMoreTopics) {
                            visibleCount = VoiceTutorExplorationPresentation.nextVisibleCount(
                                current: visibleCount,
                                total: items.count,
                                pageSize: VoiceTutorExplorationPresentation.topicPageSize
                            )
                        }
                        .font(.subheadline)
                        .frame(minHeight: 44)
                    }
                }
                .padding(.top, 10)
            }
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(strings.voiceTutorExplorationsTitle)
                    .font(.headline)
                Text(strings.voiceTutorExplorationCount(items.count))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityIdentifier("voiceSummary.explorations")
    }
}

private struct VoiceTutorExplorationRow: View {
    let exploration: BackendVoiceTutorExploration
    let transcript: [BackendVoiceTutorTranscriptTurn]
    let strings: AppStrings
    @State private var isExpanded = false
    @State private var visibleCount = VoiceTutorExplorationPresentation.exchangePageSize

    private var exchanges: [BackendVoiceTutorExplorationExchange] {
        VoiceTutorExplorationPresentation.displayExchanges(exploration.exchanges)
    }

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            if isExpanded {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if !exploration.depthSummary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(strings.voiceTutorExplorationDepth)
                                .font(.caption.weight(.medium))
                                .foregroundStyle(.secondary)
                            Text(verbatim: exploration.depthSummary)
                                .font(.subheadline)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    ForEach(Array(exchanges.prefix(visibleCount).enumerated()), id: \.offset) { _, exchange in
                        VoiceTutorExplorationExchangeRow(
                            exchange: exchange,
                            transcript: transcript,
                            strings: strings
                        )
                    }
                    if visibleCount < exchanges.count {
                        Button(strings.voiceTutorExplorationMoreExchanges) {
                            visibleCount = VoiceTutorExplorationPresentation.nextVisibleCount(
                                current: visibleCount,
                                total: exchanges.count,
                                pageSize: VoiceTutorExplorationPresentation.exchangePageSize
                            )
                        }
                        .font(.subheadline)
                        .frame(minHeight: 44)
                    }
                }
                .padding(.top, 8)
            }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: exploration.topic)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(isExpanded ? nil : 2)
                if let level = exploration.difficulty {
                    Text("\(strings.studyLevelShort) \(level)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize()
                }
            }
        }
        .accessibilityIdentifier("voiceSummary.topic")
    }
}

private struct VoiceTutorExplorationExchangeRow: View {
    let exchange: BackendVoiceTutorExplorationExchange
    let transcript: [BackendVoiceTutorTranscriptTurn]
    let strings: AppStrings
    @State private var isExpanded = false

    private var isLearnerQuestion: Bool { exchange.kind == .learnerQuestion }
    private var sourceTurns: [BackendVoiceTutorTranscriptTurn] {
        VoiceTutorExplorationPresentation.sourceTurns(for: exchange, in: transcript)
    }

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            if isExpanded {
                VStack(alignment: .leading, spacing: 10) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(isLearnerQuestion
                             ? strings.voiceTutorExplorationTutorAnswer
                             : strings.voiceTutorExplorationLearnerAnswer)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                        if exchange.answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Text(strings.voiceTutorExplorationNoAnswer)
                                .foregroundStyle(.secondary)
                        } else {
                            Text(verbatim: exchange.answer)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    if !isLearnerQuestion {
                        feedback(title: strings.voiceTutorStrengths, values: exchange.strengths)
                        feedback(title: strings.voiceTutorImprovements, values: exchange.improvements)
                    }
                    if !sourceTurns.isEmpty {
                        VoiceTutorSourceConversation(turns: sourceTurns, strings: strings)
                    }
                }
                .font(.subheadline)
                .padding(.top, 7)
            }
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { exchangeMetadata }
                    VStack(alignment: .leading, spacing: 3) { exchangeMetadata }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                Text(verbatim: exchange.question)
                    .font(.subheadline)
                    .lineLimit(isExpanded ? nil : 2)
            }
        }
        .padding(10)
        .background(Color.secondary.opacity(0.05), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityIdentifier(isLearnerQuestion ? "voiceSummary.learnerQuestion" : "voiceSummary.tutorQuestion")
    }

    @ViewBuilder
    private var exchangeMetadata: some View {
        Text(isLearnerQuestion
             ? strings.voiceTutorExplorationLearnerQuestion
             : strings.voiceTutorExplorationTutorQuestion)
            .fontWeight(.medium)
        if let score = VoiceTutorExplorationPresentation.displayScore(for: exchange) {
            Text(strings.voiceTutorExplorationScore(score))
                .accessibilityIdentifier("voiceSummary.callScore")
        }
    }

    @ViewBuilder
    private func feedback(title: String, values: [String]) -> some View {
        let items = VoiceTutorSummaryState.nonemptyItems(values)
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                ForEach(Array(items.enumerated()), id: \.offset) { _, text in
                    HStack(alignment: .top, spacing: 5) {
                        Text("•")
                        Text(verbatim: text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }
}

/// Used both by a specific exchange's evidence and by the whole-session history.
/// No live-call caption/transport behavior is changed by this view.
private struct VoiceTutorSourceConversation: View {
    let turns: [BackendVoiceTutorTranscriptTurn]
    let strings: AppStrings
    @State private var isExpanded = false
    @State private var visibleCount = VoiceTutorExplorationPresentation.transcriptPageSize

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            if isExpanded {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(turns.prefix(visibleCount).enumerated()), id: \.offset) { _, turn in
                        VStack(alignment: .leading, spacing: 3) {
                            if let speaker = speaker(for: turn) {
                                Text(speaker)
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(.secondary)
                            }
                            Text(verbatim: turn.text)
                                .font(.subheadline)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    if visibleCount < turns.count {
                        Button(strings.voiceTutorExplorationMoreExchanges) {
                            visibleCount = VoiceTutorExplorationPresentation.nextVisibleCount(
                                current: visibleCount,
                                total: turns.count,
                                pageSize: VoiceTutorExplorationPresentation.transcriptPageSize
                            )
                        }
                        .font(.subheadline)
                        .frame(minHeight: 44)
                    }
                }
                .padding(.top, 8)
            }
        } label: {
            Text(strings.voiceTutorExplorationSource)
                .font(.subheadline.weight(.medium))
        }
        .accessibilityIdentifier("voiceSummary.sourceConversation")
    }

    private func speaker(for turn: BackendVoiceTutorTranscriptTurn) -> String? {
        switch turn.role.lowercased() {
        case "user": strings.voiceTutorYou
        case "assistant", "tutor": strings.voiceTutorTeacher
        default: nil
        }
    }
}
#endif
