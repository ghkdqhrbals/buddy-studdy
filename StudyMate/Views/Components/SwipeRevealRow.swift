import SwiftUI
#if os(iOS)
import UIKit
#endif

struct SwipeRevealRow<Content: View, Action: View>: View {
    @Binding var isOpen: Bool
    var actionWidth: CGFloat = 88
    var cornerRadius: CGFloat = 8
    var onTap: (() -> Void)?
    var onFullSwipe: (() -> Void)?
    @ViewBuilder var content: () -> Content
    @ViewBuilder var action: () -> Action

    @State private var dragOffset: CGFloat = 0
    @State private var isTrackingHorizontalDrag = false
    @State private var suppressTapAfterDrag = false
    @State private var isCommittingFullSwipe = false
    @State private var didTriggerCommitFeedback = false
    @State private var isDismissingCommittedAction = false
    @State private var commitSequence = 0
    @State private var rowWidth: CGFloat = 0

    private let commitAnimationDuration: TimeInterval = 0.24
    private let committedActionDismissalDuration: TimeInterval = 0.12
    private let postCommitRemovalSettleDuration: TimeInterval = 0.28
    private let horizontalDragActivationDistance: CGFloat = 14

    private var baseOffset: CGFloat {
        isOpen ? -actionWidth : 0
    }

    private var swipeLimit: CGFloat {
        onFullSwipe == nil ? actionWidth : max(actionWidth * 2.35, rowWidth)
    }

    private var currentOffset: CGFloat {
        if isCommittingFullSwipe {
            return -committedSwipeDistance
        }

        return min(0, max(-swipeLimit, baseOffset + dragOffset))
    }

    private var actionVisibleWidth: CGFloat {
        if isCommittingFullSwipe {
            return committedSwipeDistance
        }

        return min(actionWidth, max(0, -currentOffset))
    }

    private var actionOffset: CGFloat {
        if isCommittingFullSwipe {
            return 0
        }

        return actionWidth - actionVisibleWidth
    }

    private var fullSwipeCommitOffset: CGFloat {
        min(max(actionWidth * 1.65, rowWidth * 0.55), max(actionWidth * 2, rowWidth * 0.82))
    }

    private var committedSwipeDistance: CGFloat {
        max(rowWidth, actionWidth)
    }

    private var fullSwipePreviewProgress: CGFloat {
        let revealDistance = max(0, -currentOffset - actionWidth)
        let previewDistance = max(1, swipeLimit - actionWidth)
        return min(1, revealDistance / previewDistance)
    }

    private var fullSwipeCommitProgress: CGFloat {
        guard onFullSwipe != nil else {
            return 0
        }

        let commitDistance = max(0, -currentOffset - fullSwipeCommitOffset)
        let commitRange = max(1, swipeLimit - fullSwipeCommitOffset)
        return min(1, commitDistance / commitRange)
    }

    private var commitIndicatorProgress: CGFloat {
        isCommittingFullSwipe ? 1 : fullSwipeCommitProgress
    }

    private var contentOpacity: CGFloat {
        if isCommittingFullSwipe {
            return isDismissingCommittedAction ? 0 : 0.18
        }

        return 1 - fullSwipePreviewProgress * 0.08 - fullSwipeCommitProgress * 0.12
    }

    private var actionOpacity: CGFloat {
        isDismissingCommittedAction ? 0 : 1
    }

    private var shouldAllowActionTap: Bool {
        isOpen && !isCommittingFullSwipe
    }

