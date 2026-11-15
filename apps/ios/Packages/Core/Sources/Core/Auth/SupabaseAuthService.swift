import Auth
import Contract
import Foundation
import Shared

/// supabase-swift `AuthClient` (Keychain storage, PKCE) for sign-in, plus `URLSession` reads of
/// profile and membership rows with the user's JWT (the SDK is limited to Auth/Realtime, SPEC §3).
public final class SupabaseAuthService: AuthService {
    private let client: Auth.AuthClient
    private let supabaseURL: URL
    private let anonKey: String
    private let urlSession: URLSession
    private let callbackScheme: String?

    public init(config: AppConfig, urlSession: URLSession = .shared) {
        self.supabaseURL = config.supabaseURL
        self.anonKey = config.supabaseAnonKey
        self.urlSession = urlSession
        self.callbackScheme = config.authCallbackURL.scheme
        self.client = Auth.AuthClient(
            url: config.supabaseURL.appendingPathComponent("auth/v1"),
            headers: ["apikey": config.supabaseAnonKey],
            flowType: .pkce,
            redirectToURL: config.authCallbackURL,
            localStorage: KeychainLocalStorage(),
            fetch: { try await urlSession.data(for: $0) },
            // An expired token must not read as signed out while offline; REST calls refresh it on demand.
            emitLocalSessionAsInitialSession: true
        )
    }

    // MARK: Session

    public func currentSession() async -> AuthSession? {
        client.currentSession.map(Self.authSession)
    }

