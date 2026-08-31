#if os(iOS)
import AVFoundation
import SwiftUI

struct VoiceTutorView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedStudyID: Int?
    @State private var showsMembership = false
    @State private var recordingConsent = false
    @State private var callStudy: BackendStudyRoom?
    @State private var callRecordingConsent = false
    @State private var showsCall = false

    private var strings: AppStrings { appState.strings }

    private var selectedStudy: BackendStudyRoom? {
        appState.voiceTutorStudies.first { $0.id == selectedStudyID }
    }

    private var status: BackendVoiceTutorStatus? {
        appState.voiceTutorStatus
    }

    private var canStart: Bool {
        status?.eligible == true
            && (status?.quota.remainingSeconds ?? 0) > 0
            && status?.activeSession == nil
            && selectedStudy != nil
    }

    var body: some View {
        List {
            voiceAccessSection

            if status?.activeSession != nil
                || (status?.eligible == true && (status?.quota.remainingSeconds ?? 0) > 0) {
                Section(strings.voiceTutorSelectTopic) {
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
                    } else if appState.voiceTutorStudies.isEmpty {
                        Label(strings.voiceTutorNoTopics, systemImage: "books.vertical")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Picker(strings.voiceTutorSelectTopic, selection: $selectedStudyID) {
                            ForEach(appState.voiceTutorStudies) { study in
                                Text(study.topic)
                                    .tag(Optional(study.id))
                            }
                        }
                        .pickerStyle(.menu)

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

                        if let selectedStudy {
                            Button {
                                // The destination must outlive quota reservation:
                                // reserving the allowance removes this start row.
                                callStudy = selectedStudy
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
            if let callStudy {
                VoiceTutorSessionView(
                    appState: appState,
                    study: callStudy,
                    recordingConsent: callRecordingConsent,
                    onRecordingConsentConsumed: { recordingConsent = false }
                )
            }
        }
        .task {
            recordingConsent = false
            await appState.retryPendingVoiceTutorRecordingUploads()
            await appState.refreshVoiceTutorStatus()
            await appState.loadVoiceTutorSessions(reset: true)
            selectFirstStudyIfNeeded()
        }
        .refreshable {
            recordingConsent = false
            await appState.refreshVoiceTutorStatus()
            await appState.loadVoiceTutorSessions(reset: true)
            selectFirstStudyIfNeeded()
        }
        .onChange(of: appState.voiceTutorStudies.map(\.id)) { _, _ in
            selectFirstStudyIfNeeded()
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

    private func selectFirstStudyIfNeeded() {
        guard selectedStudy == nil else {
            return
        }
        selectedStudyID = appState.voiceTutorStudies.first?.id
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

struct VoiceTutorSessionView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @StateObject private var viewModel: VoiceTutorViewModel
    @State private var showsTranscript = false
    @State private var showsSummary = false
    private let strings: AppStrings
    private let onRecordingConsentConsumed: () -> Void

    init(
        appState: AppState,
        study: BackendStudyRoom,
        recordingConsent: Bool,
        onRecordingConsentConsumed: @escaping () -> Void = {}
    ) {
        _viewModel = StateObject(
            wrappedValue: VoiceTutorViewModel(
                appState: appState,
                study: study,
                recordingConsent: recordingConsent
            )
        )
        strings = appState.strings
        self.onRecordingConsentConsumed = onRecordingConsentConsumed
    }

    var body: some View {
        VoiceTutorCallScreen(
            topic: viewModel.study.topic,
            presentation: VoiceTutorCallPresentation(
                phase: viewModel.phase,
                isMuted: viewModel.isMuted,
                isRecording: viewModel.isRecording,
                inputNeedsRepeat: viewModel.inputNeedsRepeat,
                sessionSecondsRemaining: viewModel.sessionSecondsRemaining,
                quotaRemainingSeconds: viewModel.quotaRemainingSeconds,
                quotaReservedSeconds: viewModel.quotaReservedSeconds,
                quotaLimitSeconds: viewModel.quotaLimitSeconds,
                detail: viewModel.detail
            ),
            strings: strings,
            captions: viewModel.captions,
            assistantTranscriptDraft: viewModel.assistantTranscriptDraft,
            errorMessage: viewModel.errorMessage,
            showsTranscript: $showsTranscript,
            showsSummary: $showsSummary,
            onMute: { viewModel.toggleMute() },
            onEnd: { Task { await viewModel.stopForUser() } },
            onRetry: {
                showsSummary = false
                Task { await viewModel.start() }
            },
            onDismiss: { dismiss() }
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
        case hidden, pending, ready, failed
    }

    enum PrimaryAction: Equatable {
        case end, retry, dismiss, wait
    }

    var phase: VoiceTutorSessionPhase
    var isMuted = false
    var isRecording = false
    var inputNeedsRepeat = false
    var sessionSecondsRemaining: Int?
    var quotaRemainingSeconds = 0
    var quotaReservedSeconds = 0
    var quotaLimitSeconds = 0
    var detail: BackendVoiceTutorSessionDetail?

    var canMute: Bool { phase == .listening || phase == .speaking }

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
        guard phase == .ended || phase == .failed, let detail else { return .hidden }
        let statuses = [detail.resultStatus, detail.result?.status].compactMap { $0?.uppercased() }
        if statuses.contains("FAILED") { return .failed }
        if statuses.contains("PENDING") || statuses.contains("PROCESSING") { return .pending }
        guard let result = detail.result else { return .hidden }
        let hasContent = !result.summaryMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || [result.strengths, result.improvements, result.nextSteps].joined().contains {
                !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
        return hasContent && statuses.contains("COMPLETED") ? .ready : .hidden
    }

    func showsConnectionFailure(_ strings: AppStrings, errorMessage: String?) -> Bool {
        phase == .failed || (phase == .ending
            && errorMessage?.trimmingCharacters(in: .whitespacesAndNewlines) == strings.voiceTutorConnectionFailed)
    }

    func statusText(_ strings: AppStrings, errorMessage: String? = nil) -> String {
        if showsConnectionFailure(strings, errorMessage: errorMessage) {
            return strings.voiceTutorCallFailed
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
}

/// The same non-networking surface is rendered by device visual tests.
struct VoiceTutorCallScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var showsInformation = false
    let topic: String
    let presentation: VoiceTutorCallPresentation
    let strings: AppStrings
    var captions: [VoiceTutorCaption] = []
    var assistantTranscriptDraft = ""
    var errorMessage: String?
    @Binding var showsTranscript: Bool
    @Binding var showsSummary: Bool
    var onMute: () -> Void = {}
    var onEnd: () -> Void = {}
    var onRetry: () -> Void = {}
    var onDismiss: () -> Void = {}

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 22) {
                    timeAndRecording
                    Spacer(minLength: 12)
                    callIdentity

                    if showsTranscript {
                        transcriptPanel
                            .frame(height: min(260, geometry.size.height * 0.43))
                            .transition(.opacity)
                    }

                    summaryRow
                    if showsSummary && presentation.summaryState == .ready {
                        VoiceTutorResultSections(detail: presentation.detail, strings: strings)
                    }
                    Spacer(minLength: 12)
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background(Color(uiColor: .systemBackground))
        .safeAreaInset(edge: .bottom) {
            controls
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 20)
                .background(Color(uiColor: .systemBackground))
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showsInformation = true
                } label: {
                    Image(systemName: "info.circle")
                }
                .accessibilityLabel(strings.voiceTutorCallDetails)
            }
        }
        .alert(strings.voiceTutorCallDetails, isPresented: $showsInformation) {
            Button(strings.done, role: .cancel) {}
        } message: {
            Text(informationText)
        }
    }

    private var timeAndRecording: some View {
        VStack(spacing: 8) {
            Group {
                switch presentation.remainingTime {
                case .call(let seconds):
                    Label(strings.voiceTutorCallRemaining(seconds), systemImage: "clock")
                case .monthly(let seconds):
                    Label(strings.voiceTutorCallMonthlyRemaining(seconds), systemImage: "clock")
                case nil:
                    Color.clear.frame(height: 16).accessibilityHidden(true)
                }
            }
            .font(.subheadline.monospacedDigit())
            .foregroundStyle(.secondary)
            .accessibilityIdentifier("voiceCall.remainingTime")

            if presentation.isRecording {
                Label(strings.voiceTutorCallRecording, systemImage: "record.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("voiceCall.recording")
            }
        }
    }

    private var callIdentity: some View {
        VStack(spacing: 12) {
            if !showsTranscript && !dynamicTypeSize.isAccessibilitySize {
                Image(systemName: presentation.phase == .speaking ? "waveform" : "person.fill")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(presentation.phase == .speaking ? Color.accentColor : .secondary)
                    .frame(width: 60, height: 60)
                    .background(Color.secondary.opacity(0.08), in: Circle())
                    .symbolEffect(.pulse, options: .repeating, isActive: presentation.phase == .speaking && !reduceMotion)
                    .accessibilityHidden(true)
            }

            Text(topic)
                .font(.title3.weight(.semibold))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 6) {
                if !showsConnectionFailure
                    && [.idle, .requestingPermission, .connecting, .ending].contains(presentation.phase) {
                    ProgressView().controlSize(.mini)
                } else {
                    Image(systemName: statusSymbol)
                        .foregroundStyle(statusColor)
                }
                Text(presentation.statusText(strings, errorMessage: errorMessage))
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("voiceCall.status")

            // Preserve actionable permission/quota errors without repeating the
            // generic disconnect sentence already represented by the status.
            if let error = presentation.supplementaryError(strings, errorMessage: errorMessage) {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(strings.voiceTutorTeacher)
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
            }
            .background(Color.secondary.opacity(0.04), in: RoundedRectangle(cornerRadius: 12))
            .onAppear { proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom) }
            .onChange(of: captions.last?.id) { _, _ in
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
            .onChange(of: assistantTranscriptDraft) { _, _ in
                proxy.scrollTo("voiceCall.latestCaption", anchor: .bottom)
            }
        }
        .accessibilityIdentifier("voiceCall.transcript")
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
        case .failed:
            Label(strings.voiceTutorCallSummaryFailed, systemImage: "exclamationmark.circle")
                .font(.caption)
                .foregroundStyle(.secondary)
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

    private var controls: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 18))
        return layout {
            callButton(
                title: presentation.isMuted ? strings.voiceTutorUnmute : strings.voiceTutorMute,
                symbol: presentation.isMuted ? "mic.slash.fill" : "mic.fill",
                selected: presentation.isMuted,
                enabled: presentation.canMute,
                identifier: "voiceCall.mute",
                action: onMute
            )
            callButton(
                title: strings.voiceTutorCallTranscript,
                symbol: showsTranscript ? "captions.bubble.fill" : "captions.bubble",
                selected: showsTranscript,
                identifier: "voiceCall.captions"
            ) {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.18)) {
                    showsTranscript.toggle()
                }
            }
            switch presentation.primaryAction {
            case .end:
                callButton(title: strings.voiceTutorCallEnd, symbol: "phone.down.fill", tint: .red,
                           identifier: "voiceCall.end", action: onEnd)
            case .retry:
                callButton(title: strings.voiceTutorCallRetry, symbol: "phone.fill", tint: .green,
                           identifier: "voiceCall.retry", action: onRetry)
            case .dismiss:
                callButton(title: strings.done, symbol: "checkmark", tint: .accentColor,
                           identifier: "voiceCall.done", action: onDismiss)
            case .wait:
                callButton(title: strings.voiceTutorCallEnd, symbol: "phone.down.fill", enabled: false,
                           identifier: "voiceCall.ending", action: {})
            }
        }
    }

    private func callButton(
        title: String,
        symbol: String,
        tint: Color? = nil,
        selected: Bool = false,
        enabled: Bool = true,
        identifier: String,
        action: @escaping () -> Void
    ) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(HStackLayout(alignment: .center, spacing: 14))
            : AnyLayout(VStackLayout(alignment: .center, spacing: 8))
        return Button(action: action) {
            layout {
                Image(systemName: symbol)
                    .font(.system(size: 21, weight: .medium))
                    .foregroundStyle(tint == nil ? Color.primary : .white)
                    .frame(width: 54, height: 54)
                    .background(
                        tint ?? Color.secondary.opacity(selected ? 0.22 : 0.09),
                        in: Circle()
                    )
                Text(title)
                    .font(.caption)
                    .multilineTextAlignment(dynamicTypeSize.isAccessibilitySize ? .leading : .center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(
                maxWidth: .infinity,
                minHeight: dynamicTypeSize.isAccessibilitySize ? 54 : 82,
                alignment: dynamicTypeSize.isAccessibilitySize ? .leading : .top
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(identifier)
    }

    private var statusSymbol: String {
        if showsConnectionFailure { return "wifi.exclamationmark" }
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

    private var informationText: String {
        var parts: [String] = []
        if let errorMessage, !errorMessage.isEmpty { parts.append(errorMessage) }
        if presentation.summaryState == .failed { parts.append(strings.voiceTutorSummaryFailed) }
        parts.append(strings.voiceTutorForegroundOnly)
        return parts.joined(separator: "\n\n")
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
    @State private var isLoading = false

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

                    VoiceTutorResultSections(detail: detail, strings: strings)

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
                        VStack(alignment: .leading, spacing: 12) {
                            Text(strings.voiceTutorLiveCaptions)
                                .font(.headline)
                            ForEach(detail.transcriptTurns) { turn in
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(
                                        turn.role.lowercased() == "user"
                                            ? strings.voiceTutorYou
                                            : strings.voiceTutorTeacher
                                    )
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    Text(turn.text)
                                        .font(.subheadline)
                                }
                            }
                        }
                    }
                } else if isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, minHeight: 180)
                } else {
                    Text(appState.voiceTutorErrorMessage ?? strings.serviceTemporarilyUnavailable)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 180)
                }
            }
            .padding(16)
        }
        .navigationTitle(strings.voiceTutorLearningSummary)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard detail == nil else {
                return
            }
            isLoading = true
            _ = await appState.loadVoiceTutorSessionDetail(sessionID: sessionID)
            isLoading = false
        }
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

    var body: some View {
        if isFailed {
            VStack(alignment: .leading, spacing: 8) {
                Label(strings.voiceTutorLearningSummary, systemImage: "exclamationmark.triangle.fill")
                    .font(.headline)
                Text(strings.voiceTutorSummaryFailed)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
        } else if let result = detail?.result, resultHasContent(result) {
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
                if !result.strengths.isEmpty {
                    bulletSection(title: strings.voiceTutorStrengths, values: result.strengths)
                }
                if !result.improvements.isEmpty {
                    bulletSection(title: strings.voiceTutorImprovements, values: result.improvements)
                }
                if !result.nextSteps.isEmpty {
                    bulletSection(title: strings.voiceTutorNextSteps, values: result.nextSteps)
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text(strings.voiceTutorLearningSummary)
                    .font(.headline)
                Text(strings.voiceTutorSummaryPending)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
        }
    }

    private var isFailed: Bool {
        detail?.resultStatus?.uppercased() == "FAILED"
            || detail?.result?.status?.uppercased() == "FAILED"
    }

    private func resultHasContent(_ result: BackendVoiceTutorSessionResult) -> Bool {
        !result.summaryMarkdown.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !result.strengths.isEmpty
            || !result.improvements.isEmpty
            || !result.nextSteps.isEmpty
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
#endif
