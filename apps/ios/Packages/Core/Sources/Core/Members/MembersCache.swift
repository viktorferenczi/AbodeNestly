import Foundation

/// The last member list read per household, so Members stays readable offline. iOS has no local member or
/// profile tables yet (they arrive with sync); web keeps the same snapshot in `membersCache.ts`.
public struct MembersCache: Sendable {
    /// `UserDefaults` is not `Sendable`, so only the suite name is stored. `nil` is the standard defaults.
    private let suiteName: String?

    public init(suiteName: String? = nil) {
        self.suiteName = suiteName
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    static func key(_ householdId: UUID) -> String { "abodenestly.members.\(householdId.uuidString.lowercased())" }

    /// `nil` when nothing was stored or the stored shape no longer decodes, so a stale snapshot never crashes the screen.
    public func read(householdId: UUID) -> [MemberRow]? {
        defaults.data(forKey: Self.key(householdId)).flatMap { try? JSONDecoder().decode([MemberRow].self, from: $0) }
    }

    public func write(_ rows: [MemberRow], householdId: UUID) {
        guard let data = try? JSONEncoder().encode(rows) else { return }
        defaults.set(data, forKey: Self.key(householdId))
    }
}
