import Foundation

@MainActor
struct CommunityProfileStateStore {
    var profile: CommunityUserProfile?
    var isUpdating = false
    var isWithdrawing = false
    var avatarSymbolName: String
    var avatarImageData: Data?
    var avatarColorSeed: String
    var avatarConfig: [String: String]?

    init(
        profile: CommunityUserProfile? = nil,
        isUpdating: Bool = false,
        isWithdrawing: Bool = false,
        avatarSymbolName: String = "pixel-fox-scholar",
        avatarImageData: Data? = nil,
        avatarColorSeed: String = UUID().uuidString,
        avatarConfig: [String: String]? = nil
    ) {
        self.profile = profile
        self.isUpdating = isUpdating
        self.isWithdrawing = isWithdrawing
        self.avatarSymbolName = avatarSymbolName
        self.avatarImageData = avatarImageData
        self.avatarColorSeed = avatarColorSeed
        self.avatarConfig = avatarConfig
    }

    mutating func resetSignedOutProfile() {
        profile = nil
        avatarSymbolName = "pixel-fox-scholar"
        avatarImageData = nil
        avatarConfig = nil
    }

    mutating func applyProfile(_ nextProfile: CommunityUserProfile) {
        profile = nextProfile
        avatarSymbolName = nextProfile.avatarSymbolName
        avatarColorSeed = nextProfile.avatarColorSeed
        avatarConfig = nextProfile.avatarConfig
    }

    mutating func clearProfile() {
        profile = nil
    }

    mutating func updateAvatar(symbolName: String? = nil, colorSeed: String? = nil, imageData: Data? = nil, config: [String: String]? = nil) {
        if let symbolName {
            avatarSymbolName = symbolName
        }
        if let colorSeed {
            avatarColorSeed = colorSeed
        }
        if let config {
            avatarConfig = config
        }
        if let imageData {
            avatarImageData = imageData
        }
    }

    mutating func setAvatarConfig(_ config: [String: String]?) {
        avatarConfig = config
    }

    mutating func setAvatarImageData(_ data: Data?) {
        avatarImageData = data
    }
}
