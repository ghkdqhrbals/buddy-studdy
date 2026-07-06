import Foundation
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
import UserNotifications
#endif

@MainActor
protocol AppPlatformEffectsProviding {
    func runExpiringBackgroundTask(
        named name: String,
        operation: @MainActor @escaping (_ isExpired: @escaping @Sendable () -> Bool) async -> Int
    ) async -> Int

    func setApplicationIconBadge(_ count: Int)
    func open(_ url: URL)
    func uninstallApplication() throws
}

enum AppPlatformEffectError: LocalizedError {
    case unsupportedPlatform(String)

    var errorDescription: String? {
        switch self {
        case let .unsupportedPlatform(platform):
            "\(platform) does not support this platform effect."
        }
    }
}

@MainActor
struct DefaultAppPlatformEffectsProvider: AppPlatformEffectsProviding {
    func runExpiringBackgroundTask(
        named name: String,
        operation: @MainActor @escaping (_ isExpired: @escaping @Sendable () -> Bool) async -> Int
    ) async -> Int {
        #if os(iOS)
        let expiration = AppBackgroundTaskExpiration()
        let taskIdentifier = UIApplication.shared.beginBackgroundTask(withName: name) {
            expiration.expire()
        }
        defer {
            if taskIdentifier != .invalid {
                UIApplication.shared.endBackgroundTask(taskIdentifier)
            }
        }

        return await operation {
            expiration.isExpired
        }
        #else
        return await operation {
            false
        }
        #endif
    }

    func setApplicationIconBadge(_ count: Int) {
        #if os(iOS)
        Task { @MainActor in
            let badgeCount = max(0, count)
            if #available(iOS 17.0, *) {
                try? await UNUserNotificationCenter.current().setBadgeCount(badgeCount)
            } else {
                UIApplication.shared.applicationIconBadgeNumber = badgeCount
            }
        }
        #endif
    }

    func open(_ url: URL) {
        #if os(macOS)
        NSWorkspace.shared.open(url)
        #elseif os(iOS)
        UIApplication.shared.open(url)
        #endif
    }

    func uninstallApplication() throws {
        #if os(macOS)
        try launchUninstaller(for: Bundle.main.bundleURL)
        NSApp.terminate(nil)
        #else
        throw AppPlatformEffectError.unsupportedPlatform("iOS")
        #endif
    }

    #if os(macOS)
    private func launchUninstaller(for appURL: URL) throws {
        let scriptURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("studymate-uninstall-\(UUID().uuidString).sh")
        let script = Self.makeUninstallScript(appPath: appURL.path)

        try script.write(to: scriptURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: scriptURL.path)

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = [
            "-c",
            "nohup /bin/sh \(Self.shellEscaped(scriptURL.path)) >/dev/null 2>&1 &"
        ]
        try process.run()
        process.waitUntilExit()

        if process.terminationStatus != 0 {
            throw CocoaError(.executableLoad)
        }
    }

    nonisolated private static func makeUninstallScript(appPath: String) -> String {
        let escapedAppPath = shellEscaped(appPath)
        let escapedHomeApplicationsPath = shellEscaped("~/Applications/StudyMate.app")

        return """
        #!/bin/sh
        set +e

        APP_PATH=\(escapedAppPath)
        LOG_PATH="${TMPDIR:-/tmp}/studymate-uninstall.log"

        echo "StudyMate uninstall started at $(date)" > "${LOG_PATH}"

        /usr/bin/osascript -e 'tell application id "io.github.ghkdqhrbals.StudyMate" to quit' >> "${LOG_PATH}" 2>&1
        /usr/bin/osascript -e 'tell application "StudyMate" to quit' >> "${LOG_PATH}" 2>&1

        ATTEMPT=0
        while /usr/bin/pgrep -x "StudyMate" >/dev/null 2>&1 && [ "${ATTEMPT}" -lt 30 ]; do
          /bin/sleep 0.2
          ATTEMPT=$((ATTEMPT + 1))
        done

        /usr/bin/pkill -x "StudyMate" >> "${LOG_PATH}" 2>&1
        /bin/sleep 0.5

        remove_path() {
          TARGET_PATH="$1"
          EXPANDED_PATH="$(eval printf '%s' "${TARGET_PATH}")"

          [ -e "${EXPANDED_PATH}" ] || return 0
          echo "Removing ${EXPANDED_PATH}" >> "${LOG_PATH}"

          TRASH_TARGET="${HOME}/.Trash/$(basename "${EXPANDED_PATH}")-$(date +%Y%m%d%H%M%S)"
          /bin/mv "${EXPANDED_PATH}" "${TRASH_TARGET}" >> "${LOG_PATH}" 2>&1
          [ ! -e "${EXPANDED_PATH}" ] && return 0

          /bin/rm -rf "${EXPANDED_PATH}" >> "${LOG_PATH}" 2>&1
          [ ! -e "${EXPANDED_PATH}" ] && return 0

          ESCAPED_TARGET="$(printf "%s" "${EXPANDED_PATH}" | /usr/bin/sed "s/'/'\\\\''/g")"
          /usr/bin/osascript -e "do shell script \\"/bin/rm -rf '${ESCAPED_TARGET}'\\" with administrator privileges" >> "${LOG_PATH}" 2>&1
          [ ! -e "${EXPANDED_PATH}" ] && return 0

          echo "Failed to remove ${EXPANDED_PATH}" >> "${LOG_PATH}"
        }

        remove_path "${APP_PATH}"
        remove_path "/Applications/StudyMate.app"
        remove_path \(escapedHomeApplicationsPath)

        remove_data() {
          BUNDLE_ID="$1"
          /usr/bin/defaults delete "${BUNDLE_ID}" >> "${LOG_PATH}" 2>&1
          /bin/rm -f "${HOME}/Library/Preferences/${BUNDLE_ID}.plist"
          /bin/rm -rf "${HOME}/Library/Application Support/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Caches/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Caches/Sparkle/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Logs/${BUNDLE_ID}"
          /bin/rm -rf "${HOME}/Library/Saved Application State/${BUNDLE_ID}.savedState"
        }

        remove_data "io.github.ghkdqhrbals.StudyMate"
        remove_data "com.local.StudyMate"

        /usr/bin/osascript -e 'display dialog "사용해주셔서 감사합니다." with title "BuddyStudy" buttons {"확인"} default button "확인" giving up after 8' >> "${LOG_PATH}" 2>&1
        echo "StudyMate uninstall finished at $(date)" >> "${LOG_PATH}"
        /bin/rm -f "$0"
        """
    }

    nonisolated private static func shellEscaped(_ value: String) -> String {
        "'\(value.replacingOccurrences(of: "'", with: "'\\''"))'"
    }
    #endif
}

#if os(iOS)
private final class AppBackgroundTaskExpiration: @unchecked Sendable {
    private let lock = NSLock()
    private var expired = false

    var isExpired: Bool {
        lock.lock()
        defer {
            lock.unlock()
        }

        return expired
    }

    func expire() {
        lock.lock()
        expired = true
        lock.unlock()
    }
}
#endif
