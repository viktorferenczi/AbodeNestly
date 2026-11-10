// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "NestlyUI",
    platforms: [.iOS(.v17)],
    products: [.library(name: "NestlyUI", targets: ["UI"])],
    targets: [.target(name: "UI")]
)
