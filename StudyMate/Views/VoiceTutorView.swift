#if os(iOS)
import SwiftUI

struct VoiceTutorView: View {
    @EnvironmentObject private var appState: AppState
    @State private var selectedStudyID: Int?
    @State private var showsMembership = false

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
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Label(strings.voiceTutorTitle, systemImage: "waveform.and.mic")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.primary)

                    Text(strings.voiceTutorSubtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 4)
            }

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

                        if let selectedStudy {
                            NavigationLink {
                                VoiceTutorSessionView(appState: appState, study: selectedStudy)
                            } label: {
                                Label(strings.voiceTutorStart, systemImage: "mic.circle.fill")
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
        .task {
            await appState.refreshVoiceTutorStatus()
            await appState.loadVoiceTutorSessions(reset: true)
            selectFirstStudyIfNeeded()
        }
        .refreshable {
            await appState.refreshVoiceTutorStatus()
            await appState.loadVoiceTutorSessions(reset: true)
            selectFirstStudyIfNeeded()
        }
        .onChange(of: appState.voiceTutorStudies.map(\.id)) { _, _ in
            selectFirstStudyIfNeeded()
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
        max(0, status.quota.usedSeconds + status.quota.reservedSeconds)
    }

    private var progress: Double {
        guard status.quota.limitSeconds > 0 else {
            return 0
        }
        return min(1, Double(usedSeconds) / Double(status.quota.limitSeconds))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(strings.voiceTutorRemainingTime(status.quota.remainingSeconds))
                    .font(.headline)
                Spacer()
                Text(strings.membershipTierName(status.tierCode))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tint)
            }

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

            if status.maxSessionSeconds > 0 {
                Text(strings.voiceTutorSessionLimit(status.maxSessionSeconds))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if status.quota.remainingSeconds == 0 {
                Text(strings.voiceTutorQuotaReached)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.orange)
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
    private let strings: AppStrings

    init(appState: AppState, study: BackendStudyRoom) {
        _viewModel = StateObject(
            wrappedValue: VoiceTutorViewModel(appState: appState, study: study)
        )
        strings = appState.strings
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                sessionStatus
                quotaSummary
                captionPanel

                if let errorMessage = viewModel.errorMessage, !errorMessage.isEmpty {
                    Text(errorMessage)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(.red.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                }

                if viewModel.phase == .ended {
                    VoiceTutorResultSections(detail: viewModel.detail, strings: strings)
                }

                Text(strings.voiceTutorForegroundOnly)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            .padding(16)
        }
        .safeAreaInset(edge: .bottom) {
            controls
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial)
        }
        .navigationTitle(viewModel.study.topic)
        .navigationBarTitleDisplayMode(.inline)
        .task {
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

    private var sessionStatus: some View {
        VStack(spacing: 10) {
            Image(systemName: statusSymbol)
                .font(.system(size: 46, weight: .semibold))
                .foregroundStyle(statusColor)
                .symbolEffect(.pulse, options: .repeating, isActive: viewModel.phase.isLive)

            Text(statusText)
                .font(.title3.weight(.semibold))

            if let sessionSecondsRemaining = viewModel.sessionSecondsRemaining {
                Text(strings.voiceTutorSessionRemaining(sessionSecondsRemaining))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    private var quotaSummary: some View {
        HStack {
            Label(
                strings.voiceTutorRemainingTime(viewModel.quotaRemainingSeconds),
                systemImage: "clock"
            )
            .font(.subheadline.weight(.medium))
            Spacer()
            if viewModel.quotaLimitSeconds > 0 {
                Text(strings.voiceTutorMonthlyAllowance(viewModel.quotaLimitSeconds))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(13)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
    }

    private var captionPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(strings.voiceTutorLiveCaptions)
                .font(.headline)

            if viewModel.captions.isEmpty && viewModel.assistantTranscriptDraft.isEmpty {
                Text(strings.voiceTutorListening)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 100, alignment: .center)
            } else {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(viewModel.captions) { caption in
                        VoiceTutorCaptionBubble(caption: caption, strings: strings)
                    }
                    if !viewModel.assistantTranscriptDraft.isEmpty {
                        VoiceTutorCaptionBubble(
                            caption: VoiceTutorCaption(
                                speaker: .tutor,
                                text: viewModel.assistantTranscriptDraft
                            ),
                            strings: strings
                        )
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 16))
    }

    private var controls: some View {
        HStack(spacing: 14) {
            if viewModel.phase.isLive {
                Button {
                    viewModel.toggleMute()
                } label: {
                    Label(
                        viewModel.isMuted ? strings.voiceTutorUnmute : strings.voiceTutorMute,
                        systemImage: viewModel.isMuted ? "mic.slash.fill" : "mic.fill"
                    )
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                Button(role: .destructive) {
                    Task {
                        await viewModel.stopForUser()
                    }
                } label: {
                    Label(strings.voiceTutorEndSession, systemImage: "phone.down.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            } else if viewModel.phase == .failed {
                Button(strings.retry) {
                    Task {
                        await viewModel.start()
                    }
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            } else if viewModel.phase == .ended {
                Button(strings.done) {
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity)
            }
        }
    }

    private var statusText: String {
        switch viewModel.phase {
        case .idle, .requestingPermission, .connecting:
            return strings.voiceTutorConnecting
        case .listening:
            return strings.voiceTutorListening
        case .speaking:
            return strings.voiceTutorSpeaking
        case .ending:
            return strings.voiceTutorEnding
        case .ended:
            return strings.voiceTutorEnded
        case .failed:
            return strings.voiceTutorConnectionFailed
        }
    }

    private var statusSymbol: String {
        switch viewModel.phase {
        case .speaking:
            return "waveform.circle.fill"
        case .listening:
            return "ear.fill"
        case .ending, .ended:
            return "checkmark.circle.fill"
        case .failed:
            return "exclamationmark.triangle.fill"
        case .idle, .requestingPermission, .connecting:
            return "ellipsis.circle.fill"
        }
    }

    private var statusColor: Color {
        switch viewModel.phase {
        case .failed:
            return .red
        case .ended:
            return .green
        default:
            return .accentColor
        }
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
