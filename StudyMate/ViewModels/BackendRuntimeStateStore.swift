import Foundation

struct CommunitySessionStateStore {
    private(set) var isSignedIn: Bool
    private(set) var generation: UInt64 = 0

    init(isSignedIn: Bool = false) {
        self.isSignedIn = isSignedIn
    }

    mutating func signIn() {
        isSignedIn = true
    }

    mutating func signOut() {
        isSignedIn = false
        generation &+= 1
    }

    func isCurrent(_ snapshot: UInt64) -> Bool {
        isSignedIn && snapshot == generation
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
