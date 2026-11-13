import Contract
import Foundation
import Shared

/// `URLSession` client for the Vercel API. Attaches the user's JWT and, on a 401, refreshes the session
/// once and retries (swift-ios skill, Auth).
public final class HttpApiService: ApiService {
    private let baseURL: URL
    private let supabaseURL: URL
    private let anonKey: String
    private let auth: any AuthService
    private let urlSession: URLSession

    public init(config: AppConfig, auth: any AuthService, urlSession: URLSession = .shared) {
        self.baseURL = config.apiBaseURL
        self.supabaseURL = config.supabaseURL
        self.anonKey = config.supabaseAnonKey
        self.auth = auth
        self.urlSession = urlSession
    }

    public func createHousehold(name: String) async -> Result<Household, ApiFailure> {
        do {
            // `CreateHouseholdRequest` is not in the generated Swift models (the generator emits only the
            // `componentSchemas` that responses use), so the one-field body is built by hand.
            let body = try JSONSerialization.data(withJSONObject: ["name": name])
            let (data, status) = try await post("households", body: body)
            if status == 201 {
                guard let household = try? PostgrestJSON.decoder.decode(Household.self, from: data) else {
                    return .failure(.unknown)
                }
                return .success(household)
            }
            if Self.errorCode(in: data) == "validation" || status == 400 { return .failure(.validation) }
            return .failure(.unknown)
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func acceptInvite(code: String) async -> Result<UUID, ApiFailure> {
        // A code is user-typed text; `/` and `?` must not change the route.
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        guard let encoded = code.addingPercentEncoding(withAllowedCharacters: allowed) else {
            return .failure(.invalidCode)
        }
        do {
            let (data, status) = try await post("invites/\(encoded)/accept", body: Data("{}".utf8))
            guard (200..<300).contains(status) else {
                return .failure(Self.acceptFailure(status: status, code: Self.errorCode(in: data)))
            }
            struct Accepted: Decodable {
                let householdId: UUID
                enum CodingKeys: String, CodingKey { case householdId = "household_id" }
            }
            guard let accepted = try? JSONDecoder().decode(Accepted.self, from: data) else {
                return .failure(.unknown)
            }
            return .success(accepted.householdId)
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func createInvite(householdId: UUID) async -> Result<Invite, ApiFailure> {
        do {
            let (data, status) = try await post(
                "households/\(householdId.uuidString.lowercased())/invites", body: Data("{}".utf8)
            )
            guard status == 201, let invite = try? PostgrestJSON.decoder.decode(Invite.self, from: data) else {
                return .failure(.unknown)
            }
            return .success(invite)
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func fetchLiveInvite(householdId: UUID) async -> Result<Invite?, ApiFailure> {
        do {
            let url = try restURL(query: [
                ("household_id", "eq.\(householdId.uuidString.lowercased())"),
                ("deleted_at", "is.null"),
                ("expires_at", "gt.\(PostgrestJSON.format(Date()))"),
                ("order", "created_at.desc"),
            ])
            let (data, status) = try await perform(url: url, method: "GET", headers: restHeaders())
            guard status == 200, let invites = try? PostgrestJSON.decoder.decode([Invite].self, from: data) else {
                return .failure(.unknown)
            }
            // PostgREST cannot compare two columns, so `uses < max_uses` is filtered here.
            return .success(invites.first { $0.uses < $0.maxUses })
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func retireInvite(id: UUID) async -> Result<Void, ApiFailure> {
        do {
            let url = try restURL(query: [("id", "eq.\(id.uuidString.lowercased())")])
            let body = try JSONSerialization.data(withJSONObject: ["deleted_at": PostgrestJSON.format(Date())])
            var headers = restHeaders()
            headers["Prefer"] = "return=representation"
            let (data, status) = try await perform(url: url, method: "PATCH", headers: headers, body: body)
            // RLS hides rows silently, so an empty array means nothing was retired.
            guard (200..<300).contains(status),
                let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any], !rows.isEmpty
            else { return .failure(.unknown) }
            return .success(())
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    private func restURL(query: [(String, String)]) throws -> URL {
        var components = URLComponents(
            url: supabaseURL.appendingPathComponent("rest/v1/invite"), resolvingAgainstBaseURL: false
        )
        components?.queryItems = query.map { URLQueryItem(name: $0.0, value: $0.1) }
        guard let url = components?.url else { throw URLError(.badURL) }
        return url
    }

    private func restHeaders() -> [String: String] {
        ["apikey": anonKey, "Accept": "application/json", "Content-Type": "application/json"]
    }

    // CORE-03-api has not fixed its error code yet; the SQL raises `invalid_invite`. `not_found` and `gone`
    // are accepted so the pre-CORE-03 missing route (404 `not_found`) reads as an invalid code, like web.
    // Any other code, `validation` included, stays `unknown`.
    static func acceptFailure(status: Int, code: String?) -> ApiFailure {
        if let code {
            return ["invalid_invite", "not_found", "gone"].contains(code) ? .invalidCode : .unknown
        }
        // No parseable error body (e.g. a bare proxy 404): fall back to the status.
        return status == 404 || status == 410 ? .invalidCode : .unknown
    }

    // MARK: Transport

    private func post(_ path: String, body: Data) async throws -> (Data, Int) {
        // `path` is already percent-encoded; `URL.appending(path:)` would encode the `%` again.
        let base = baseURL.absoluteString.hasSuffix("/") ? String(baseURL.absoluteString.dropLast()) : baseURL.absoluteString
        guard let url = URL(string: "\(base)/\(path)") else { throw URLError(.badURL) }
        return try await perform(
            url: url, method: "POST",
            headers: ["Content-Type": "application/json", "Accept": "application/json"], body: body
        )
    }

    /// Sends with the user's JWT; on a 401 refreshes the session once and retries.
    private func perform(
        url: URL, method: String, headers: [String: String], body: Data? = nil
    ) async throws -> (Data, Int) {
        let first = try await send(url: url, method: method, headers: headers, body: body, token: await auth.accessToken(refresh: false))
        guard first.1 == 401 else { return first }
        return try await send(url: url, method: method, headers: headers, body: body, token: await auth.accessToken(refresh: true))
    }

    private func send(
        url: URL, method: String, headers: [String: String], body: Data?, token: String?
    ) async throws -> (Data, Int) {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await urlSession.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http.statusCode)
    }

    /// `{ "error": { "code": … } }`, the contract's `ApiError` shape.
    private static func errorCode(in data: Data) -> String? {
        struct Body: Decodable {
            struct Inner: Decodable { let code: String? }
            let error: Inner?
        }
        return (try? JSONDecoder().decode(Body.self, from: data))?.error?.code
    }

    private static func failure(for error: Error) -> ApiFailure {
        SupabaseAuthService.map(error) == .network ? .network : .unknown
    }
}
