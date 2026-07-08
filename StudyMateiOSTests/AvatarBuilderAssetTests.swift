import XCTest
@testable import StudyMate

final class AvatarBuilderAssetTests: XCTestCase {
    func testSeededPartAssetsExistInAssetCatalog() throws {
        let assetCatalogURL = Self.repositoryRoot()
            .appendingPathComponent("StudyMate/Resources/Assets.xcassets", isDirectory: true)

        for assetName in Self.seededPartAssetNames {
            let imageSetURL = assetCatalogURL.appendingPathComponent("\(assetName).imageset", isDirectory: true)
            let contentsURL = imageSetURL.appendingPathComponent("Contents.json")
            let imageURL = imageSetURL.appendingPathComponent("\(assetName).png")

            XCTAssertTrue(
                FileManager.default.fileExists(atPath: imageSetURL.path),
                "Missing avatar builder imageset: \(imageSetURL.path)"
            )
            XCTAssertTrue(
                FileManager.default.fileExists(atPath: contentsURL.path),
                "Missing Contents.json for \(assetName)"
            )
            XCTAssertTrue(
                FileManager.default.fileExists(atPath: imageURL.path),
                "Missing PNG for \(assetName)"
            )

            let contents = try Data(contentsOf: contentsURL)
            let decoded = try JSONDecoder().decode(AssetCatalogContents.self, from: contents)
            XCTAssertTrue(
                decoded.images.contains { $0.filename == "\(assetName).png" },
                "Contents.json for \(assetName) must reference \(assetName).png"
            )

            let imageData = try Data(contentsOf: imageURL)
            XCTAssertEqual(Array(imageData.prefix(8)), [137, 80, 78, 71, 13, 10, 26, 10])
            XCTAssertGreaterThan(imageData.count, 1024, "Avatar builder image asset is unexpectedly small: \(assetName)")
        }
    }

    func testLocalAssetRegistryCoversSeededPartAssets() {
        for assetName in Self.seededPartAssetNames {
            XCTAssertTrue(
                AvatarBuilderAssetRegistry.hasLocalImageAsset(assetName),
                "Missing renderer registry entry for \(assetName)"
            )
        }
    }
}

private extension AvatarBuilderAssetTests {
    static var seededPartAssetNames: [String] {
        [
            "avatar-top-hoodie-blue",
            "avatar-top-varsity-green",
            "avatar-top-sweater-rose",
            "avatar-bottom-denim-pants",
            "avatar-bottom-jogger-black",
            "avatar-bottom-shorts-tan",
            "avatar-shoes-white-sneakers",
            "avatar-shoes-brown-loafers",
            "avatar-shoes-blue-boots",
            "avatar-hat-beanie-navy",
            "avatar-hat-cap-orange",
            "avatar-hat-grad-black",
            "avatar-item-laptop",
            "avatar-item-book",
            "avatar-item-pencil"
        ]
    }

    static func repositoryRoot(filePath: StaticString = #filePath) -> URL {
        URL(fileURLWithPath: "\(filePath)")
            .deletingLastPathComponent()
            .deletingLastPathComponent()
    }
}

private struct AssetCatalogContents: Decodable {
    let images: [AssetCatalogImage]
}

private struct AssetCatalogImage: Decodable {
    let filename: String?
}
