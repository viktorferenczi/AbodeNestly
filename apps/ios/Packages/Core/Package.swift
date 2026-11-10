// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Core",
    platforms: [.iOS(.v17)],
    products: [.library(name: "Core", targets: ["Core"])],
    dependencies: [
        .package(path: "../Contract"),
        .package(path: "../Shared"),
        .package(path: "../NestlyUI"),
        .package(url: "https://github.com/supabase/supabase-swift", from: "2.0.0"),
    ],
    targets: [
        .target(
            name: "Core",
            dependencies: [
                "Contract", "Shared",
                .product(name: "NestlyUI", package: "NestlyUI"),
                .product(name: "Auth", package: "supabase-swift"),
            ],
            resources: [.process("Resources")]
        ),
    ]
)
