import SwiftUI
import AppKit

@main
@MainActor
struct StudyMateApp: App {
    @NSApplicationDelegateAdaptor(StudyMateAppDelegate.self) private var appDelegate
    @StateObject private var appState: AppState

    init() {
        let appState = AppState()
        _appState = StateObject(wrappedValue: appState)
        StudyNotificationDelegate.shared.configure(appState: appState)

        Task { @MainActor in
            await appState.start()
            if !appState.hasCompletedOnboarding {
                StudyWindowPresenter.shared.show(appState: appState)
            }
        }
    }

    var body: some Scene {
        MenuBarExtra {
            MenuBarMenuView()
                .environmentObject(appState)
        } label: {
            MenuBarIcon(isRunning: appState.isRunning, hasAPIKeyError: appState.hasAPIKeyError)
        }
    }
}

final class StudyMateAppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }
}

private struct MenuBarMenuView: View {
    @EnvironmentObject private var appState: AppState

    private let timerOptions = [1, 5, 10, 15, 30, 45, 60, 120]

    var body: some View {
        let strings = appState.strings

        StatusMenuRow(isRunning: appState.isRunning, title: strings.statusTitle(isRunning: appState.isRunning))

        if appState.hasAPIKeyError {
            Text("⚠ \(strings.invalidAPIKey)")
                .foregroundStyle(.orange)
        }

        Divider()

        Button {
            StudyWindowPresenter.shared.show(appState: appState)
        } label: {
            Label(strings.openStudy, systemImage: "book")
        }
        .keyboardShortcut("o", modifiers: .command)

        Button {
            NSApp.activate(ignoringOtherApps: true)
            NSApp.orderFrontStandardAboutPanel(options: [
                .applicationName: "BuddyStudy",
                .applicationVersion: "1.0",
                .credits: NSAttributedString(string: "AI teacher menu bar app")
            ])
            AppWindowFocus.bringWindowToFront(named: "About BuddyStudy")
        } label: {
            Label(strings.aboutStudyMate, systemImage: "info.circle")
        }

        Menu(strings.timerTitle(minutes: appState.settings.sanitizedIntervalMinutes)) {
            ForEach(timerOptions, id: \.self) { minutes in
                Button {
                    appState.setTimerInterval(minutes)
                    NSApp.activate(ignoringOtherApps: true)
                } label: {
                    if appState.settings.sanitizedIntervalMinutes == minutes {
                        Label(strings.minuteLabel(minutes), systemImage: "checkmark")
                    } else {
                        Text(strings.minuteLabel(minutes))
                    }
                }
            }
        }

        Menu(strings.languageMenu) {
            ForEach(AppLanguage.allCases) { language in
                Button {
                    appState.setAppLanguage(language)
                } label: {
                    if appState.settings.appLanguage == language {
                        Text("✓ \(language.displayName)")
                    } else {
                        Text("  \(language.displayName)")
                    }
                }
            }
        }

        Divider()

        Button {
            appState.setRunning(!appState.isRunning)
            NSApp.activate(ignoringOtherApps: true)
        } label: {
            MenuActionLabel(
                title: appState.isRunning ? strings.pause : strings.resume,
                systemImage: appState.isRunning ? "pause.fill" : "play.fill"
            )
        }
        .keyboardShortcut("p", modifiers: .command)

        Button {
            NSApp.terminate(nil)
        } label: {
            MenuActionLabel(title: strings.quit, systemImage: "power")
        }
        .keyboardShortcut("q", modifiers: .command)
    }
}

private struct MenuActionLabel: View {
    var title: String
    var systemImage: String

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: systemImage)
                .font(.system(size: 12, weight: .semibold))
                .frame(width: 15, alignment: .center)
        }
    }
}

private struct MenuBarIcon: View {
    var isRunning: Bool
    var hasAPIKeyError: Bool

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            BookStarGlyph(isRunning: isRunning)
                .frame(width: 21, height: 18)
                .padding(.trailing, hasAPIKeyError ? 3 : 0)

            if hasAPIKeyError {
                WarningBadge()
                    .frame(width: 10, height: 10)
                    .offset(x: 1, y: 1)
            }
        }
        .frame(width: 26, height: 20, alignment: .center)
    }
}

private struct BookStarGlyph: View {
    var isRunning: Bool

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Image(systemName: isRunning ? "book.fill" : "book.closed.fill")
                .font(.system(size: 16, weight: .semibold))
                .symbolRenderingMode(.hierarchical)

            Image(systemName: "star.fill")
                .font(.system(size: 6.5, weight: .black))
                .offset(x: 1.5, y: -1)
        }
        .foregroundStyle(.primary.opacity(isRunning ? 1 : 0.62))
        .accessibilityLabel("BuddyStudy")
    }
}

private struct WarningBadge: View {
    var body: some View {
        ZStack {
            Image(systemName: "exclamationmark.circle.fill")
                .font(.system(size: 10, weight: .black))
                .symbolRenderingMode(.palette)
                .foregroundStyle(.white, .orange)
        }
        .overlay {
            Circle()
                .stroke(.white.opacity(0.95), lineWidth: 0.8)
        }
        .shadow(color: .black.opacity(0.35), radius: 0.6, x: 0, y: 0)
        .accessibilityLabel("Invalid API key")
    }
}

private struct StatusMenuRow: View {
    var isRunning: Bool
    var title: String

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Circle()
                .fill(isRunning ? Color.green : Color.orange)
                .frame(width: 8, height: 8)
        }
    }
}

private enum AppWindowFocus {
    static func bringWindowToFront(named title: String, attempt: Int = 0) {
        let delay = attempt == 0 ? 0.05 : 0.12

        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            NSApp.activate(ignoringOtherApps: true)

            if let window = NSApp.windows.first(where: { $0.title == title || $0.title.contains(title) }) {
                window.level = .floating
                window.makeKeyAndOrderFront(nil)

                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                    window.level = .normal
                }
            } else if attempt < 4 {
                bringWindowToFront(named: title, attempt: attempt + 1)
            }
        }
    }
}
