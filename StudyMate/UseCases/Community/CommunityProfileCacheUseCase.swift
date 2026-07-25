import Foundation

struct CommunityProfileAvatarCache {
    var symbolName: String
    var imageData: Data?
    var colorSeed: String
    var config: [String: String]?
}

struct CommunityProfileCacheUseCase {
    private let repository: CommunityProfileCacheRepository

    init(repository: CommunityProfileCacheRepository) {
        self.repository = repository
    }

    func loadAvatarCache(generateColorSeed: () -> String) -> CommunityProfileAvatarCache {
        if repository.loadProfileAvatarImageData() != nil {
            repository.saveProfileAvatarImageData(nil)
        }

        let colorSeed: String
        if let cachedColorSeed = repository.loadProfileAvatarColorSeed()?.trimmingCharacters(in: .whitespacesAndNewlines),
           !cachedColorSeed.isEmpty {
            colorSeed = cachedColorSeed
        } else {
            let generatedColorSeed = generateColorSeed()
            colorSeed = generatedColorSeed
            repository.saveProfileAvatarColorSeed(generatedColorSeed)
        }

        return CommunityProfileAvatarCache(
            symbolName: repository.loadProfileAvatarSymbolName(),
            imageData: nil,
            colorSeed: colorSeed,
            config: repository.loadProfileAvatarConfig()
        )
    }

    func saveSignedOutProfile(avatarSymbolName: String) {
        repository.saveProfileAvatarSymbolName(avatarSymbolName)
        repository.saveProfileAvatarImageData(nil)
        repository.saveProfileAvatarConfig(nil)
        repository.saveCommunityProfileID(nil)
        repository.saveCommunityProfileDisplayName("")
    }

    func saveAvatarSymbolName(_ symbolName: String) {
        repository.saveProfileAvatarSymbolName(symbolName)
    }

    func saveAvatarColorSeed(_ seed: String) {
        repository.saveProfileAvatarColorSeed(seed)
    }

    func saveAvatarConfig(_ config: [String: String]?) {
        repository.saveProfileAvatarConfig(config)
    }

    func saveAvatarImageData(_ data: Data?) {
        repository.saveProfileAvatarImageData(data)
    }

    func saveDisplayName(_ displayName: String) {
        repository.saveCommunityProfileDisplayName(displayName)
    }

    func applyProfile(_ profile: CommunityUserProfile) -> CommunityUserProfile {
        let cachedDisplayName = repository.loadCommunityProfileDisplayName()?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let incomingDisplayName = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let cachedProfileID = repository.loadCommunityProfileID()
        let shouldPreserveCachedName = cachedProfileID == profile.id
            && !cachedDisplayName.isEmpty
            && cachedDisplayName != incomingDisplayName

        let resolvedProfile = shouldPreserveCachedName
            ? CommunityUserProfile(
                id: profile.id,
                displayName: cachedDisplayName,
                provider: profile.provider,
                email: profile.email,
                bio: profile.bio,
                avatarURL: profile.avatarURL,
                avatarSymbolName: profile.avatarSymbolName,
                avatarColorSeed: profile.avatarColorSeed,
                avatarMode: profile.avatarMode,
                avatarConfig: profile.avatarConfig,
                pageAccess: profile.pageAccess
            )
            : profile

        repository.saveCommunityProfileID(resolvedProfile.id)
        repository.saveCommunityProfileDisplayName(resolvedProfile.displayName)
        repository.saveProfileAvatarSymbolName(resolvedProfile.avatarSymbolName)
        repository.saveProfileAvatarColorSeed(resolvedProfile.avatarColorSeed)
        repository.saveProfileAvatarConfig(resolvedProfile.avatarConfig)
        return resolvedProfile
    }

    func clearProfileIdentity() {
        repository.saveCommunityProfileID(nil)
        repository.saveCommunityProfileDisplayName("")
    }
}
