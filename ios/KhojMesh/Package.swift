// swift-tools-version:5.9
// UNCOMPILED: written without a Swift compiler. Run `swift test` on a Mac before trusting it.
import PackageDescription

let package = Package(
    name: "KhojMesh",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "KhojMesh", targets: ["KhojMesh"])],
    targets: [
        .target(name: "KhojMesh"),
        .testTarget(
            name: "KhojMeshTests",
            dependencies: ["KhojMesh"],
            resources: [.process("Resources")]
        ),
    ]
)
