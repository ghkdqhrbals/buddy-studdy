import Foundation

@MainActor
struct BackendRuntimeStateStore {
    var accessState: BackendAccessState = .signedOut
    var isUnderMaintenance = false
    var isLoadingSettingsForEditing = false
    var isOpenAIKeyConfigured = false

    mutating func signOut() {
        accessState = .signedOut
    }
}
