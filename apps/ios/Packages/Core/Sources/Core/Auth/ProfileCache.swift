import Contract
import Foundation

/// The last profile read per user, so the name still renders when the profile read fails offline
/// (web keeps the same cache in `AuthProvider`).
public struct ProfileCache: Sendable {
    /// `UserDefaults` is not `Sendable`, so only the suite name is stored. `nil` is the standard defaults.
    private let suiteName: String?

    public init(suiteName: String? = nil) {
        self.suiteName = suiteName
    }

    private var defaults: UserDefaults {
        suiteName.flatMap(UserDefaults.init(suiteName:)) ?? .standard
    }

    private static func key(_ userId: UUID) -> String { "abodenestly.profile.\(userId.uuidString.lowercased())" }

    public func read(userId: UUID) -> Profile? {
        defaults.data(forKey: Self.key(userId)).flatMap { try? JSONDecoder().decode(Profile.self, from: $0) }
    }

    public func write(_ profile: Profile, userId: UUID) {
        guard let data = try? JSONEncoder().encode(profile) else { return }
        defaults.set(data, forKey: Self.key(userId))
    }
}
