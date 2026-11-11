import Contract
import Foundation

/// The slice of a session the app reads. Keeps Supabase types out of views, models and tests.
public struct AuthSession: Sendable, Equatable {
    public let userId: UUID
    public let email: String?

    public init(userId: UUID, email: String?) {
        self.userId = userId
        self.email = email
    }
}

public enum AuthError: Error, Sendable, Equatable {
    case invalidEmail
    case rateLimited
    case network
    /// The person dismissed the Apple sheet or the Google web view. Not shown as an error.
    case cancelled
    case unknown
}

/// The calls this app makes to auth and profile storage; mirrors web `AuthClient`.
/// Named `AuthService` so it never clashes with supabase-swift's `AuthClient`.
public protocol AuthService: Sendable {
    /// The stored session, even if its access token has expired (an existing session stays usable offline).
    func currentSession() async -> AuthSession?
    func sessionChanges() -> AsyncStream<AuthSession?>
    func sendMagicLink(email: String, redirectTo: URL) async throws(AuthError)
    func signInWithApple(idToken: String, nonce: String) async throws(AuthError)
    func signInWithGoogle(redirectTo: URL) async throws(AuthError)
    /// Completes a magic-link sign-in from the app's custom-scheme callback URL.
    func handle(url: URL) async
    func signOut() async
    /// The JWT for API calls. `refresh: true` forces a token refresh (after a 401); `nil` when there is no session.
    func accessToken(refresh: Bool) async -> String?
    /// `.success(nil)`: the server trigger has not created the row yet.
    func fetchProfile(userId: UUID) async -> Result<Profile?, AuthError>
    func fetchMemberships(userId: UUID) async -> Result<[Membership], AuthError>
}
