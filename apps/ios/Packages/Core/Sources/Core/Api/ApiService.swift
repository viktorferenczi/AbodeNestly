import Contract
import Foundation

/// Why an API call failed, as the Welcome screen needs to tell the person (mirrors web `ApiFailure`).
public enum ApiFailure: Error, Sendable, Equatable {
    case validation
    /// Accept-invite only: the code is wrong, used up or expired.
    case invalidCode
    case network
    case unknown
    /// Remove/leave: the caller is the only owner left (409 `last_owner`).
    case lastOwner
    case forbidden
    case notFound
}

/// The `profile` columns the Members screen shows; like web's `Pick<Profile, …>`. The read selects only these,
/// so the generated `Profile` (which also needs `created_at` / `updated_at`) cannot decode it.
public struct MemberProfile: Sendable, Codable, Hashable {
    public var id: UUID
    public var displayName: String
    public var avatarPath: String?

    public init(id: UUID, displayName: String, avatarPath: String? = nil) {
        self.id = id
        self.displayName = displayName
        self.avatarPath = avatarPath
    }

    enum CodingKeys: String, CodingKey {
        case id
        case displayName = "display_name"
        case avatarPath = "avatar_path"
    }
}

/// An active member with the profile shown beside them (`nil` when RLS hides it).
public struct MemberRow: Sendable, Codable, Hashable, Identifiable {
    public var member: Member
    public var profile: MemberProfile?
    public var id: UUID { member.userId }

    public init(member: Member, profile: MemberProfile?) {
        self.member = member
        self.profile = profile
    }
}

/// The API calls the app makes; mirrors web `ApiClient`. Tests use `FakeApiService`.
public protocol ApiService: Sendable {
    /// `POST /households` (201). The server seeds the Groceries list and Fridge/Freezer/Pantry locations.
    func createHousehold(name: String) async -> Result<Household, ApiFailure>
    /// `POST /invites/{code}/accept` (CORE-03). Success carries the joined household's id.
    func acceptInvite(code: String) async -> Result<UUID, ApiFailure>
    /// `POST /households/{id}/invites` with `{}` (201): the DB defaults apply (member, 7 days, 10 uses).
    func createInvite(householdId: UUID) async -> Result<Invite, ApiFailure>
    /// Newest usable invite (not retired, not expired, uses left) or `nil`. Read under RLS, no API route.
    func fetchLiveInvite(householdId: UUID) async -> Result<Invite?, ApiFailure>
    /// Sets `deleted_at`; the only change a client may make to an invite (SPEC §14).
    func retireInvite(id: UUID) async -> Result<Void, ApiFailure>
    /// Active members of the household with their profiles. Read under RLS, no API route.
    func fetchMembers(householdId: UUID) async -> Result<[MemberRow], ApiFailure>
    /// `DELETE /households/{id}/members/{user_id}`: remove someone, or leave when `userId` is the caller.
    func removeMember(householdId: UUID, userId: UUID) async -> Result<Void, ApiFailure>
    /// Promotes a member to owner: a direct `member.role` update under RLS (SPEC §7, network-only core settings).
    func makeOwner(householdId: UUID, userId: UUID) async -> Result<Void, ApiFailure>
}
