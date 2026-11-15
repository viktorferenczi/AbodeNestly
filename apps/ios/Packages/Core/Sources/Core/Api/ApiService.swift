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

/// The viewer's household and role in it. Decodes a PostgREST `member` row (`role`, embedded `household`) and
/// doubles as the offline snapshot shape.
public struct HouseholdWithRole: Sendable, Codable, Equatable {
    public var household: Household
    public var role: Member.Role

    public init(household: Household, role: Member.Role) {
        self.household = household
        self.role = role
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
    /// The viewer's active membership with its (not deleted) household, or `nil` when there is none. Read under RLS.
    func fetchHousehold(householdId: UUID, userId: UUID) async -> Result<HouseholdWithRole?, ApiFailure>
    /// Every household the viewer is an active member of, with their role in each. Same read as `fetchHousehold`
    /// without the household filter (CORE-07).
    func fetchMyHouseholds(userId: UUID) async -> Result<[HouseholdWithRole], ApiFailure>
    /// `PATCH /households/{id}/modules` (owner only). Pass only the flags to change. 403 → `.forbidden`, 400 → `.validation`.
    func updateModules(householdId: UUID, shopping: Bool?, pantry: Bool?, maintenance: Bool?) async -> Result<Household, ApiFailure>
    /// Name and/or currency: a direct `household` update under RLS (SPEC §7); a non-owner gets `.forbidden`.
    func updateHousehold(householdId: UUID, name: String?, currency: String?) async -> Result<Household, ApiFailure>
}
