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
struct MobileToolbarSearchField: View {
    @Binding var text: String
    var prompt: String
    var focus: FocusState<Bool>.Binding
    var closeAccessibilityLabel: String
    var onSubmit: () -> Void = {}
    var onClose: () -> Void

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)

            TextField(prompt, text: $text)
                .textFieldStyle(.plain)
                .font(.subheadline)
                .lineLimit(1)
                .submitLabel(.search)
                .focused(focus)
                .onSubmit(onSubmit)

            Button {
                onClose()
            } label: {
                Image(systemName: text.isEmpty ? "xmark" : "xmark.circle.fill")
                    .font(.system(size: text.isEmpty ? 11 : 14, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 22, height: 22)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(closeAccessibilityLabel)
        }
        .padding(.leading, 11)
        .padding(.trailing, 6)
        .frame(width: 236, height: 34)
        .background(Color(.secondarySystemBackground), in: Capsule())
        .overlay {
            Capsule()
                .stroke(Color(.separator).opacity(0.34), lineWidth: 0.7)
        }
        .contentShape(Capsule())
        .transition(.opacity.combined(with: .scale(scale: 0.98, anchor: .center)))
    }
}
#endif
