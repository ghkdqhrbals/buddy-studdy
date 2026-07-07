import Foundation

protocol AppIdentifierProviding {
    func makeIdentifier() -> String
}

struct UUIDAppIdentifierProvider: AppIdentifierProviding {
    func makeIdentifier() -> String {
        UUID().uuidString
    }
}
