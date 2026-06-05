import SwiftUI

#if os(iOS)
import UIKit

private struct HasKeyboardDoneToolbarKey: EnvironmentKey {
    static let defaultValue = false
}

private extension EnvironmentValues {
    var hasKeyboardDoneToolbar: Bool {
        get { self[HasKeyboardDoneToolbarKey.self] }
        set { self[HasKeyboardDoneToolbarKey.self] = newValue }
    }
}

private struct KeyboardDoneToolbarModifier: ViewModifier {
    @Environment(\.hasKeyboardDoneToolbar) private var hasKeyboardDoneToolbar

    var title: String

    func body(content: Content) -> some View {
        if hasKeyboardDoneToolbar {
            content
                .scrollDismissesKeyboard(.interactively)
        } else {
            content
                .environment(\.hasKeyboardDoneToolbar, true)
                .scrollDismissesKeyboard(.interactively)
                .toolbar {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button(title) {
                            UIApplication.shared.sendAction(
                                #selector(UIResponder.resignFirstResponder),
                                to: nil,
                                from: nil,
                                for: nil
                            )
                        }
                    }
                }
        }
    }
}
#else
private struct KeyboardDoneToolbarModifier: ViewModifier {
    var title: String

    func body(content: Content) -> some View {
        content
    }
}
#endif

extension View {
    func keyboardDoneToolbar(_ title: String) -> some View {
        modifier(KeyboardDoneToolbarModifier(title: title))
    }
}