    var body: some View {
        ZStack(alignment: .trailing) {
            content()
                .frame(maxWidth: .infinity)
                .offset(x: currentOffset)
                .opacity(contentOpacity)
                .contentShape(Rectangle())
                #if os(iOS)
                .overlay {
                    HorizontalSwipeGestureBridge(
                        onTap: handleTap,
                        onChanged: handleHorizontalDragChanged,
                        onEnded: handleHorizontalDragEnded,
                        isOpen: isOpen
                    )
                }
                #else
                .onTapGesture(perform: handleTap)
                .simultaneousGesture(dragGesture)
                #endif

            action()
                .frame(width: actionVisibleWidth)
                .frame(maxHeight: .infinity)
                .offset(x: actionOffset)
                .opacity(actionOpacity)
                .brightness(commitIndicatorProgress * 0.05)
                .contentShape(Rectangle())
                .allowsHitTesting(shouldAllowActionTap)
                .accessibilityHidden(!shouldAllowActionTap)
                .zIndex(1)
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .background {
            GeometryReader { proxy in
                Color.clear
                    .onAppear {
                        rowWidth = proxy.size.width
                    }
                    .onChange(of: proxy.size.width) { _, width in
                        rowWidth = width
                    }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .animation(.easeOut(duration: commitAnimationDuration), value: isCommittingFullSwipe)
        .animation(.easeOut(duration: committedActionDismissalDuration), value: isDismissingCommittedAction)
        .animation(.interactiveSpring(response: 0.24, dampingFraction: 0.9), value: isOpen)
    }

    private func handleTap() {
        guard !suppressTapAfterDrag else {
            suppressTapAfterDrag = false
            return
        }

        if isOpen {
            withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.9)) {
                isOpen = false
            }
        } else {
            onTap?()
        }
    }

    private func handleHorizontalDragChanged(translation: CGFloat) {
        if !isTrackingHorizontalDrag {
            guard shouldStartHorizontalDrag(translation: translation) else {
                return
            }

            isTrackingHorizontalDrag = true
            suppressTapAfterDrag = true
        }

        var transaction = Transaction()
        transaction.disablesAnimations = true
        let newOffset = min(0, max(-swipeLimit, baseOffset + translation))
        withTransaction(transaction) {
            dragOffset = newOffset - baseOffset
        }
        updateCommitFeedback(for: -newOffset)
    }

    private func handleHorizontalDragEnded(translation: CGFloat, predictedTranslation: CGFloat) {
        guard isTrackingHorizontalDrag else {
            dragOffset = 0
            suppressTapAfterDrag = false
            return
        }

        let projectedOffset = baseOffset + predictedTranslation
        let finalOffset = min(0, max(-swipeLimit, baseOffset + translation))
        let projectedDistance = -min(0, max(-swipeLimit, projectedOffset))
        let finalDistance = -finalOffset
        let hasMeaningfulFullSwipeDistance = finalDistance >= max(actionWidth * 0.75, horizontalDragActivationDistance)
        let shouldCommitFullSwipe = onFullSwipe != nil &&
            (
                finalDistance >= fullSwipeCommitOffset ||
                    (hasMeaningfulFullSwipeDistance && projectedDistance >= actionWidth * 2.0)
            )
        let shouldOpen = projectedOffset < -actionWidth * 0.35 || finalOffset < -actionWidth * 0.42

        if shouldCommitFullSwipe {
            commitFullSwipe()
        } else {
            withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.9)) {
                isOpen = shouldOpen
                dragOffset = 0
                isTrackingHorizontalDrag = false
            }
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            suppressTapAfterDrag = false
        }
    }

    private func commitFullSwipe() {
        guard onFullSwipe != nil, !isCommittingFullSwipe else {
            return
        }

        commitSequence += 1
        let currentCommitSequence = commitSequence
        isDismissingCommittedAction = false
        withAnimation(.easeOut(duration: commitAnimationDuration)) {
            isOpen = true
            dragOffset = 0
            isCommittingFullSwipe = true
            isTrackingHorizontalDrag = false
        }
        playCommitFeedbackIfNeeded()

        DispatchQueue.main.asyncAfter(deadline: .now() + commitAnimationDuration) {
            guard commitSequence == currentCommitSequence else {
                return
            }

            withAnimation(.easeOut(duration: committedActionDismissalDuration)) {
                isDismissingCommittedAction = true
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + committedActionDismissalDuration) {
                guard commitSequence == currentCommitSequence else {
                    return
                }

                onFullSwipe?()

                DispatchQueue.main.asyncAfter(deadline: .now() + postCommitRemovalSettleDuration) {
                    guard commitSequence == currentCommitSequence else {
                        return
                    }

                    withAnimation(.easeOut(duration: 0.18)) {
                        isOpen = false
                        isCommittingFullSwipe = false
                        isDismissingCommittedAction = false
                        dragOffset = 0
                        isTrackingHorizontalDrag = false
                    }
                    didTriggerCommitFeedback = false
                    suppressTapAfterDrag = false
                }
            }
        }
    }

    private func shouldStartHorizontalDrag(translation: CGFloat) -> Bool {
        if isOpen {
            return abs(translation) >= horizontalDragActivationDistance
        }

        return translation <= -horizontalDragActivationDistance
    }

    private func updateCommitFeedback(for distance: CGFloat) {
        guard onFullSwipe != nil else {
            return
        }

        if distance >= fullSwipeCommitOffset {
            playCommitFeedbackIfNeeded()
        } else if distance < fullSwipeCommitOffset * 0.72 {
            didTriggerCommitFeedback = false
        }
    }

    private func playCommitFeedbackIfNeeded() {
        guard !didTriggerCommitFeedback else {
            return
        }

        didTriggerCommitFeedback = true
        #if os(iOS)
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        #endif
    }

    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 8, coordinateSpace: .local)
            .onChanged { value in
                let horizontal = value.translation.width
                let vertical = value.translation.height

                if !isTrackingHorizontalDrag {
                    guard abs(horizontal) > 6,
                          abs(horizontal) > abs(vertical) * 1.2 else {
                        return
                    }
                    isTrackingHorizontalDrag = true
                    suppressTapAfterDrag = true
                }

                var transaction = Transaction()
                transaction.disablesAnimations = true
                let newOffset = min(0, max(-swipeLimit, baseOffset + horizontal))
                withTransaction(transaction) {
                    dragOffset = newOffset - baseOffset
                }
                updateCommitFeedback(for: -newOffset)
            }
            .onEnded { value in
                guard isTrackingHorizontalDrag else {
                    dragOffset = 0
                    return
                }

                let projectedOffset = baseOffset + value.predictedEndTranslation.width
                let finalOffset = currentOffset
                let projectedDistance = -min(0, max(-swipeLimit, projectedOffset))
                let finalDistance = -finalOffset
                let hasMeaningfulFullSwipeDistance = finalDistance >= max(actionWidth * 0.75, horizontalDragActivationDistance)
                let shouldCommitFullSwipe = onFullSwipe != nil &&
                    (
                        finalDistance >= fullSwipeCommitOffset ||
                            (hasMeaningfulFullSwipeDistance && projectedDistance >= actionWidth * 2.0)
                    )
                let shouldOpen = projectedOffset < -actionWidth * 0.45 || finalOffset < -actionWidth * 0.5

                if shouldCommitFullSwipe {
                    commitFullSwipe()
                } else {
                    withAnimation(.interactiveSpring(response: 0.24, dampingFraction: 0.9)) {
                        isOpen = shouldOpen
                        dragOffset = 0
                        isTrackingHorizontalDrag = false
                    }
                }

                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                    suppressTapAfterDrag = false
                }
            }
    }
}

