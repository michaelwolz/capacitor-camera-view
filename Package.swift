// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorCameraView",
    platforms: [.iOS(.v14)],
    products: [
        .library(
            name: "CapacitorCameraView",
            targets: ["CameraViewPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.2.0")
    ],
    targets: [
        .target(
            name: "CameraViewPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CameraViewPlugin"),
        .testTarget(
            name: "CameraViewPluginTests",
            dependencies: ["CameraViewPlugin"],
            path: "ios/Tests/CameraViewPluginTests")
    ]
)