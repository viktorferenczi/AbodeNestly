import Contract
import Foundation
import Shared
import Sync

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
            let url = try restURL(table: "invite", query: [
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
            let url = try restURL(table: "invite", query: [("id", "eq.\(id.uuidString.lowercased())")])
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

    public func fetchMembers(householdId: UUID) async -> Result<[MemberRow], ApiFailure> {
        do {
            let membersURL = try restURL(table: "member", query: [
                ("household_id", "eq.\(householdId.uuidString.lowercased())"),
                ("left_at", "is.null"),
                ("select", "*"),
            ])
            let (memberData, memberStatus) = try await perform(url: membersURL, method: "GET", headers: restHeaders())
            guard memberStatus == 200,
                let members = try? PostgrestJSON.decoder.decode([Member].self, from: memberData)
            else { return .failure(.unknown) }
            if members.isEmpty { return .success([]) }
            // No FK embed from member to profile, so the profiles are a second read.
            let ids = members.map { $0.userId.uuidString.lowercased() }.joined(separator: ",")
            let profilesURL = try restURL(table: "profile", query: [
                ("id", "in.(\(ids))"),
                ("select", "id,display_name,avatar_path"),
            ])
            let (profileData, profileStatus) = try await perform(url: profilesURL, method: "GET", headers: restHeaders())
            guard profileStatus == 200,
                let profiles = try? PostgrestJSON.decoder.decode([MemberProfile].self, from: profileData)
            else { return .failure(.unknown) }
            let byId = Dictionary(profiles.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            return .success(members.map { MemberRow(member: $0, profile: byId[$0.userId]) })
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func removeMember(householdId: UUID, userId: UUID) async -> Result<Void, ApiFailure> {
        do {
            let path = "households/\(householdId.uuidString.lowercased())/members/\(userId.uuidString.lowercased())"
            let (data, status) = try await perform(
                url: try apiURL(path), method: "DELETE", headers: ["Accept": "application/json"]
            )
            if status == 204 { return .success(()) }
            return .failure(Self.removeFailure(status: status, code: Self.errorCode(in: data)))
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func makeOwner(householdId: UUID, userId: UUID) async -> Result<Void, ApiFailure> {
        do {
            let url = try restURL(table: "member", query: [
                ("household_id", "eq.\(householdId.uuidString.lowercased())"),
                ("user_id", "eq.\(userId.uuidString.lowercased())"),
            ])
            let body = try JSONSerialization.data(withJSONObject: ["role": "owner"])
            var headers = restHeaders()
            headers["Prefer"] = "return=representation"
            let (data, status) = try await perform(url: url, method: "PATCH", headers: headers, body: body)
            // RLS hides rows silently, so an empty array means nothing was promoted.
            guard (200..<300).contains(status),
                let rows = (try? JSONSerialization.jsonObject(with: data)) as? [Any], !rows.isEmpty
            else { return .failure(.unknown) }
            return .success(())
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func fetchHousehold(householdId: UUID, userId: UUID) async -> Result<HouseholdWithRole?, ApiFailure> {
        do {
            let url = try restURL(table: "member", query: [
                ("select", "role,household:household!inner(*)"),
                ("household_id", "eq.\(householdId.uuidString.lowercased())"),
                ("user_id", "eq.\(userId.uuidString.lowercased())"),
                ("left_at", "is.null"),
                ("household.deleted_at", "is.null"),
            ])
            let (data, status) = try await perform(url: url, method: "GET", headers: restHeaders())
            guard status == 200, let rows = try? PostgrestJSON.decoder.decode([HouseholdWithRole].self, from: data) else {
                return .failure(.unknown)
            }
            return .success(rows.first)
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func fetchMyHouseholds(userId: UUID) async -> Result<[HouseholdWithRole], ApiFailure> {
        do {
            let url = try restURL(table: "member", query: [
                ("select", "role,household:household!inner(*)"),
                ("user_id", "eq.\(userId.uuidString.lowercased())"),
                ("left_at", "is.null"),
                ("household.deleted_at", "is.null"),
            ])
            let (data, status) = try await perform(url: url, method: "GET", headers: restHeaders())
            guard status == 200, let rows = try? PostgrestJSON.decoder.decode([HouseholdWithRole].self, from: data) else {
                return .failure(.unknown)
            }
            return .success(rows)
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func updateModules(
        householdId: UUID, shopping: Bool?, pantry: Bool?, maintenance: Bool?
    ) async -> Result<Household, ApiFailure> {
        do {
            // Like `createHousehold`, the request schema is not in the generated models; send only the flags given.
            var fields: [String: Bool] = [:]
            if let shopping { fields["shopping"] = shopping }
            if let pantry { fields["pantry"] = pantry }
            if let maintenance { fields["maintenance"] = maintenance }
            let body = try JSONSerialization.data(withJSONObject: fields)
            let (data, status) = try await perform(
                url: try apiURL("households/\(householdId.uuidString.lowercased())/modules"), method: "PATCH",
                headers: ["Content-Type": "application/json", "Accept": "application/json"], body: body
            )
            if status == 200 {
                guard let household = try? PostgrestJSON.decoder.decode(Household.self, from: data) else {
                    return .failure(.unknown)
                }
                return .success(household)
            }
            return .failure(Self.modulesFailure(status: status))
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func updateHousehold(householdId: UUID, name: String?, currency: String?) async -> Result<Household, ApiFailure> {
        do {
            let url = try restURL(table: "household", query: [("id", "eq.\(householdId.uuidString.lowercased())")])
            var fields: [String: String] = [:]
            if let name { fields["name"] = name }
            if let currency { fields["currency"] = currency }
            let body = try JSONSerialization.data(withJSONObject: fields)
            var headers = restHeaders()
            headers["Prefer"] = "return=representation"
            let (data, status) = try await perform(url: url, method: "PATCH", headers: headers, body: body)
            guard (200..<300).contains(status) else {
                return .failure(Self.updateHouseholdFailure(status: status, sqlState: Self.sqlState(in: data)))
            }
            // RLS hides rows silently, so an empty array means nothing changed.
            guard let rows = try? PostgrestJSON.decoder.decode([Household].self, from: data), let household = rows.first else {
                return .failure(.unknown)
            }
            return .success(household)
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func signAttachment(
        householdId: UUID, entity: String, entityId: UUID, filename: String, mime: String, size: Int
    ) async -> Result<SignedUpload, ApiFailure> {
        // `SignAttachmentRequest` / `SignAttachmentResponse` are not in the generated Swift models (the generator emits
        // only the entities), so the bodies are hand-built like `createHousehold`'s.
        struct Body: Encodable {
            let householdId: UUID
            let entity: String
            let entityId: UUID
            let filename: String
            let mime: String
            let size: Int
            enum CodingKeys: String, CodingKey {
                case householdId = "household_id", entity, entityId = "entity_id", filename, mime, size
            }
        }
        struct Response: Decodable {
            let attachmentId: UUID
            let uploadUrl: String
            let storagePath: String
            enum CodingKeys: String, CodingKey {
                case attachmentId = "attachment_id", uploadUrl = "upload_url", storagePath = "storage_path"
            }
        }
        do {
            let body = Body(
                householdId: householdId, entity: entity, entityId: entityId, filename: filename, mime: mime, size: size
            )
            let (data, status) = try await post("attachments/sign", body: try JSONEncoder().encode(body))
            guard (200..<300).contains(status) else {
                return .failure(Self.signFailure(status: status, code: Self.errorCode(in: data)))
            }
            guard let response = try? JSONDecoder().decode(Response.self, from: data), let url = URL(string: response.uploadUrl) else {
                return .failure(.unknown)
            }
            return .success(SignedUpload(attachmentId: response.attachmentId, uploadURL: url, storagePath: response.storagePath))
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func upload(to url: URL, data: Data, mime: String) async -> Result<Void, ApiFailure> {
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.setValue(mime, forHTTPHeaderField: "Content-Type")
        request.setValue("false", forHTTPHeaderField: "x-upsert")
        do {
            let (_, response) = try await urlSession.upload(for: request, from: data)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return .failure(.unknown) }
            return .success(())
        } catch {
            return .failure(Self.failure(for: error))
        }
    }

    public func product(barcode: String) async -> ProductLookupResult {
        // A code is user-typed text; `/` and `?` must not change the route.
        let allowed = CharacterSet.urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))
        guard let encoded = barcode.addingPercentEncoding(withAllowedCharacters: allowed) else { return .notFound }
        do {
            let (data, status) = try await perform(
                url: try apiURL("products/\(encoded)"), method: "GET", headers: ["Accept": "application/json"]
            )
            return Self.productResult(status: status, code: Self.errorCode(in: data), data: data)
        } catch {
            return Self.failure(for: error) == .network ? .offline : .failed
        }
    }

    /// The API's own error code wins; with no parseable body the status decides. Only a 502 can pass by itself.
    static func productResult(status: Int, code: String?, data: Data) -> ProductLookupResult {
        if status == 200 {
            guard let product = try? JSONDecoder().decode(Product.self, from: data) else { return .failed }
            return .found(product)
        }
        if code == "not_found" || status == 404 { return .notFound }
        if code == "invalid_barcode" || status == 400 { return .notFound }
        if code == "upstream_unavailable" || status == 502 { return .offline }
        return .failed
    }

    /// The API's own error code wins; with no parseable body the status decides.
    static func signFailure(status: Int, code: String?) -> ApiFailure {
        switch code {
        case "module_disabled": return .moduleDisabled
        case "forbidden": return .forbidden
        case "validation": return .validation
        case .some: return .unknown
        case nil:
            switch status {
            case 403: return .forbidden
            case 400: return .validation
            default: return .unknown
            }
        }
    }

    private func restURL(table: String, query: [(String, String)]) throws -> URL {
        var components = URLComponents(
            url: supabaseURL.appendingPathComponent("rest/v1/\(table)"), resolvingAgainstBaseURL: false
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

    /// The API's own error code wins; with no parseable body (a bare proxy response) the status decides.
    static func removeFailure(status: Int, code: String?) -> ApiFailure {
        switch code {
        case "last_owner": return .lastOwner
        case "forbidden": return .forbidden
        case "not_found": return .notFound
        case .some: return .unknown
        case nil:
            switch status {
            case 409: return .lastOwner
            case 403: return .forbidden
            case 404: return .notFound
            default: return .unknown
            }
        }
    }

    static func modulesFailure(status: Int) -> ApiFailure {
        switch status {
        case 403: .forbidden
        case 400: .validation
        default: .unknown
        }
    }

    /// SQLSTATE 42501 (insufficient privilege) is what RLS raises for a non-owner.
    static func updateHouseholdFailure(status: Int, sqlState: String?) -> ApiFailure {
        sqlState == "42501" || status == 403 ? .forbidden : .unknown
    }

    /// PostgREST error bodies carry the SQLSTATE as `{ "code": "42501", … }`.
    private static func sqlState(in data: Data) -> String? {
        struct Body: Decodable { let code: String? }
        return (try? JSONDecoder().decode(Body.self, from: data))?.code
    }

    // MARK: Transport

    /// `path` is already percent-encoded; `URL.appending(path:)` would encode the `%` again.
    private func apiURL(_ path: String) throws -> URL {
        let base = baseURL.absoluteString.hasSuffix("/") ? String(baseURL.absoluteString.dropLast()) : baseURL.absoluteString
        guard let url = URL(string: "\(base)/\(path)") else { throw URLError(.badURL) }
        return url
    }

    private func post(_ path: String, body: Data) async throws -> (Data, Int) {
        let url = try apiURL(path)
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

// MARK: /sync (SHOP-08)

extension HttpApiService: SyncTransport {
    public func push(_ body: SyncPushBody) async -> Result<SyncPushResult, SyncFailure> {
        do {
            let (data, status) = try await post("sync", body: JSONEncoder().encode(body))
            return Self.syncResult(status: status, data: data)
        } catch {
            return .failure(Self.syncFailure(for: error))
        }
    }

    public func pull(householdId: String, since: Int, limit: Int) async -> Result<SyncPullPage, SyncFailure> {
        do {
            let (data, status) = try await perform(
                url: try apiURL("sync?household_id=\(householdId)&since=\(since)&limit=\(limit)"), method: "GET",
                headers: ["Accept": "application/json"]
            )
            return Self.syncResult(status: status, data: data)
        } catch {
            return .failure(Self.syncFailure(for: error))
        }
    }

    public func refreshSession() async {
        _ = await auth.accessToken(refresh: true)
    }

    /// 200 decodes; 503 or the API's `retryable` code means try again shortly (deadlock); anything else stops the run.
    static func syncResult<T: Decodable>(status: Int, data: Data) -> Result<T, SyncFailure> {
        if status == 200 {
            guard let value = try? JSONDecoder().decode(T.self, from: data) else { return .failure(.other) }
            return .success(value)
        }
        return .failure(status == 503 || errorCode(in: data) == "retryable" ? .retryable : .other)
    }

    static func syncFailure(for error: Error) -> SyncFailure {
        failure(for: error) == .network ? .network : .other
    }
}
