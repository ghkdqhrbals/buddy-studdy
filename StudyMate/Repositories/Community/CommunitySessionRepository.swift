protocol CommunitySessionRepository {
    func loadIsCommunitySignedIn() -> Bool
    func saveIsCommunitySignedIn(_ isSignedIn: Bool)
}
