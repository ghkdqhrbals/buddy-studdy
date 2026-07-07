import Foundation

protocol AppClockProviding {
    var now: Date { get }
}

struct SystemAppClockProvider: AppClockProviding {
    var now: Date {
        Date()
    }
}
