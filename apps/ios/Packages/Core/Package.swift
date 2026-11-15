// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Core",
    platforms: [.iOS(.v17)],
    // Sync is a second library of this package (not a package of its own): an extra top-level local package made
    // Xcode 26.5 abort while registering dependency packages on CI.
    products: [
        .library(name: "Core", targets: ["Core"]),
        .library(name: "Sync", targets: ["Sync"]),
    ],
    dependencies: [
        .package(path: "../Contract"),
        .package(path: "../Shared"),
        .package(path: "../NestlyUI"),
        .package(url: "https://github.com/groue/GRDB.swift", from: "7.0.0"),
        .package(url: "https://github.com/supabase/supabase-swift", from: "2.0.0"),
    ],
    targets: [
        .target(name: "Sync", dependencies: [.product(name: "GRDB", package: "GRDB.swift")]),
        .target(
            name: "Core",
            dependencies: [
                "Contract", "Shared",
                .product(name: "NestlyUI", package: "NestlyUI"),
                "Sync",
                .product(name: "Auth", package: "supabase-swift"),
            ],
            resources: [.process("Resources")]
        ),
    ]
)
