import Foundation

struct CommunitySessionEpoch {
    private(set) var value: UInt64 = 0

    mutating func invalidate() {
        value &+= 1
    }

    func isCurrent(_ snapshot: UInt64, sessionIsActive: Bool) -> Bool {
        sessionIsActive && snapshot == value
    }
}

@MainActor
struct BackendRuntimeStateStore {
    var accessState: BackendAccessState = .signedOut
    var isLoadingSettingsForEditing = false
    var isOpenAIKeyConfigured = false

    mutating func signOut() {
        accessState = .signedOut
    }
}
