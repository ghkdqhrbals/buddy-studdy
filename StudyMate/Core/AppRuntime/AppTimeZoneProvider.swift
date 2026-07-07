import Foundation

protocol AppTimeZoneProviding {
    var currentIdentifier: String { get }
}

struct SystemAppTimeZoneProvider: AppTimeZoneProviding {
    var currentIdentifier: String {
        TimeZone.current.identifier
    }
}
