#if os(iOS)
import AVFoundation
import SwiftUI

struct VoiceTutorView: View {
    @EnvironmentObject private var appState: AppState
    @State private var showsMembership = false
    @State private var recordingConsent = false
    @State private var callRecordingConsent = false
    @State private var showsCall = false

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
                                    await appState.refreshVoiceTutorStatus()
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
            await appState.retryPendingVoiceTutorRecordingUploads()
            await appState.refreshVoiceTutorStatus()
            await appState.loadVoiceTutorSessions(reset: true)
        }
        .refreshable {
            recordingConsent = false
            await appState.refreshVoiceTutorStatus()
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
            if appState.isLoadingVoiceTutorStatus && status == nil {
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
                    await appState.refreshVoiceTutorStatus()
                }
            }
            .buttonStyle(.bordered)
        }
        .padding(.vertical, 4)
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
                pauseState: viewModel.pauseState,
                sessionSecondsRemaining: viewModel.sessionSecondsRemaining,
                quotaRemainingSeconds: viewModel.quotaRemainingSeconds,
                quotaReservedSeconds: viewModel.quotaReservedSeconds,
                quotaLimitSeconds: viewModel.quotaLimitSeconds,
                detail: viewModel.detail,
                summaryRefreshState: viewModel.summaryRefreshState
            ),
            strings: strings,
            captions: viewModel.captions,
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
            onSummaryRefresh: { Task { await viewModel.refreshSummary() } }
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
            guard newPhase == .background else {
                return
            }
            Task {
                await viewModel.stopForBackground()
            }
        }
        .onDisappear {
            Task {
                await viewModel.stopForBackground()
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
        case connecting, listening, speaking, pausing, paused, resuming, ending, ended, failed
    }

    enum OrbAction: Equatable {
        case none, pause, resume
    }

    var phase: VoiceTutorSessionPhase
    var failureCause: VoiceTutorFailureCause? = nil
    var serverEndReason: String? = nil
    var isRecording = false
    var inputNeedsRepeat = false
    var pauseState = VoiceTutorCallPauseState()
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
        }
        switch phase {
        case .idle, .requestingPermission, .connecting: return .connecting
        case .listening: return .listening
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
        orbState == .listening || orbState == .speaking
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

    func statusText(_ strings: AppStrings, errorMessage: String? = nil) -> String {
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
        }
        switch phase {
        case .idle, .requestingPermission, .connecting: return strings.voiceTutorCallConnecting
        case .listening:
            return inputNeedsRepeat ? strings.voiceTutorInputRepeat : strings.voiceTutorCallListening
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

/// The same non-networking surface is rendered by device visual tests.
struct VoiceTutorCallScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .largeTitle) private var preferredOrbDiameter: CGFloat = 168
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
    @GestureState private var orbDragIsActive = false
    let topic: String
    var discoveryPrompt: String? = nil
    let presentation: VoiceTutorCallPresentation
    let strings: AppStrings
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

    private var usesAccessibilityChrome: Bool {
        VoiceTutorCallAdaptiveLayout.usesAccessibilityChrome(for: dynamicTypeSize)
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
        .onChange(of: orbDragIsActive) { _, isDragging in
            // A system-cancelled drag must settle back without toggling pause
            // or leaving a half-visible conversation on screen.
            if !isDragging, orbDragStartsExpanded != nil {
                setTranscriptExpanded(showsTranscript)
            }
        }
        .onChange(of: captions.isEmpty) { _, isEmpty in
            if isEmpty { resetTranscriptInteraction() }
        }
        .onDisappear {
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
    }

    private func compactCall(in geometry: GeometryProxy) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                Spacer(minLength: usesAccessibilityChrome ? 12 : 28)

                orbPlaceholder(.call, diameter: compactOrbDiameter(in: geometry))

                VStack(spacing: 8) {
                    Text(topic)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                        .fixedSize(horizontal: false, vertical: true)

                    callTime
                    callNotices
                    summaryRow
                    if showsSummary && presentation.summaryState == .ready {
                        VoiceTutorResultSections(detail: presentation.detail, strings: strings)
                    }
                }

                Spacer(minLength: 20)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .frame(
                maxWidth: .infinity,
                minHeight: max(300, geometry.size.height * (usesAccessibilityChrome ? 0.62 : 0.74))
            )
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
        VStack(alignment: .leading, spacing: usesAccessibilityChrome ? 12 : 8) {
            HStack(spacing: 10) {
                transcriptReturnButton

                Spacer(minLength: 8)

                if !usesAccessibilityChrome {
                    callTime
                }

                orbPlaceholder(.transcript, diameter: usesAccessibilityChrome ? 56 : 48)
            }

            Text(strings.voiceTutorCallTranscript)
                .font(usesAccessibilityChrome ? .headline.weight(.semibold) : .title2.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)

            if usesAccessibilityChrome {
                VStack(alignment: .leading, spacing: 6) {
                    callTime
                    transcriptStatus
                    Text(topic)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
                HStack(spacing: 6) {
                    transcriptStatus
                    Text(topic)
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.horizontal, usesAccessibilityChrome ? 16 : 20)
        .padding(.vertical, 12)
    }

    private var transcriptReturnButton: some View {
        Button {
            setTranscriptExpanded(false)
        } label: {
            Group {
                if usesAccessibilityChrome {
                    Image(systemName: "chevron.left")
                        .font(.body.weight(.semibold))
                        .frame(width: 44, height: 44)
                } else {
                    Label(strings.voiceTutorCallCollapseConversation, systemImage: "chevron.left")
                        .font(.subheadline.weight(.medium))
                        .frame(minHeight: 44)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.tint)
        .accessibilityLabel(strings.voiceTutorCallCollapseConversation)
        .accessibilityIdentifier("voiceCall.collapseTranscript")
    }

    private var transcriptStatus: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(statusColor)
                .frame(width: 7, height: 7)
                .accessibilityHidden(true)
            Text(presentation.statusText(strings, errorMessage: errorMessage))
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(usesAccessibilityChrome ? nil : 1)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func callOrb(diameter: CGFloat) -> some View {
        Group {
            if presentation.showsPauseControl {
                Button(action: onPause) {
                    orbVisual(diameter: diameter)
                }
                .buttonStyle(.plain)
                .disabled(presentation.orbAction == .none)
                .accessibilityLabel(orbActionLabel)
                .accessibilityValue(orbAccessibilityValue)
                .accessibilityHint(orbActionHint)
                .accessibilityAddTraits(presentation.pauseState.holdsMicrophone ? .isSelected : [])
                .accessibilityIdentifier("voiceCall.orb")
            } else {
                orbVisual(diameter: diameter)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(strings.voiceTutorTeacher)
                    .accessibilityValue(orbAccessibilityValue)
                    .accessibilityHint(orbTranscriptGestureHint)
                    .accessibilityIdentifier("voiceCall.orb")
            }
        }
        .accessibilityAction(
            named: Text(showsTranscript ? strings.voiceTutorCallCollapseConversation : strings.voiceTutorCallRevealConversation)
        ) {
            setTranscriptExpanded(!showsTranscript)
        }
    }

    private func orbPlaceholder(_ placement: VoiceTutorOrbPlacement, diameter: CGFloat) -> some View {
        Color.clear
            .frame(width: diameter, height: diameter)
            .anchorPreference(key: VoiceTutorOrbAnchorPreferenceKey.self, value: .bounds) { [placement: $0] }
            .accessibilityHidden(true)
    }

    private func orbVisual(diameter: CGFloat) -> some View {
        ZStack {
            if presentation.orbAnimates {
                Circle()
                    .stroke(orbTint.opacity(orbPulseExpanded ? 0.05 : 0.26), lineWidth: 2)
                    .scaleEffect(orbPulseExpanded ? 1.16 : 1.02)
            }

            Circle()
                .fill(orbTint.opacity(presentation.orbState == .paused ? 0.08 : 0.16))

            Circle()
                .stroke(
                    orbTint.opacity(presentation.orbState == .paused ? 0.62 : 0.78),
                    style: StrokeStyle(
                        lineWidth: presentation.orbState == .paused ? 3 : 2,
                        lineCap: .round,
                        dash: presentation.orbState == .paused ? [7, 9] : []
                    )
                )

            if let symbol = orbSymbol {
                Image(systemName: symbol)
                    .font(.system(size: diameter * 0.24, weight: .semibold))
                    .foregroundStyle(orbTint)
                    .symbolEffect(
                        .variableColor.iterative,
                        options: .repeating,
                        isActive: presentation.orbState == .speaking && !reduceMotion
                    )
            } else {
                ProgressView()
                    .controlSize(diameter > 120 ? .large : .regular)
                    .tint(orbTint)
            }

        }
        .frame(width: diameter, height: diameter)
        .scaleEffect(orbScale)
        .shadow(
            color: presentation.orbAnimates ? orbTint.opacity(orbPulseExpanded ? 0.18 : 0.08) : .clear,
            radius: orbPulseExpanded ? 28 : 12
        )
        .contentShape(Circle())
    }

    private var callNotices: some View {
        VStack(spacing: 7) {
            if presentation.isRecording {
                Label(strings.voiceTutorCallRecording, systemImage: "record.circle.fill")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("voiceCall.recording")
            }

            HStack(spacing: 6) {
                Image(systemName: statusSymbol)
                    .foregroundStyle(statusColor)
                Text(presentation.statusText(strings, errorMessage: errorMessage))
            }
            .font(.caption)
            .foregroundStyle(showsConnectionFailure ? Color.red : Color.secondary)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("voiceCall.status")

            if let error = presentation.supplementaryError(strings, errorMessage: errorMessage) {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(showsConnectionFailure ? Color.red : Color.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 3)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("voiceCall.error")
            }

            if presentation.phase.isLive && presentation.pauseState.holdsMicrophone {
                Text(strings.voiceTutorPauseUsesTime)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("voiceCall.pauseUsesTime")
            }
        }
        .frame(maxWidth: .infinity)
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
                LazyVStack(alignment: .leading, spacing: 14) {
                    if captions.isEmpty && assistantTranscriptDraft.isEmpty {
                        Text(strings.voiceTutorCallNoCaptions)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                    ForEach(captions) { caption in
                        VoiceTutorCaptionBubble(caption: caption, strings: strings)
                    }
                    if !assistantTranscriptDraft.isEmpty {
                        VoiceTutorCaptionBubble(
                            caption: VoiceTutorCaption(speaker: .tutor, text: assistantTranscriptDraft),
                            strings: strings
                        )
                    }
                    Color.clear.frame(height: 1).id("voiceCall.latestCaption")
                }
                .padding(.vertical, 12)
                .padding(.horizontal, 14)
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
        guard transcriptFollowState.shouldAutoScrollForContentChange else { return }
        transcriptScrollAnimates = transcriptScrollAnimates || (animated && showsTranscript)
        transcriptAutoScrollTask?.cancel()
        transcriptAutoScrollTask = Task { @MainActor in
            // Caption mutations arrive before SwiftUI has necessarily laid out
            // the new bottom anchor. Wait one main-actor turn, then re-check the
            // learner's position so a concurrent scroll toward history wins.
            await Task.yield()
            guard !Task.isCancelled,
                  transcriptFollowState.shouldAutoScrollForContentChange else { return }
            let animate = transcriptScrollAnimates && !reduceMotion
            transcriptScrollAnimates = false
            withAnimation(animate ? .easeOut(duration: 0.18) : nil) {
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
        }
    }

    private func scheduleTranscriptScrollSettlement(using proxy: ScrollViewProxy) {
        guard transcriptFollowState.shouldScheduleSettlement(
            isGestureActive: transcriptDragIsActive
        ) else { return }
        transcriptScrollSettleTask?.cancel()
        transcriptScrollSettleTask = Task { @MainActor in
            do {
                try await Task.sleep(nanoseconds: 180_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
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
        VStack(spacing: 0) {
            Divider()
            stableCallControls
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
        }
        .frame(maxWidth: .infinity)
        .background(Color(uiColor: .systemBackground))
    }

    @ViewBuilder
    private var stableCallControls: some View {
        switch presentation.primaryAction {
        case .end:
            liveCallControl(
                title: strings.voiceTutorCallEnd,
                symbol: "phone.down.fill",
                tint: .red,
                filled: true,
                identifier: "voiceCall.end",
                action: onEnd
            )
            .frame(maxWidth: .infinity)
        case .retry, .dismiss:
            terminalControls
        case .wait:
            EmptyView()
        }
    }

    @ViewBuilder
    private var terminalControls: some View {
        switch presentation.primaryAction {
        case .retry:
            responsiveControlLayout {
                primaryControl(
                    title: strings.voiceTutorCallRetry,
                    symbol: "phone.fill",
                    tint: .green,
                    identifier: "voiceCall.retry",
                    action: onRetry
                )
                neutralControl(
                    title: strings.done,
                    symbol: "xmark",
                    identifier: "voiceCall.done",
                    action: onDismiss
                )
            }
        case .dismiss:
            primaryControl(
                title: strings.done,
                symbol: "checkmark",
                tint: .accentColor,
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
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 10))
            : AnyLayout(HStackLayout(spacing: 12))
        return layout { content() }
            .frame(maxWidth: .infinity)
    }

    private func liveCallControl(
        title: String,
        symbol: String,
        tint: Color = .accentColor,
        filled: Bool = false,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Group {
                if usesAccessibilityChrome {
                    HStack(spacing: 12) {
                        liveCallControlIcon(symbol: symbol, tint: tint, filled: filled, diameter: 48)
                        Text(title)
                            .font(.body.weight(.medium))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 4)
                    .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                } else {
                    VStack(spacing: 5) {
                        liveCallControlIcon(symbol: symbol, tint: tint, filled: filled, diameter: 52)
                        Text(title)
                            .font(.caption)
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.center)
                            .lineLimit(1)
                    }
                    .frame(width: 96)
                    .frame(minHeight: 66)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityIdentifier(identifier)
    }

    private func liveCallControlIcon(
        symbol: String,
        tint: Color,
        filled: Bool,
        diameter: CGFloat
    ) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(filled ? Color.white : tint)
            .frame(width: diameter, height: diameter)
            .background(
                filled ? tint : Color.secondary.opacity(0.12),
                in: Circle()
            )
            .accessibilityHidden(true)
    }

    private func neutralControl(
        title: String,
        symbol: String,
        selected: Bool = false,
        enabled: Bool = true,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.subheadline.weight(.medium))
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.capsule)
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
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
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .tint(tint)
        .accessibilityIdentifier(identifier)
    }

    private func destructiveControl(
        title: String,
        symbol: String,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        primaryControl(title: title, symbol: symbol, tint: .red, identifier: identifier, action: action)
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
        let minimum: CGFloat = dynamicTypeSize.isAccessibilitySize ? 104 : 112
        let available = min(geometry.size.width - 96, geometry.size.height * 0.25)
        return max(minimum, min(preferredOrbDiameter, 152, available))
    }

    private func orbTranscriptGesture(travel: CGFloat) -> some Gesture {
        DragGesture(
            minimumDistance: VoiceTutorOrbGestureRouting.dragRecognitionDistance,
            coordinateSpace: .global
        )
        .updating($orbDragIsActive) { _, isActive, transaction in
            transaction.animation = nil
            isActive = true
        }
        .onChanged { value in
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
            guard let startsExpanded = orbDragStartsExpanded else { return }
            setTranscriptExpanded(VoiceTutorOrbGestureRouting.settlesExpanded(
                translation: value.translation,
                predictedTranslation: value.predictedEndTranslation,
                startsExpanded: startsExpanded
            ))
        }
    }

    private var orbScale: CGFloat {
        if presentation.orbState == .paused { return 0.84 }
        guard presentation.orbAnimates else { return 1 }
        return orbPulseExpanded ? 1.035 : 0.985
    }

    private var orbTint: Color {
        if presentation.isMonthlyQuotaExhausted { return .orange }
        switch presentation.orbState {
        case .listening: return .green
        case .speaking: return .accentColor
        case .failed: return .red
        case .paused, .pausing, .resuming, .connecting, .ending, .ended: return .secondary
        }
    }

    private var orbSymbol: String? {
        switch presentation.orbState {
        case .connecting, .ending: return nil
        case .listening: return "mic.fill"
        case .speaking: return "waveform"
        case .pausing, .paused: return "pause.fill"
        case .resuming: return "play.fill"
        case .ended: return "checkmark"
        case .failed: return failureSymbol
        }
    }

    private var orbActionLabel: String {
        orbRepresentsResume ? strings.voiceTutorResumeLesson : strings.voiceTutorTakeBreak
    }

    private var orbActionHint: String {
        let tapHint = orbRepresentsResume ? strings.voiceTutorOrbResumeHint : strings.voiceTutorOrbPauseHint
        return "\(tapHint) \(orbTranscriptGestureHint)"
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
        var parts = [topic, presentation.statusText(strings, errorMessage: errorMessage)]
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
        return parts.joined(separator: ", ")
    }

    private func updateOrbAnimation() {
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            orbPulseExpanded = false
        }
        guard presentation.orbAnimates, !reduceMotion else { return }
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

    private var statusSymbol: String {
        if showsConnectionFailure { return "wifi.exclamationmark" }
        if presentation.isMonthlyQuotaExhausted { return "clock.fill" }
        if presentation.phase.isLive && presentation.pauseState.mode == .paused { return "pause.fill" }
        switch presentation.phase {
        case .speaking: return "waveform"
        case .listening: return "phone.fill"
        case .failed: return failureSymbol
        case .ended: return "phone.down"
        default: return "phone"
        }
    }

    private var statusColor: Color {
        if showsConnectionFailure { return .red }
        if presentation.isMonthlyQuotaExhausted { return .orange }
        if presentation.phase.isLive && presentation.pauseState.holdsMicrophone { return .secondary }
        switch presentation.phase {
        case .failed: return .red
        case .speaking: return .accentColor
        case .listening: return .green
        default: return .secondary
        }
    }

    private var showsConnectionFailure: Bool {
        presentation.showsConnectionFailure(strings, errorMessage: errorMessage)
    }

    private var failureSymbol: String {
        switch presentation.failureCause {
        case .connection, .none: return "wifi.exclamationmark"
        case .microphone: return "mic.slash.fill"
        case .audio: return "speaker.slash.fill"
        case .localControl: return "pause.circle.fill"
        case .provider, .providerUnavailable, .updateRequired, .requestRejected, .service, .unknown:
            return "exclamationmark"
        }
    }

}

private struct VoiceTutorCaptionBubble: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var caption: VoiceTutorCaption
    var strings: AppStrings

    var body: some View {
        HStack {
            if caption.speaker == .learner {
                Spacer(minLength: dynamicTypeSize.isAccessibilitySize ? 16 : 44)
            }

            VStack(
                alignment: caption.speaker == .learner ? .trailing : .leading,
                spacing: 4
            ) {
                Text(caption.speaker == .learner ? strings.voiceTutorYou : strings.voiceTutorTeacher)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(caption.text)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 13)
            .padding(.vertical, 10)
            .background(
                caption.speaker == .learner
                    ? Color.accentColor.opacity(0.14)
                    : Color.secondary.opacity(0.10),
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )

            if caption.speaker != .learner {
                Spacer(minLength: dynamicTypeSize.isAccessibilitySize ? 16 : 44)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
