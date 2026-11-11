import Contract
import Foundation
import Shared

/// `URLSession` client for the Vercel API. Attaches the user's JWT and, on a 401, refreshes the session
/// once and retries (swift-ios skill, Auth).
public final class HttpApiService: ApiService {
    private let baseURL: URL
    private let auth: any AuthService
    private let urlSession: URLSession

    public init(config: AppConfig, auth: any AuthService, urlSession: URLSession = .shared) {
        self.baseURL = config.apiBaseURL
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
        let first = try await send(path, body: body, token: await auth.accessToken(refresh: false))
        guard first.1 == 401 else { return first }
        return try await send(path, body: body, token: await auth.accessToken(refresh: true))
    }

    private func send(_ path: String, body: Data, token: String?) async throws -> (Data, Int) {
        // `path` is already percent-encoded; `URL.appending(path:)` would encode the `%` again.
        let base = baseURL.absoluteString.hasSuffix("/") ? String(baseURL.absoluteString.dropLast()) : baseURL.absoluteString
        guard let url = URL(string: "\(base)/\(path)") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
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
