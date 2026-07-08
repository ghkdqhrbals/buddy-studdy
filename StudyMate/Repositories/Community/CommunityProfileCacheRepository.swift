import Foundation

protocol CommunityProfileCacheRepository {
    func loadProfileAvatarSymbolName() -> String
    func saveProfileAvatarSymbolName(_ symbolName: String)
    func loadProfileAvatarImageData() -> Data?
    func saveProfileAvatarImageData(_ data: Data?)
    func loadProfileAvatarColorSeed() -> String?
    func saveProfileAvatarColorSeed(_ seed: String)
    func loadProfileAvatarConfig() -> [String: String]?
    func saveProfileAvatarConfig(_ config: [String: String]?)
    func loadCommunityProfileDisplayName() -> String?
    func saveCommunityProfileDisplayName(_ displayName: String)
    func loadCommunityProfileID() -> Int?
    func saveCommunityProfileID(_ id: Int?)
}
