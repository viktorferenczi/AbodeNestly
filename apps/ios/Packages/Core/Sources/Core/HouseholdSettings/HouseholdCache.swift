import Foundation

/// The last household and role read per user, so Settings stays readable offline. Keyed by user as well as household
/// so a second account on the device never reads the first one's role (web `HouseholdProvider` does the same).
/// iOS has no local household table until sync exists.
public struct HouseholdCache: Sendable {
    /// `UserDefaults` is not `Sendable`, so only the suite name is stored. `nil` is the standard defaults.
    private let suiteName: String?

    public init(suiteName: String? = nil) {
        self.suiteName = suiteName
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    static func key(userId: UUID, householdId: UUID) -> String {
        "abodenestly.household.\(userId.uuidString.lowercased()).\(householdId.uuidString.lowercased())"
    }

    /// `nil` when nothing was stored or the stored shape no longer decodes.
    public func read(userId: UUID, householdId: UUID) -> HouseholdWithRole? {
        defaults.data(forKey: Self.key(userId: userId, householdId: householdId))
            .flatMap { try? PostgrestJSON.decoder.decode(HouseholdWithRole.self, from: $0) }
    }

    public func write(_ value: HouseholdWithRole, userId: UUID) {
        guard let data = try? Self.encoder.encode(value) else { return }
        defaults.set(data, forKey: Self.key(userId: userId, householdId: value.household.id))
        // The switcher's list follows, so a rename or role change shows there without a refetch.
        HouseholdsCache(suiteName: suiteName).upsert(value, userId: userId)
    }

    public func clear(userId: UUID, householdId: UUID) {
        defaults.removeObject(forKey: Self.key(userId: userId, householdId: householdId))
        HouseholdsCache(suiteName: suiteName).remove(householdId: householdId, userId: userId)
    }

    /// Dates go out as ISO 8601 text so `PostgrestJSON.decoder` reads them back.
    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(PostgrestJSON.format(date))
        }
        return encoder
    }
}
