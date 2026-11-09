import Foundation

/// The household the person last used. CORE-07 (household switcher) will write it too.
public struct LastHouseholdStore: Sendable {
    private static let key = "abodenestly.lastHousehold"
    /// `UserDefaults` is not `Sendable`, so only the suite name is stored. `nil` is the standard defaults.
    private let suiteName: String?

    public init(suiteName: String? = nil) {
        self.suiteName = suiteName
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    public func read() -> UUID? {
        defaults.string(forKey: Self.key).flatMap(UUID.init(uuidString:))
    }

    public func write(_ id: UUID) {
        defaults.set(id.uuidString, forKey: Self.key)
    }
}
