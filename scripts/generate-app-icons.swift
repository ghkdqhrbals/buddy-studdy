import AppKit
import Foundation
import UniformTypeIdentifiers

let sourceIconURL = URL(
    fileURLWithPath: "design/app-icon-concepts/study-tree/app-icon-buddystudy-tree-main-1024.png"
)

struct IconOutput {
    let path: String
    let pixels: Int
}

let outputs = [
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_20x20.png", pixels: 20),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_20x20@2x.png", pixels: 40),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_20x20@3x.png", pixels: 60),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_29x29.png", pixels: 29),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_29x29@2x.png", pixels: 58),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_29x29@3x.png", pixels: 87),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_40x40.png", pixels: 40),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_40x40@2x.png", pixels: 80),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_40x40@3x.png", pixels: 120),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_60x60@2x.png", pixels: 120),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_60x60@3x.png", pixels: 180),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_76x76.png", pixels: 76),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_76x76@2x.png", pixels: 152),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_83_5x83_5@2x.png", pixels: 167),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/AppIcon.appiconset/icon_1024x1024.png", pixels: 1024),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/BuddyStudyBrandLogo.imageset/BuddyStudyBrandLogo.png", pixels: 256),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/BuddyStudyBrandLogo.imageset/BuddyStudyBrandLogo@2x.png", pixels: 512),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/BuddyStudyBrandLogo.imageset/BuddyStudyBrandLogo@3x.png", pixels: 1024),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/BuddyStudyLoginLogo.imageset/BuddyStudyLoginLogo.png", pixels: 256),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/BuddyStudyLoginLogo.imageset/BuddyStudyLoginLogo@2x.png", pixels: 512),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/BuddyStudyLoginLogo.imageset/BuddyStudyLoginLogo@3x.png", pixels: 1024),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/LaunchLogo.imageset/launch_logo.png", pixels: 144),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/LaunchLogo.imageset/launch_logo@2x.png", pixels: 288),
    IconOutput(path: "StudyMate/Resources/Assets.xcassets/LaunchLogo.imageset/launch_logo@3x.png", pixels: 432),
    IconOutput(path: "docs/assets/buddystudy-icon.png", pixels: 1024),
    IconOutput(path: "portfolio-site/public/media/buddystudy-icon.png", pixels: 1024),
]

guard
    let sourceImage = NSImage(contentsOf: sourceIconURL),
    let sourceCGImage = sourceImage.cgImage(forProposedRect: nil, context: nil, hints: nil)
else {
    fatalError("Missing or invalid source icon at \(sourceIconURL.path)")
}

func pngData(size: Int) -> Data? {
    guard let context = CGContext(
        data: nil,
        width: size,
        height: size,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
    ) else {
        return nil
    }

    context.interpolationQuality = .high
    context.draw(
        sourceCGImage,
        in: CGRect(x: 0, y: 0, width: size, height: size)
    )

    guard let renderedImage = context.makeImage() else {
        return nil
    }

    let data = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(
        data,
        UTType.png.identifier as CFString,
        1,
        nil
    ) else {
        return nil
    }

    CGImageDestinationAddImage(destination, renderedImage, nil)
    guard CGImageDestinationFinalize(destination) else {
        return nil
    }
    return data as Data
}

for output in outputs {
    let outputURL = URL(fileURLWithPath: output.path)
    try FileManager.default.createDirectory(
        at: outputURL.deletingLastPathComponent(),
        withIntermediateDirectories: true
    )

    guard let png = pngData(size: output.pixels) else {
        fatalError("Failed to render \(output.path)")
    }
    try png.write(to: outputURL, options: .atomic)
}

print("Generated \(outputs.count) BuddyStudy icon assets from \(sourceIconURL.path)")
