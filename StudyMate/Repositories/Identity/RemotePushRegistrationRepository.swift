import Foundation

protocol RemotePushRegistrationRepository {
    func loadRemotePushRegistration() -> RemotePushRegistration?
    func saveRemotePushRegistration(_ registration: RemotePushRegistration?)
    func loadOrCreateBackendInstallationIdentifier() -> String
}
