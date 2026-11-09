// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Core",
    platforms: [.iOS(.v17)],
    products: [.library(name: "Core", targets: ["Core"])],
    dependencies: [
        .package(path: "../Contract"),
        .package(path: "../Shared"),
        .package(path: "../UI"),
        .package(url: "https://github.com/supabase/supabase-swift", from: "2.0.0"),
    ],
    targets: [
        .target(
            name: "Core",
            dependencies: [
                "Contract", "Shared", "UI",
                .product(name: "Auth", package: "supabase-swift"),
            ]
        ),
        .testTarget(name: "CoreTests", dependencies: ["Core", "Contract"]),
    ]
)
