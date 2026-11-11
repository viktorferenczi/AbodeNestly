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
}
