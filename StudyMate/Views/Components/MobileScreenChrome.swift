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

private struct MobileSearchCapsuleBackground: View {
    var fill: Color
    var stroke: Color

    var body: some View {
        Capsule()
            .fill(fill)
            .overlay {
                Capsule()
                    .stroke(stroke, lineWidth: 0.2)
            }
    }
}

struct MobileToolbarSearchField: View {
    @Binding var text: String
    var prompt: String
    var focus: FocusState<Bool>.Binding
    var closeAccessibilityLabel: String
    var width: CGFloat = 284
    var height: CGFloat = 50
    var showsBackground: Bool = true
    var onSubmit: () -> Void = {}
    var onClose: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.secondary)

            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text(prompt)
                        .font(.system(size: 18, weight: .regular))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                TextField("", text: $text)
                    .textFieldStyle(.plain)
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(.primary)
                    .tint(.accentColor)
                    .lineLimit(1)
                    .submitLabel(.search)
                    .focused(focus)
                    .onSubmit(onSubmit)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "xmark")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(.secondary)
                .frame(width: 32, height: 32)
                .contentShape(Rectangle())
                .onTapGesture(perform: onClose)
                .accessibilityLabel(closeAccessibilityLabel)
                .accessibilityAddTraits(.isButton)
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .frame(width: resolvedWidth, height: height)
        .mobileToolbarSearchBackground(showsBackground ? searchBackground : nil)
        .contentShape(Capsule())
    }

    private var resolvedWidth: CGFloat {
        let safeToolbarWidth = max(UIScreen.main.bounds.width - 48, 292)
        return min(width, safeToolbarWidth, 430)
    }

    private var searchBackground: Color {
        MobileSearchColors.toolbarSearchFill
    }
}

struct MobileExpandingToolbarSearch<CollapsedContent: View>: View {
    @State private var keepsSearchFieldMounted = false
    @State private var searchFieldUnmountTask: Task<Void, Never>?

    var isExpanded: Bool
    @Binding var text: String
    var prompt: String
    var focus: FocusState<Bool>.Binding
    var closeAccessibilityLabel: String
    var width: CGFloat = 430
    var collapsedWidth: CGFloat = 34
    var height: CGFloat = 50
    var onSubmit: () -> Void = {}
    var onClose: () -> Void
    @ViewBuilder var collapsedContent: () -> CollapsedContent

    var body: some View {
        let fullWidth = resolvedWidth
        let containerWidth = isExpanded ? fullWidth : collapsedWidth

        ZStack(alignment: .trailing) {
            if !isExpanded {
                collapsedContent()
                    .frame(width: collapsedWidth, height: height, alignment: .trailing)
                    .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .trailing)))
            }

            ZStack(alignment: .trailing) {
                MobileSearchCapsuleBackground(
                    fill: searchBackground,
                    stroke: MobileSearchColors.toolbarSearchStroke
                )
                    .frame(width: containerWidth, height: height)

                if keepsSearchFieldMounted {
                    MobileToolbarSearchField(
                        text: $text,
                        prompt: prompt,
                        focus: focus,
                        closeAccessibilityLabel: closeAccessibilityLabel,
                        width: fullWidth,
                        height: height,
                        showsBackground: false,
                        onSubmit: onSubmit,
                        onClose: onClose
                    )
                    .frame(width: fullWidth, height: height, alignment: .trailing)
                    .disabled(!isExpanded)
                }
            }
            .frame(width: containerWidth, height: height, alignment: .trailing)
            .opacity(isExpanded ? 1 : 0)
            .allowsHitTesting(isExpanded)
            .clipped()
        }
        .frame(width: containerWidth, height: height, alignment: .trailing)
        .animation(.smooth(duration: isExpanded ? 0.34 : 0.22), value: isExpanded)
        .clipped()
        .onAppear {
            keepsSearchFieldMounted = isExpanded
        }
        .onChange(of: isExpanded) { _, expanded in
            updateSearchFieldMountState(isExpanded: expanded)
        }
        .onDisappear {
            searchFieldUnmountTask?.cancel()
            searchFieldUnmountTask = nil
        }
    }

    private var resolvedWidth: CGFloat {
        let safeToolbarWidth = max(UIScreen.main.bounds.width - 48, 292)
        return min(width, safeToolbarWidth, 430)
    }

    private var searchBackground: Color {
        MobileSearchColors.toolbarSearchFill
    }

    private func updateSearchFieldMountState(isExpanded expanded: Bool) {
        searchFieldUnmountTask?.cancel()

        if expanded {
            keepsSearchFieldMounted = true
            return
        }

        focus.wrappedValue = false
        searchFieldUnmountTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 260_000_000)
            guard !Task.isCancelled else {
                return
            }
            keepsSearchFieldMounted = false
        }
    }
}

private enum MobileSearchColors {
    static var toolbarSearchFill: Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? .secondarySystemBackground : .secondarySystemFill
        })
    }

    static var toolbarSearchStroke: Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? .separator : .separator
        })
    }
}

private extension View {
    @ViewBuilder
    func mobileToolbarSearchBackground(_ color: Color?) -> some View {
        if let color {
            background {
                MobileSearchCapsuleBackground(
                    fill: color,
                    stroke: MobileSearchColors.toolbarSearchStroke
                )
            }
        } else {
            self
        }
    }
}
#endif
