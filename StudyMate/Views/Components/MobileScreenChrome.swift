import SwiftUI
#if os(iOS)
import UIKit
#endif

extension View {
    @ViewBuilder
    func mobileToolbarSearchable(
        isPresented: Bool,
        text: Binding<String>,
        prompt: String,
        focus: FocusState<Bool>.Binding
    ) -> some View {
        #if os(iOS)
        self
        #else
        if isPresented {
            searchable(text: text, prompt: prompt)
                .mobileSearchFocused(focus)
        } else {
            self
        }
        #endif
    }

    @ViewBuilder
    private func mobileSearchFocused(_ binding: FocusState<Bool>.Binding) -> some View {
        if #available(iOS 18.0, macOS 15.0, *) {
            searchFocused(binding)
        } else {
            self
        }
    }
}

struct MobileRootLargeTitle: View {
    var title: String

    init(_ title: String) {
        self.title = title
    }

    var body: some View {
        Text(title)
            .font(.system(size: 34, weight: .bold))
            .lineLimit(1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

#if os(iOS)
struct MobileToolbarIconButtonLabel: View {
    var systemName: String

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 21, weight: .semibold))
            .foregroundStyle(.primary)
            .frame(width: 34, height: 34)
            .contentShape(Rectangle())
    }
}

struct MobileToolbarSearchField: View {
    @Environment(\.colorScheme) private var colorScheme

    @Binding var text: String
    var prompt: String
    var focus: FocusState<Bool>.Binding
    var closeAccessibilityLabel: String
    var width: CGFloat = 284
    var showsBackground: Bool = true
    var onSubmit: () -> Void = {}
    var onClose: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.white)

            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text(prompt)
                        .font(.system(size: 18, weight: .regular))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                }

                TextField("", text: $text)
                    .textFieldStyle(.plain)
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(.white)
                    .tint(.white)
                    .lineLimit(1)
                    .submitLabel(.search)
                    .focused(focus)
                    .onSubmit(onSubmit)
            }

            Button {
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(closeAccessibilityLabel)
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(width: resolvedWidth, height: 46)
        .background(showsBackground ? searchBackground : Color.clear, in: Capsule())
        .contentShape(Capsule())
        .clipped()
    }

    private var resolvedWidth: CGFloat {
        let safeToolbarWidth = max(UIScreen.main.bounds.width - 48, 292)
        return min(width, safeToolbarWidth, 430)
    }

    private var searchBackground: Color {
        colorScheme == .dark ? Color.white.opacity(0.16) : Color.black.opacity(0.72)
    }
}

struct MobileExpandingToolbarSearch<CollapsedContent: View>: View {
    @Environment(\.colorScheme) private var colorScheme

    var isExpanded: Bool
    @Binding var text: String
    var prompt: String
    var focus: FocusState<Bool>.Binding
    var closeAccessibilityLabel: String
    var width: CGFloat = 430
    var collapsedWidth: CGFloat = 84
    var onSubmit: () -> Void = {}
    var onClose: () -> Void
    @ViewBuilder var collapsedContent: () -> CollapsedContent

    var body: some View {
        let fullWidth = resolvedWidth
        let searchWidth = isExpanded ? fullWidth : 44
        let containerWidth = isExpanded ? fullWidth : collapsedWidth

        ZStack(alignment: .trailing) {
            collapsedContent()
                .frame(width: collapsedWidth, height: 46, alignment: .trailing)
                .opacity(isExpanded ? 0 : 1)
                .scaleEffect(isExpanded ? 0.96 : 1, anchor: .trailing)
                .allowsHitTesting(!isExpanded)

            MobileToolbarSearchField(
                text: $text,
                prompt: prompt,
                focus: focus,
                closeAccessibilityLabel: closeAccessibilityLabel,
                width: fullWidth,
                showsBackground: false,
                onSubmit: onSubmit,
                onClose: onClose
            )
            .frame(width: fullWidth, height: 46, alignment: .trailing)
            .frame(width: searchWidth, height: 46, alignment: .trailing)
            .background(searchBackground, in: Capsule())
            .clipShape(Capsule())
            .opacity(isExpanded ? 1 : 0)
            .allowsHitTesting(isExpanded)
        }
        .frame(width: containerWidth, height: 46, alignment: .trailing)
        .animation(.smooth(duration: isExpanded ? 0.34 : 0.22), value: isExpanded)
        .clipped()
    }

    private var resolvedWidth: CGFloat {
        let safeToolbarWidth = max(UIScreen.main.bounds.width - 48, 292)
        return min(width, safeToolbarWidth, 430)
    }

    private var searchBackground: Color {
        colorScheme == .dark ? Color.white.opacity(0.16) : Color.black.opacity(0.72)
    }
}
#endif
