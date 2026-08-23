import Foundation

struct SettingsStoreRemotePushRegistrationRepository: RemotePushRegistrationRepository {
    private let settingsStore: SettingsStore

    init(settingsStore: SettingsStore) {
        self.settingsStore = settingsStore
    }

    func loadRemotePushRegistration() -> RemotePushRegistration? {
        settingsStore.loadRemotePushRegistration()
    }

    func saveRemotePushRegistration(_ registration: RemotePushRegistration?) {
        settingsStore.saveRemotePushRegistration(registration)
    }

    func loadOrCreateBackendInstallationIdentifier() -> String {
        settingsStore.loadOrCreateBackendInstallationIdentifier()
    }
}
