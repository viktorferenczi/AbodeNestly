import Contract
import Foundation

/// Every household the person belongs to, per user, so the switcher works offline (SPEC §6.1). Mirrors web
/// `householdsCache.ts`; keyed by user so a second account on the device never sees the first one's households.
public struct HouseholdsCache: Sendable {
    /// `UserDefaults` is not `Sendable`, so only the suite name is stored. `nil` is the standard defaults.
    private let suiteName: String?

    public init(suiteName: String? = nil) {
        self.suiteName = suiteName
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    static func key(userId: UUID) -> String {
        "abodenestly.households.\(userId.uuidString.lowercased())"
    }

    /// Empty when nothing was stored or the stored shape no longer decodes.
    public func read(userId: UUID) -> [HouseholdWithRole] {
        defaults.data(forKey: Self.key(userId: userId))
            .flatMap { try? PostgrestJSON.decoder.decode([HouseholdWithRole].self, from: $0) } ?? []
    }

    public func write(_ list: [HouseholdWithRole], userId: UUID) {
        guard let data = try? Self.encoder.encode(list) else { return }
        defaults.set(data, forKey: Self.key(userId: userId))
    }

    /// Adds or replaces one household, keeping the rest; offline the switcher only knows what was cached.
    public func upsert(_ entry: HouseholdWithRole, userId: UUID) {
        var list = read(userId: userId)
        if let index = list.firstIndex(where: { $0.household.id == entry.household.id }) {
            list[index] = entry
        } else {
            list.append(entry)
        }
        write(list, userId: userId)
    }

    public func remove(householdId: UUID, userId: UUID) {
        write(read(userId: userId).filter { $0.household.id != householdId }, userId: userId)
    }

    public func clear(userId: UUID) {
        defaults.removeObject(forKey: Self.key(userId: userId))
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
