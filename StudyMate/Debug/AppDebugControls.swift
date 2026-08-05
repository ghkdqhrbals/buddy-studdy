#if os(iOS)
import SwiftUI
import UIKit

struct RapidDeveloperUnlockTapTracker {
    static let requiredTapCount = 5
    static let tapWindow: TimeInterval = 2

    private(set) var tapCount = 0
    private var windowStartedAt: Date?

    mutating func registerTap(at now: Date) -> Bool {
        if let windowStartedAt,
           now.timeIntervalSince(windowStartedAt) <= Self.tapWindow {
            tapCount += 1
        } else {
            windowStartedAt = now
            tapCount = 1
        }

        guard tapCount >= Self.requiredTapCount else {
            return false
        }

        tapCount = 0
        windowStartedAt = nil
        return true
    }
}

enum DebugOverlayPositionPolicy {
    static func boundedOffset(
        proposed: CGSize,
        containerSize: CGSize,
        panelSize: CGSize,
        margin: CGFloat
    ) -> CGSize {
        let maxX = max(margin, containerSize.width - panelSize.width - margin)
        let maxY = max(margin, containerSize.height - panelSize.height - margin)
        return CGSize(
            width: min(max(proposed.width, margin), maxX),
            height: min(max(proposed.height, margin), maxY)
        )
    }

    static func offsetAfterDrag(
        committed: CGSize,
        translation: CGSize,
        containerSize: CGSize,
        panelSize: CGSize,
        margin: CGFloat
    ) -> CGSize {
        boundedOffset(
            proposed: CGSize(
                width: committed.width + translation.width,
                height: committed.height + translation.height
            ),
            containerSize: containerSize,
            panelSize: panelSize,
            margin: margin
        )
    }

    static func nextCornerOffset(
        current: CGSize,
        containerSize: CGSize,
        panelSize: CGSize,
        margin: CGFloat
    ) -> CGSize {
        let topLeft = boundedOffset(
            proposed: CGSize(width: margin, height: margin),
            containerSize: containerSize,
            panelSize: panelSize,
            margin: margin
        )
        let topRight = boundedOffset(
            proposed: CGSize(width: CGFloat.greatestFiniteMagnitude, height: margin),
            containerSize: containerSize,
            panelSize: panelSize,
            margin: margin
        )
        let bottomRight = boundedOffset(
            proposed: CGSize(
                width: CGFloat.greatestFiniteMagnitude,
                height: CGFloat.greatestFiniteMagnitude
            ),
            containerSize: containerSize,
            panelSize: panelSize,
            margin: margin
        )
        let bottomLeft = boundedOffset(
            proposed: CGSize(width: margin, height: CGFloat.greatestFiniteMagnitude),
            containerSize: containerSize,
            panelSize: panelSize,
            margin: margin
        )
        let corners = [topLeft, topRight, bottomRight, bottomLeft]
        let currentIndex = corners.indices.min { lhs, rhs in
            distance(from: current, to: corners[lhs]) < distance(from: current, to: corners[rhs])
        } ?? 0
        return corners[(currentIndex + 1) % corners.count]
    }

    private static func distance(from lhs: CGSize, to rhs: CGSize) -> CGFloat {
        hypot(lhs.width - rhs.width, lhs.height - rhs.height)
    }
}

struct AppDebugSettingsTabLongPressBridge: UIViewRepresentable {
    let onLongPressSettingsTab: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onLongPressSettingsTab: onLongPressSettingsTab)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        DispatchQueue.main.async {
            context.coordinator.install(from: view)
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onLongPressSettingsTab = onLongPressSettingsTab
        DispatchQueue.main.async {
            context.coordinator.install(from: uiView)
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onLongPressSettingsTab: () -> Void
        private weak var installedTabBar: UITabBar?
        private var recognizer: UILongPressGestureRecognizer?

        init(onLongPressSettingsTab: @escaping () -> Void) {
            self.onLongPressSettingsTab = onLongPressSettingsTab
        }

        func install(from view: UIView) {
            guard let window = view.window ?? UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap(\.windows)
                .first(where: { $0.isKeyWindow }),
                  let tabBar = findTabBar(in: window) else {
                return
            }

            if installedTabBar === tabBar {
                return
            }

            if let recognizer, let installedTabBar {
                installedTabBar.removeGestureRecognizer(recognizer)
            }

            let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
            longPress.minimumPressDuration = 0.75
            longPress.cancelsTouchesInView = false
            longPress.delaysTouchesBegan = false
            longPress.delaysTouchesEnded = false
            longPress.delegate = self
            tabBar.addGestureRecognizer(longPress)
            installedTabBar = tabBar
            recognizer = longPress
        }

        @objc private func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began,
                  let tabBar = recognizer.view as? UITabBar,
                  let itemCount = tabBar.items?.count,
                  itemCount > 0 else {
                return
            }

            let location = recognizer.location(in: tabBar)
            let itemWidth = max(tabBar.bounds.width / CGFloat(itemCount), 1)
            let index = min(max(Int(location.x / itemWidth), 0), itemCount - 1)
            guard index == itemCount - 1 else {
                return
            }

            onLongPressSettingsTab()
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            true
        }

        private func findTabBar(in view: UIView) -> UITabBar? {
            if let tabBar = view as? UITabBar {
                return tabBar
            }

            for subview in view.subviews {
                if let tabBar = findTabBar(in: subview) {
                    return tabBar
                }
            }

            return nil
        }
    }
}
#endif
