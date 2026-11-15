// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Sync",
    platforms: [.iOS(.v17)],
    products: [.library(name: "Sync", targets: ["Sync"])],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift", from: "7.0.0"),
    ],
    targets: [
        .target(name: "Sync", dependencies: [.product(name: "GRDB", package: "GRDB.swift")]),
    ]
)
