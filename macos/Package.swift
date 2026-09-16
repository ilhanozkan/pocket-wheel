// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "PocketWheel",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "PocketWheelCore", targets: ["PocketWheelCore"]),
        .executable(name: "PocketWheel", targets: ["PocketWheelApp"])
    ],
    targets: [
        .target(name: "PocketWheelCore"),
        .executableTarget(name: "PocketWheelApp", dependencies: ["PocketWheelCore"]),
        .testTarget(name: "PocketWheelCoreTests", dependencies: ["PocketWheelCore"])
    ]
)
