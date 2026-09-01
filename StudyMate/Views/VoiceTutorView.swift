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

                if status.quota.remainingSeconds == 0 && status.quota.reservedSeconds == 0 {
                    Text(strings.voiceTutorQuotaReached)
                        .font(.caption)
                        .foregroundStyle(.orange)
                }
            }
            .padding(.vertical, 6)
        } label: {
            HStack(alignment: .firstTextBaseline) {
                Text(status.quota.reservedSeconds > 0
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
                isMuted: viewModel.isMuted,
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
            onMute: { viewModel.toggleMute() },
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

/// Display-only state. Time and connection truth still belong to the existing
/// session model; UI expansion never starts, stops, or replaces a call.
struct VoiceTutorCallPresentation {
    enum RemainingTime: Equatable {
        case call(Int)
        case monthly(Int)
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
    var isMuted = false
    var isRecording = false
    var inputNeedsRepeat = false
    var pauseState = VoiceTutorCallPauseState()
    var sessionSecondsRemaining: Int?
    var quotaRemainingSeconds = 0
    var quotaReservedSeconds = 0
    var quotaLimitSeconds = 0
    var detail: BackendVoiceTutorSessionDetail?
    var summaryRefreshState: VoiceTutorSummaryRefreshState = .idle

    var canMute: Bool { (phase == .listening || phase == .speaking) && !pauseState.holdsMicrophone }
    var showsPauseControl: Bool { pauseState.isSupported && phase.isLive }
    var canChangePause: Bool {
        showsPauseControl && (phase == .listening || phase == .speaking) && !pauseState.isAwaitingAcknowledgement
    }
    var microphoneIsMuted: Bool { pauseState.effectiveMicrophoneMuted(userMuted: isMuted) }

    var orbState: OrbState {
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
        if phase.isLive {
            // Monthly availability excludes the current reservation and can be
            // zero during a healthy call. Never substitute it for the countdown.
            return sessionSecondsRemaining.map { .call(max(0, $0)) }
        }
        guard phase == .ended || phase == .failed,
              quotaLimitSeconds > 0, quotaReservedSeconds == 0 else { return nil }
        return .monthly(max(0, quotaRemainingSeconds))
    }

    var primaryAction: PrimaryAction {
        if phase.isLive { return .end }
        switch phase {
        case .failed: return .retry
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
        phase == .failed || (phase == .ending
            && errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines) == strings.voiceTutorConnectionFailed)
    }

    func statusText(_ strings: AppStrings, errorMessage: String? = nil) -> String {
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
            if isMuted { return strings.voiceTutorCallMuted }
            return inputNeedsRepeat ? strings.voiceTutorInputRepeat : strings.voiceTutorCallListening
        case .speaking: return strings.voiceTutorCallSpeaking
        case .ending: return strings.voiceTutorCallEnding
        case .ended: return strings.voiceTutorCallEnded
        case .failed: return strings.voiceTutorCallFailed
        }
    }

    func supplementaryError(_ strings: AppStrings, errorMessage: String?) -> String? {
        guard let error = errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines),
              !error.isEmpty else { return nil }
        if showsConnectionFailure(strings, errorMessage: error),
           error == strings.voiceTutorConnectionFailed { return nil }
        return error
    }

    func needsVisibleStatus(_ strings: AppStrings, errorMessage: String?) -> Bool {
        guard phase == .listening || phase == .speaking else { return true }
        return pauseState.mode != .active || isMuted || inputNeedsRepeat
            || supplementaryError(strings, errorMessage: errorMessage) != nil
    }
}

/// A deliberately small, deterministic gesture contract so transcript reveal
/// never treats a short tap or horizontal page gesture as a call command.
struct VoiceTutorCallTranscriptGesture {
    enum Action: Equatable { case reveal, collapse }

    static let minimumVerticalDistance: CGFloat = 44

    static func action(translation: CGSize, isExpanded: Bool) -> Action? {
        let verticalDistance = abs(translation.height)
        guard verticalDistance >= minimumVerticalDistance,
              verticalDistance > abs(translation.width) * 1.15 else { return nil }
        if isExpanded, translation.height < 0 { return .collapse }
        if !isExpanded, translation.height > 0 { return .reveal }
        return nil
    }

    /// Expanded calls install one simultaneous gesture over the whole surface.
    /// Transcript drags stay with the nested `ScrollView` until it is already
    /// at the latest edge; only a further intentional upward swipe may collapse.
    /// Failing closed while layout is unresolved prevents an early transcript
    /// scroll from accidentally dismissing the conversation.
    static func expandedSurfaceAction(
        translation: CGSize,
        startLocation: CGPoint,
        transcriptFrame: CGRect,
        transcriptWasAtLatestAtStart: Bool,
        expandedScrollWasAtLatestAtStart: Bool
    ) -> Action? {
        guard transcriptFrame.isUsableForGestureRouting else { return nil }
        // Large Dynamic Type can make the expanded call itself scrollable. Let
        // that outer ScrollView reach its controls before an upward overscroll
        // becomes the collapse command.
        guard expandedScrollWasAtLatestAtStart else { return nil }
        if transcriptFrame.contains(startLocation), !transcriptWasAtLatestAtStart {
            return nil
        }
        return action(translation: translation, isExpanded: true)
    }

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
        if VoiceTutorCallTranscriptGesture.transcriptIsAtLatest(
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

private struct VoiceTutorTranscriptFramePreferenceKey: PreferenceKey {
    static let defaultValue = CGRect.null

    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if next.isUsableForGestureRouting {
            value = next
        }
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

private struct VoiceTutorExpandedContentFramePreferenceKey: PreferenceKey {
    static let defaultValue = CGRect.null

    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if next.isUsableForGestureRouting {
            value = next
        }
    }
}

private struct VoiceTutorExpandedViewportHeightPreferenceKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next.isFinite, next > 0 {
            value = next
        }
    }
}

/// The same non-networking surface is rendered by device visual tests.
struct VoiceTutorCallScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .largeTitle) private var preferredOrbDiameter: CGFloat = 196
    @ScaledMetric(relativeTo: .title) private var preferredExpandedOrbDiameter: CGFloat = 88
    @State private var orbPulseExpanded = false
    @State private var transcriptFrame = CGRect.null
    @State private var transcriptContentFrame = CGRect.null
    @State private var transcriptViewportHeight: CGFloat = 0
    @State private var transcriptFollowState = VoiceTutorTranscriptFollowState()
    @State private var transcriptScrollSettleTask: Task<Void, Never>?
    @State private var expandedContentFrame = CGRect.null
    @State private var expandedViewportHeight: CGFloat = 0
    @State private var expandedDragHasStarted = false
    @State private var transcriptWasAtLatestWhenExpandedDragStarted = false
    @State private var expandedScrollWasAtLatestWhenDragStarted = false
    @GestureState private var expandedDragIsActive = false
    @GestureState private var transcriptDragIsActive = false
    let topic: String
    var discoveryPrompt: String? = nil
    let presentation: VoiceTutorCallPresentation
    let strings: AppStrings
    var captions: [VoiceTutorCaption] = []
    var assistantTranscriptDraft = ""
    var errorMessage: String?
    @Binding var showsTranscript: Bool
    @Binding var showsSummary: Bool
    var onMute: () -> Void = {}
    var onPause: () -> Void = {}
    var onEnd: () -> Void = {}
    var onRetry: () -> Void = {}
    var onDismiss: () -> Void = {}
    var onSummaryRefresh: () -> Void = {}

    var body: some View {
        GeometryReader { geometry in
            Group {
                if showsTranscript {
                    expandedCall(in: geometry)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                } else {
                    compactCall(in: geometry)
                        .transition(.opacity)
                        .simultaneousGesture(transcriptDragGesture(isExpanded: false))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        .onChange(of: showsTranscript) { _, _ in
            // A new expansion must wait for its own layout measurements rather
            // than route a gesture using frames retained from an earlier one.
            transcriptFrame = .null
            transcriptContentFrame = .null
            transcriptViewportHeight = 0
            transcriptScrollSettleTask?.cancel()
            transcriptScrollSettleTask = nil
            transcriptFollowState.reset()
            expandedContentFrame = .null
            expandedViewportHeight = 0
            expandedDragHasStarted = false
            transcriptWasAtLatestWhenExpandedDragStarted = false
            expandedScrollWasAtLatestWhenDragStarted = false
        }
        .onDisappear {
            transcriptScrollSettleTask?.cancel()
            transcriptScrollSettleTask = nil
        }
        .onChange(of: expandedDragIsActive) { wasActive, isActive in
            guard wasActive, !isActive else { return }
            // `DragGesture` has no cancellation callback. GestureState resets
            // on both completion and cancellation, so no stale edge snapshot
            // can leak into the next swipe.
            expandedDragHasStarted = false
            transcriptWasAtLatestWhenExpandedDragStarted = false
            expandedScrollWasAtLatestWhenDragStarted = false
        }
        .accessibilityAction(
            named: Text(showsTranscript ? strings.voiceTutorCallCollapseConversation : strings.voiceTutorCallRevealConversation)
        ) {
            setTranscriptExpanded(!showsTranscript)
        }
    }

    private func compactCall(in geometry: GeometryProxy) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                Spacer(minLength: max(12, geometry.size.height * 0.08))

                callOrb(diameter: compactOrbDiameter(in: geometry))

                // The normal collapsed call is intentionally only the orb.
                // Connection, recording, pause and failure notices remain
                // visible because hiding those states would be misleading.
                callNotices
                summaryRow
                if showsSummary && presentation.summaryState == .ready {
                    VoiceTutorResultSections(detail: presentation.detail, strings: strings)
                }
                terminalControls

                Spacer(minLength: 12)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, minHeight: geometry.size.height)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    private func expandedCall(in geometry: GeometryProxy) -> some View {
        ScrollView {
            VStack(spacing: 14) {
                VStack(spacing: 10) {
                    transcriptAffordance(expanded: true)
                    callOrb(diameter: expandedOrbDiameter(in: geometry))

                    Text(topic)
                        .font(.subheadline.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
                        .fixedSize(horizontal: false, vertical: true)

                    expandedTime
                    callNotices
                }
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())

                transcriptPanel
                    .frame(height: transcriptHeight(in: geometry))
                    .background {
                        GeometryReader { transcriptGeometry in
                            Color.clear.preference(
                                key: VoiceTutorTranscriptFramePreferenceKey.self,
                                value: transcriptGeometry.frame(in: .named(expandedCallCoordinateSpace))
                            )
                        }
                    }

                summaryRow
                if showsSummary && presentation.summaryState == .ready {
                    VoiceTutorResultSections(detail: presentation.detail, strings: strings)
                }
                expandedControls
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            .background {
                GeometryReader { contentGeometry in
                    Color.clear.preference(
                        key: VoiceTutorExpandedContentFramePreferenceKey.self,
                        value: contentGeometry.frame(in: .named(expandedCallCoordinateSpace))
                    )
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .background {
            GeometryReader { viewportGeometry in
                Color.clear.preference(
                    key: VoiceTutorExpandedViewportHeightPreferenceKey.self,
                    value: viewportGeometry.size.height
                )
            }
        }
        .coordinateSpace(name: expandedCallCoordinateSpace)
        .onPreferenceChange(VoiceTutorTranscriptFramePreferenceKey.self) { frame in
            transcriptFrame = frame
        }
        .onPreferenceChange(VoiceTutorExpandedContentFramePreferenceKey.self) { frame in
            expandedContentFrame = frame
        }
        .onPreferenceChange(VoiceTutorExpandedViewportHeightPreferenceKey.self) { height in
            expandedViewportHeight = height
        }
        .simultaneousGesture(expandedTranscriptDragGesture, including: .all)
    }

    @ViewBuilder
    private func callOrb(diameter: CGFloat) -> some View {
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
                .accessibilityIdentifier("voiceCall.orb")
        }
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

            if presentation.microphoneIsMuted && !presentation.pauseState.holdsMicrophone {
                Image(systemName: "mic.slash.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(8)
                    .background(Color.secondary, in: Circle())
                    .overlay(Circle().stroke(Color(uiColor: .systemBackground), lineWidth: 3))
                    .offset(x: diameter * 0.31, y: diameter * 0.31)
                    .accessibilityHidden(true)
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

            if presentation.needsVisibleStatus(strings, errorMessage: errorMessage) {
                HStack(spacing: 6) {
                    Image(systemName: statusSymbol)
                        .foregroundStyle(statusColor)
                    Text(presentation.statusText(strings, errorMessage: errorMessage))
                }
                .font(.caption)
                .foregroundStyle(showsConnectionFailure ? Color.red : Color.secondary)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("voiceCall.status")
            }

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

    private var expandedTime: some View {
        Group {
            switch presentation.remainingTime {
            case .call(let seconds):
                Label(strings.voiceTutorCallRemaining(seconds), systemImage: "clock")
                    .accessibilityIdentifier("voiceCall.remainingTime")
            case .monthly(let seconds):
                Label(strings.voiceTutorCallMonthlyRemaining(seconds), systemImage: "clock")
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
            .background(Color.secondary.opacity(0.05), in: RoundedRectangle(cornerRadius: 18))
            .simultaneousGesture(transcriptFollowGesture, including: .all)
            .onAppear {
                transcriptFollowState.reset()
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
            .onChange(of: captions.last?.id) { _, _ in
                guard transcriptFollowState.shouldAutoScrollForContentChange else { return }
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
            .onChange(of: assistantTranscriptDraft) { _, _ in
                guard transcriptFollowState.shouldAutoScrollForContentChange else { return }
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
            .onChange(of: transcriptDragIsActive) { wasActive, isActive in
                guard wasActive, !isActive else { return }
                observeTranscriptLayout()
                scheduleTranscriptScrollSettlement(using: proxy)
            }
            .onPreferenceChange(VoiceTutorTranscriptContentFramePreferenceKey.self) { frame in
                transcriptContentFrame = frame
                observeTranscriptLayout()
                if transcriptFollowState.shouldScheduleSettlement(
                    isGestureActive: transcriptDragIsActive
                ) {
                    scheduleTranscriptScrollSettlement(using: proxy)
                }
            }
            .onPreferenceChange(VoiceTutorTranscriptViewportHeightPreferenceKey.self) { height in
                transcriptViewportHeight = height
                observeTranscriptLayout()
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

    private func transcriptAffordance(expanded: Bool) -> some View {
        Button {
            setTranscriptExpanded(!expanded)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: expanded ? "chevron.up" : "chevron.down")
                    .font(.caption.weight(.semibold))
                Text(expanded ? strings.voiceTutorCallCollapseConversation : strings.voiceTutorCallRevealConversation)
                    .font(.caption2)
                    .multilineTextAlignment(.center)
            }
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(expanded ? "voiceCall.collapseTranscript" : "voiceCall.revealTranscript")
    }

    @ViewBuilder
    private var expandedControls: some View {
        switch presentation.primaryAction {
        case .end:
            responsiveControlLayout {
                neutralControl(
                    title: presentation.microphoneIsMuted ? strings.voiceTutorUnmute : strings.voiceTutorMute,
                    symbol: presentation.microphoneIsMuted ? "mic.slash.fill" : "mic.fill",
                    selected: presentation.microphoneIsMuted,
                    enabled: presentation.canMute,
                    identifier: "voiceCall.mute",
                    action: onMute
                )
                destructiveControl(
                    title: strings.voiceTutorCallEnd,
                    symbol: "phone.down.fill",
                    identifier: "voiceCall.end",
                    action: onEnd
                )
            }
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

    private func transcriptDragGesture(isExpanded: Bool) -> some Gesture {
        DragGesture(minimumDistance: 12)
            .onEnded { value in
                guard let action = VoiceTutorCallTranscriptGesture.action(
                    translation: value.translation,
                    isExpanded: isExpanded
                ) else { return }
                setTranscriptExpanded(action == .reveal)
            }
    }

    private var expandedTranscriptDragGesture: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .named(expandedCallCoordinateSpace))
            .updating($expandedDragIsActive) { _, isActive, _ in
                isActive = true
            }
            .onChanged { _ in
                guard !expandedDragHasStarted else { return }
                expandedDragHasStarted = true
                transcriptWasAtLatestWhenExpandedDragStarted = transcriptIsAtLatest
                expandedScrollWasAtLatestWhenDragStarted = expandedScrollIsAtLatest
            }
            .onEnded { value in
                let transcriptWasAtLatestAtStart = expandedDragHasStarted
                    ? transcriptWasAtLatestWhenExpandedDragStarted
                    : transcriptIsAtLatest
                let expandedScrollWasAtLatestAtStart = expandedDragHasStarted
                    ? expandedScrollWasAtLatestWhenDragStarted
                    : expandedScrollIsAtLatest
                expandedDragHasStarted = false
                transcriptWasAtLatestWhenExpandedDragStarted = false
                expandedScrollWasAtLatestWhenDragStarted = false
                guard VoiceTutorCallTranscriptGesture.expandedSurfaceAction(
                    translation: value.translation,
                    startLocation: value.startLocation,
                    transcriptFrame: transcriptFrame,
                    transcriptWasAtLatestAtStart: transcriptWasAtLatestAtStart,
                    expandedScrollWasAtLatestAtStart: expandedScrollWasAtLatestAtStart
                ) == .collapse else { return }
                setTranscriptExpanded(false)
            }
    }

    private var expandedCallCoordinateSpace: String {
        "voiceTutorCall.expanded"
    }

    private var transcriptScrollCoordinateSpace: String {
        "voiceTutorCall.transcriptScroll"
    }

    private var transcriptIsAtLatest: Bool {
        VoiceTutorCallTranscriptGesture.transcriptIsAtLatest(
            contentFrame: transcriptContentFrame,
            viewportHeight: transcriptViewportHeight
        )
    }

    private var expandedScrollIsAtLatest: Bool {
        VoiceTutorCallTranscriptGesture.transcriptIsAtLatest(
            contentFrame: expandedContentFrame,
            viewportHeight: expandedViewportHeight
        )
    }

    private func setTranscriptExpanded(_ expanded: Bool) {
        resetExpandedGestureRouting()
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.24)) {
            showsTranscript = expanded
        }
    }

    private func resetExpandedGestureRouting() {
        transcriptScrollSettleTask?.cancel()
        transcriptScrollSettleTask = nil
        transcriptFollowState.reset()
        expandedDragHasStarted = false
        transcriptWasAtLatestWhenExpandedDragStarted = false
        expandedScrollWasAtLatestWhenDragStarted = false
        transcriptFrame = .null
        transcriptContentFrame = .null
        transcriptViewportHeight = 0
        expandedContentFrame = .null
        expandedViewportHeight = 0
    }

    private func compactOrbDiameter(in geometry: GeometryProxy) -> CGFloat {
        let available = min(geometry.size.width - 64, geometry.size.height * 0.43)
        return min(max(148, preferredOrbDiameter), max(148, available))
    }

    private func expandedOrbDiameter(in geometry: GeometryProxy) -> CGFloat {
        min(max(72, preferredExpandedOrbDiameter), min(112, geometry.size.width * 0.3))
    }

    private func transcriptHeight(in geometry: GeometryProxy) -> CGFloat {
        let fraction = dynamicTypeSize.isAccessibilitySize ? 0.48 : 0.52
        return min(360, max(190, geometry.size.height * fraction))
    }

    private var orbScale: CGFloat {
        if presentation.orbState == .paused { return 0.84 }
        guard presentation.orbAnimates else { return 1 }
        return orbPulseExpanded ? 1.035 : 0.985
    }

    private var orbTint: Color {
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
        case .failed: return "wifi.exclamationmark"
        }
    }

    private var orbActionLabel: String {
        orbRepresentsResume ? strings.voiceTutorResumeLesson : strings.voiceTutorTakeBreak
    }

    private var orbActionHint: String {
        orbRepresentsResume ? strings.voiceTutorOrbResumeHint : strings.voiceTutorOrbPauseHint
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
        if presentation.phase.isLive && presentation.pauseState.mode == .paused { return "pause.fill" }
        switch presentation.phase {
        case .speaking: return "waveform"
        case .listening: return presentation.isMuted ? "mic.slash" : "phone.fill"
        case .failed: return "wifi.exclamationmark"
        case .ended: return "phone.down"
        default: return "phone"
        }
    }

    private var statusColor: Color {
        if showsConnectionFailure { return .red }
        if presentation.phase.isLive && presentation.pauseState.holdsMicrophone { return .secondary }
        switch presentation.phase {
        case .failed: return .red
        case .speaking: return .accentColor
        case .listening: return presentation.isMuted ? .secondary : .green
        default: return .secondary
        }
    }

    private var showsConnectionFailure: Bool {
        presentation.showsConnectionFailure(strings, errorMessage: errorMessage)
    }

}

private struct VoiceTutorCaptionBubble: View {
    var caption: VoiceTutorCaption
    var strings: AppStrings

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(caption.speaker == .learner ? strings.voiceTutorYou : strings.voiceTutorTeacher)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(caption.text)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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