    public func sessionChanges() -> AsyncStream<AuthSession?> {
        let changes = client.authStateChanges
        return AsyncStream { continuation in
            let task = Task {
                for await change in changes {
                    continuation.yield(change.session.map(Self.authSession))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func handle(url: URL) async {
        guard AuthCallback.matches(url, scheme: callbackScheme) else { return }
        // A bad or expired link leaves the person signed out on the sign-in screen, where they can ask for a new one.
        _ = try? await client.session(from: url)
    }

    public func signOut() async {
        // Local scope: the device forgets the session even when offline.
        try? await client.signOut(scope: .local)
    }

    public func accessToken(refresh: Bool) async -> String? {
        do {
            // `session` itself refreshes an expired token; `refreshSession` forces it.
            return refresh ? try await client.refreshSession().accessToken : try await client.session.accessToken
        } catch {
            return nil
        }
    }

    // MARK: Sign in

    public func sendMagicLink(email: String, redirectTo: URL) async throws(AuthError) {
        do {
            try await client.signInWithOTP(email: email, redirectTo: redirectTo)
        } catch {
            throw Self.map(error)
        }
    }

    public func signInWithApple(idToken: String, nonce: String) async throws(AuthError) {
        do {
            try await client.signInWithIdToken(
                credentials: OpenIDConnectCredentials(provider: .apple, idToken: idToken, nonce: nonce)
            )
        } catch {
            throw Self.map(error)
        }
    }

    public func signInWithGoogle(redirectTo: URL) async throws(AuthError) {
        do {
            try await client.signInWithOAuth(provider: .google, redirectTo: redirectTo)
        } catch {
            throw Self.map(error)
        }
    }

    // MARK: Reads (PostgREST with the user JWT, RLS applies)

    public func fetchProfile(userId: UUID) async -> Result<Profile?, AuthError> {
        do {
            let rows: [Profile] = try await get(
                "profile", query: [("select", "*"), ("id", "eq.\(userId.uuidString.lowercased())")]
            )
            return .success(rows.first)
        } catch {
            return .failure(Self.map(error))
        }
    }

    public func fetchMemberships(userId: UUID) async -> Result<[Membership], AuthError> {
        struct Row: Decodable {
            struct HouseholdRow: Decodable {
                let id: UUID
                let moduleShopping: Bool
                let modulePantry: Bool
                let moduleMaintenance: Bool
                let deletedAt: Date?
                enum CodingKeys: String, CodingKey {
                    case id, deletedAt = "deleted_at"
                    case moduleShopping = "module_shopping"
                    case modulePantry = "module_pantry"
                    case moduleMaintenance = "module_maintenance"
                }
            }
            let householdId: UUID
            let joinedAt: Date
            let household: HouseholdRow?
            enum CodingKeys: String, CodingKey {
                case household
                case householdId = "household_id"
                case joinedAt = "joined_at"
            }
        }
        do {
            let rows: [Row] = try await get(
                "member",
                query: [
                    ("select", "household_id,joined_at,household(id,module_shopping,module_pantry,module_maintenance,deleted_at)"),
                    ("user_id", "eq.\(userId.uuidString.lowercased())"),
                    ("left_at", "is.null"),
                ]
            )
            let memberships = rows.compactMap { row -> Membership? in
                guard let household = row.household, household.deletedAt == nil else { return nil }
                return Membership(
                    householdId: row.householdId,
                    joinedAt: row.joinedAt,
                    household: LandingHousehold(
                        id: household.id,
                        moduleShopping: household.moduleShopping,
                        modulePantry: household.modulePantry,
                        moduleMaintenance: household.moduleMaintenance
                    )
                )
            }
            return .success(memberships)
        } catch {
            return .failure(Self.map(error))
        }
    }

    // MARK: Writes (profile row and avatar object, user JWT)

    public func updateProfile(userId: UUID, edit: ProfileEdit) async -> Result<Void, AuthError> {
        do {
            var components = URLComponents(
                url: supabaseURL.appendingPathComponent("rest/v1/profile"), resolvingAgainstBaseURL: false
            )!
            components.queryItems = [URLQueryItem(name: "id", value: "eq.\(userId.uuidString.lowercased())")]
            let (data, _) = try await send(
                url: components.url!, method: "PATCH", body: try JSONEncoder().encode(edit),
                headers: ["Content-Type": "application/json", "Prefer": "return=representation", "Accept": "application/json"]
            )
            return Self.updateOutcome(data)
        } catch {
            return .failure(Self.map(error))
        }
    }

    /// PostgREST reports zero matched rows as success; a missing own row must not drop the queued edit silently.
    static func updateOutcome(_ representation: Data) -> Result<Void, AuthError> {
        let rows = (try? JSONSerialization.jsonObject(with: representation)) as? [Any]
        return (rows?.isEmpty ?? true) ? .failure(.unknown) : .success(())
    }

    public func uploadAvatar(path: String, data: Data) async -> Result<Void, AuthError> {
        do {
            _ = try await send(
                url: supabaseURL.appendingPathComponent("storage/v1/object/households/\(path)"), method: "POST",
                body: data, headers: ["Content-Type": "image/jpeg"]
            )
            return .success(())
        } catch {
            return .failure(Self.map(error))
        }
    }

    public func avatarURL(path: String) async -> URL? {
        struct Signed: Decodable { let signedURL: String }
        do {
            let (data, _) = try await send(
                url: supabaseURL.appendingPathComponent("storage/v1/object/sign/households/\(path)"), method: "POST",
                body: try JSONEncoder().encode(["expiresIn": 3600]),
                headers: ["Content-Type": "application/json", "Accept": "application/json"]
            )
            let signed = try JSONDecoder().decode(Signed.self, from: data)
            // The API returns a path relative to `storage/v1` ("/object/sign/…?token=…").
            return URL(string: supabaseURL.absoluteString + "/storage/v1" + signed.signedURL)
        } catch {
            return nil
        }
    }

    // MARK: Helpers

    /// A request with the user's JWT and the anon key; non-2xx is `URLError(.badServerResponse)`.
    private func send(url: URL, method: String, body: Data?, headers: [String: String]) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        // `session` refreshes an expired access token first.
        request.setValue("Bearer \(try await client.session.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(anonKey, forHTTPHeaderField: "apikey")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let (data, response) = try await urlSession.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard (200..<300).contains(http.statusCode) else { throw HTTPStatusError(status: http.statusCode) }
        return (data, http)
    }

    private func get<T: Decodable>(_ table: String, query: [(String, String)]) async throws -> T {
        var components = URLComponents(
            url: supabaseURL.appendingPathComponent("rest/v1/\(table)"), resolvingAgainstBaseURL: false
        )!
        components.queryItems = query.map { URLQueryItem(name: $0.0, value: $0.1) }
        var request = URLRequest(url: components.url!)
        // `session` refreshes an expired access token first.
        request.setValue("Bearer \(try await client.session.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(anonKey, forHTTPHeaderField: "apikey")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await urlSession.data(for: request)
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try PostgrestJSON.decoder.decode(T.self, from: data)
    }

    private static func authSession(_ session: Session) -> AuthSession {
        AuthSession(userId: session.user.id, email: session.user.email)
    }

    /// Mirrors web `toAuthError`.
    static func map(_ error: Error) -> AuthError {
        if let error = error as? AuthError { return error }
        if let error = error as? HTTPStatusError {
            if error.status == 429 { return .rateLimited }
            return error.status >= 500 || error.status == 408 ? .server : .unknown
        }
        if error is CancellationError { return .cancelled }
        if let error = error as? URLError {
            switch error.code {
            case .cancelled: return .cancelled
            case .notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotConnectToHost,
                .cannotFindHost, .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff:
                return .network
            default: return .unknown
            }
        }
        if let error = error as? Auth.AuthError {
            if case .api(_, let code, _, let response) = error {
                if code == .overEmailSendRateLimit || code == .overRequestRateLimit || response.statusCode == 429 {
                    return .rateLimited
                }
                if code == ErrorCode("email_address_invalid") || code == .validationFailed { return .invalidEmail }
            }
            return .unknown
        }
        if (error as NSError).domain == "com.apple.AuthenticationServices.WebAuthenticationSession",
            (error as NSError).code == 1
        {
            return .cancelled
        }
        return .unknown
    }
}

/// A non-2xx answer from PostgREST or Storage. 5xx is retryable; 4xx is a real rejection.
struct HTTPStatusError: Error { let status: Int }

/// PostgREST timestamps carry microseconds ("2026-11-09T20:31:12.123456+00:00").
enum PostgrestJSON {
    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            guard let date = parseDate(text) else {
                throw DecodingError.dataCorrupted(
                    .init(codingPath: decoder.codingPath, debugDescription: "Bad timestamp \(text)")
                )
            }
            return date
        }
        return decoder
    }

    /// ISO 8601 with fractional seconds, like JS `toISOString()`.
    static func format(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    static func parseDate(_ text: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: text) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: text)
    }
}

enum AuthCallback {
    /// Only `<scheme>://auth/callback`; any other URL the app is opened with is ignored.
    static func matches(_ url: URL, scheme: String?) -> Bool {
        url.scheme == scheme && url.host == "auth" && url.path == "/callback"
    }
}
