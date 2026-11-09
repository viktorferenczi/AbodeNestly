// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "UI",
    platforms: [.iOS(.v17)],
    products: [.library(name: "UI", targets: ["UI"])],
    targets: [.target(name: "UI")]
)
