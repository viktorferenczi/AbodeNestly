import Contract
import Foundation

/// Why an API call failed, as the Welcome screen needs to tell the person (mirrors web `ApiFailure`).
public enum ApiFailure: Error, Sendable, Equatable {
    case validation
    /// Accept-invite only: the code is wrong, used up or expired.
    case invalidCode
    case network
    case unknown
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
}