#if os(iOS)
private struct HorizontalSwipeGestureBridge: UIViewRepresentable {
    var onTap: () -> Void
    var onChanged: (CGFloat) -> Void
    var onEnded: (CGFloat, CGFloat) -> Void
    var isOpen: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap, onChanged: onChanged, onEnded: onEnded, isOpen: isOpen)
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear

        let panGesture = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handlePan(_:)))
        panGesture.delegate = context.coordinator
        panGesture.cancelsTouchesInView = false
        view.addGestureRecognizer(panGesture)

        let tapGesture = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        tapGesture.delegate = context.coordinator
        tapGesture.cancelsTouchesInView = false
        tapGesture.require(toFail: panGesture)
        view.addGestureRecognizer(tapGesture)

        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onTap = onTap
        context.coordinator.onChanged = onChanged
        context.coordinator.onEnded = onEnded
        context.coordinator.isOpen = isOpen
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var onTap: () -> Void
        var onChanged: (CGFloat) -> Void
        var onEnded: (CGFloat, CGFloat) -> Void
        var isOpen: Bool

        init(
            onTap: @escaping () -> Void,
            onChanged: @escaping (CGFloat) -> Void,
            onEnded: @escaping (CGFloat, CGFloat) -> Void,
            isOpen: Bool
        ) {
            self.onTap = onTap
            self.onChanged = onChanged
            self.onEnded = onEnded
            self.isOpen = isOpen
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard let panGesture = gestureRecognizer as? UIPanGestureRecognizer else {
                return true
            }

            let velocity = panGesture.velocity(in: panGesture.view)
            let isClearlyHorizontal = abs(velocity.x) > 32 && abs(velocity.x) > abs(velocity.y) * 1.25
            guard isClearlyHorizontal else {
                return false
            }

            if isOpen {
                return true
            }

            return velocity.x < 0
        }

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended else {
                return
            }

            onTap()
        }

        @objc func handlePan(_ gesture: UIPanGestureRecognizer) {
            let translation = gesture.translation(in: gesture.view).x
            let velocity = gesture.velocity(in: gesture.view).x
            let predictedTranslation = translation + velocity * 0.18

            switch gesture.state {
            case .began, .changed:
                onChanged(translation)
            case .ended:
                onEnded(translation, predictedTranslation)
            case .cancelled, .failed:
                onEnded(translation, translation)
            default:
                break
            }
        }
    }
}
#endif

struct SwipeActionButton: View {
    var title: String
    var systemImage: String
    var tint: Color
    var width: CGFloat = 88
    var iconSize: CGFloat = 14
    var contentHorizontalPadding: CGFloat = 12

    var body: some View {
        ZStack {
            tint

            VStack(spacing: 3) {
                Image(systemName: systemImage)
                    .font(.system(size: iconSize, weight: .semibold))
                    .frame(height: iconSize + 2)
                Text(title)
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .frame(maxWidth: .infinity)
            .frame(maxHeight: .infinity)
            .foregroundStyle(.white)
            .padding(.horizontal, contentHorizontalPadding)
        }
        .frame(width: width)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
