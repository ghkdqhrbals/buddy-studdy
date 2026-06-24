import SwiftUI

extension View {
    func searchSafeRefreshControlOffset(
        offset: CGFloat = 46
    ) -> some View {
        #if os(iOS)
        background(
            RefreshControlOffsetProbe(offset: offset)
                .frame(width: 0, height: 0)
                .allowsHitTesting(false)
        )
        #else
        self
        #endif
    }
}

#if os(iOS)
import UIKit

private struct RefreshControlOffsetProbe: UIViewRepresentable {
    var offset: CGFloat

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        context.coordinator.scheduleApply(
            from: view,
            offset: offset
        )
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.scheduleApply(
            from: view,
            offset: offset
        )
    }

    @MainActor
    final class Coordinator {
        private weak var scrollView: UIScrollView?
        private weak var refreshControl: UIRefreshControl?
        private var retryCount = 0
        private var lastOffset: CGFloat = 0

        func scheduleApply(
            from view: UIView,
            offset: CGFloat
        ) {
            lastOffset = offset
            Task { @MainActor [weak self, weak view] in
                guard let self, let view else {
                    return
                }
                self.apply(
                    from: view,
                    offset: offset
                )
            }
        }

        private func apply(
            from view: UIView,
            offset: CGFloat
        ) {
            let scrollView = self.scrollView ?? view.enclosingScrollView()
            guard let scrollView else {
                retry(
                    from: view,
                    offset: offset
                )
                return
            }

            self.scrollView = scrollView

            guard let refreshControl = scrollView.refreshControl else {
                retry(
                    from: view,
                    offset: offset
                )
                return
            }

            self.refreshControl = refreshControl
            retryCount = 0

            if refreshControl.transform.ty != offset {
                refreshControl.transform = CGAffineTransform(translationX: 0, y: offset)
            }
            refreshControl.layer.zPosition = 1
        }

        private func retry(
            from view: UIView,
            offset: CGFloat
        ) {
            guard retryCount < 12 else {
                return
            }

            retryCount += 1
            Task { @MainActor [weak self, weak view] in
                try? await Task.sleep(for: .milliseconds(50))
                guard let self, let view else {
                    return
                }
                self.apply(
                    from: view,
                    offset: self.lastOffset == 0 ? offset : self.lastOffset
                )
            }
        }

    }
}

private extension UIView {
    func enclosingScrollView() -> UIScrollView? {
        var current = superview
        while let view = current {
            if let scrollView = view as? UIScrollView {
                return scrollView
            }
            current = view.superview
        }
        return nil
    }
}
#endif
