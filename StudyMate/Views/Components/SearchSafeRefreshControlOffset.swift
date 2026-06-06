import SwiftUI

extension View {
    func searchSafeRefreshControlOffset(
        offset: CGFloat = 46,
        isRefreshing: Bool = false,
        hidesSystemIndicator: Bool = false
    ) -> some View {
        #if os(iOS)
        background(
            RefreshControlOffsetProbe(
                offset: offset,
                isRefreshing: isRefreshing,
                hidesSystemIndicator: hidesSystemIndicator
            )
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
    var isRefreshing: Bool
    var hidesSystemIndicator: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.backgroundColor = .clear
        view.isUserInteractionEnabled = false
        context.coordinator.scheduleApply(
            from: view,
            offset: offset,
            isRefreshing: isRefreshing,
            hidesSystemIndicator: hidesSystemIndicator
        )
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.scheduleApply(
            from: view,
            offset: offset,
            isRefreshing: isRefreshing,
            hidesSystemIndicator: hidesSystemIndicator
        )
    }

    @MainActor
    final class Coordinator {
        private weak var scrollView: UIScrollView?
        private weak var refreshControl: UIRefreshControl?
        private var retryCount = 0
        private var lastOffset: CGFloat = 0
        private var lastIsRefreshing = false
        private var lastHidesSystemIndicator = false
        private var originalTintColor: UIColor?

        func scheduleApply(
            from view: UIView,
            offset: CGFloat,
            isRefreshing: Bool,
            hidesSystemIndicator: Bool
        ) {
            lastOffset = offset
            lastIsRefreshing = isRefreshing
            lastHidesSystemIndicator = hidesSystemIndicator
            Task { @MainActor [weak self, weak view] in
                guard let self, let view else {
                    return
                }
                self.apply(
                    from: view,
                    offset: offset,
                    isRefreshing: isRefreshing,
                    hidesSystemIndicator: hidesSystemIndicator
                )
            }
        }

        private func apply(
            from view: UIView,
            offset: CGFloat,
            isRefreshing: Bool,
            hidesSystemIndicator: Bool
        ) {
            let scrollView = self.scrollView ?? view.enclosingScrollView()
            guard let scrollView else {
                retry(
                    from: view,
                    offset: offset,
                    isRefreshing: isRefreshing,
                    hidesSystemIndicator: hidesSystemIndicator
                )
                return
            }

            self.scrollView = scrollView

            guard let refreshControl = scrollView.refreshControl else {
                retry(
                    from: view,
                    offset: offset,
                    isRefreshing: isRefreshing,
                    hidesSystemIndicator: hidesSystemIndicator
                )
                return
            }

            self.refreshControl = refreshControl
            retryCount = 0
            if originalTintColor == nil {
                originalTintColor = refreshControl.tintColor
            }

            if refreshControl.transform.ty != offset {
                refreshControl.transform = CGAffineTransform(translationX: 0, y: offset)
            }
            refreshControl.tintColor = hidesSystemIndicator ? .clear : originalTintColor
            refreshControl.subviews.forEach { subview in
                subview.alpha = hidesSystemIndicator ? 0 : 1
            }
            refreshControl.layer.zPosition = 1
            synchronize(refreshControl, in: scrollView, isRefreshing: isRefreshing)
        }

        private func retry(
            from view: UIView,
            offset: CGFloat,
            isRefreshing: Bool,
            hidesSystemIndicator: Bool
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
                    offset: self.lastOffset == 0 ? offset : self.lastOffset,
                    isRefreshing: self.lastIsRefreshing,
                    hidesSystemIndicator: self.lastHidesSystemIndicator || hidesSystemIndicator
                )
            }
        }

        private func synchronize(_ refreshControl: UIRefreshControl, in scrollView: UIScrollView, isRefreshing: Bool) {
            if isRefreshing {
                if !refreshControl.isRefreshing {
                    refreshControl.beginRefreshing()
                }

                guard !scrollView.isDragging && !scrollView.isDecelerating else {
                    return
                }

                let targetY = -scrollView.adjustedContentInset.top - max(refreshControl.bounds.height, 48)
                if scrollView.contentOffset.y > targetY {
                    scrollView.setContentOffset(CGPoint(x: scrollView.contentOffset.x, y: targetY), animated: true)
                }
            } else if refreshControl.isRefreshing {
                refreshControl.endRefreshing()
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
