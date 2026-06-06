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

#if os(iOS)
struct MobileToolbarIconButtonLabel: View {
    var systemName: String

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 21, weight: .semibold))
            .frame(width: 34, height: 34)
            .contentShape(Rectangle())
    }
}

struct MobileToolbarSearchField: View {
    @Binding var text: String
    var prompt: String
    var focus: FocusState<Bool>.Binding
    var closeAccessibilityLabel: String
    var width: CGFloat = 284
    var onSubmit: () -> Void = {}
    var onClose: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.secondary)

            TextField(prompt, text: $text)
                .textFieldStyle(.plain)
                .font(.body)
                .lineLimit(1)
                .submitLabel(.search)
                .focused(focus)
                .onSubmit(onSubmit)

            Button {
                onClose()
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(closeAccessibilityLabel)
        }
        .padding(.leading, 14)
        .padding(.trailing, 8)
        .frame(width: width, height: 41)
        .background(Color(.secondarySystemBackground), in: Capsule())
        .contentShape(Capsule())
        .transition(.opacity.combined(with: .scale(scale: 0.985, anchor: .center)))
    }
}
#endif
