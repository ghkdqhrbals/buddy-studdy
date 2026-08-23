// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "SentrySDK",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(name: "Sentry", targets: ["Sentry"]),
    ],
    targets: [
        .binaryTarget(
            name: "Sentry",
            url: "https://github.com/getsentry/sentry-cocoa/releases/download/9.23.0/Sentry.xcframework.zip",
            checksum: "e16f1fb6333f572e980be28d2a9e1ea20a08c2c91b7901d612ff6cee2af697cf"
        ),
    ]
)
