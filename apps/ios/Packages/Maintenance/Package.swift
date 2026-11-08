// swift-tools-version: 6.0
// Module package. It must not depend on another module package (SPEC §2.2);
// allowed dependencies: Core, Sync, UI, Shared, Contract.
import PackageDescription

let package = Package(
    name: "Maintenance",
    platforms: [.iOS(.v17)],
    products: [.library(name: "Maintenance", targets: ["Maintenance"])],
    targets: [.target(name: "Maintenance")]
)
