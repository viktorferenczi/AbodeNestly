import XCTest

/// Shared by the e2e journeys (TESTING §8): admin-API users, Mailpit magic links and the sign-in steps.
/// Needs `supabase start` and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY` (see apps/ios/README.md).
enum E2E {
    static let supabaseURL = URL(string: "http://127.0.0.1:54321")!
    static let mailpitURL = URL(string: "http://127.0.0.1:54324")!

    struct TestUser {
        let id: String
        let email: String
        let localPart: String
    }

    static func serviceRoleKey() throws -> String {
        guard let key = ProcessInfo.processInfo.environment["SUPABASE_SERVICE_ROLE_KEY"], !key.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY to run the e2e journey")
        }
        return key
    }

    /// Service-role request; bypasses RLS, so only tests use it.
    static func adminRequest(key: String, path: String, method: String = "GET") -> URLRequest {
        var request = URLRequest(url: URL(string: path, relativeTo: supabaseURL)!.absoluteURL)
        request.httpMethod = method
        request.setValue(key, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return request
    }

    /// TESTING §5: every run creates its own user and deletes it at the end.
    static func createUser(key: String) async throws -> TestUser {
        let localPart = "e2e-\(UUID().uuidString.lowercased())"
        let email = "\(localPart)@test.local"
        var request = adminRequest(key: key, path: "auth/v1/admin/users", method: "POST")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["email": email, "email_confirm": true])
        let (data, _) = try await URLSession.shared.data(for: request)
        let id = try XCTUnwrap((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["id"] as? String)
        return TestUser(id: id, email: email, localPart: localPart)
    }

    static func deleteUser(key: String, id: String) async {
        _ = try? await URLSession.shared.data(for: adminRequest(key: key, path: "auth/v1/admin/users/\(id)", method: "DELETE"))
    }

    /// Rows of a PostgREST table read with the service role.
    static func rows(key: String, table: String, query: String) async throws -> [[String: Any]] {
        let (data, _) = try await URLSession.shared.data(for: adminRequest(key: key, path: "rest/v1/\(table)?\(query)"))
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [[String: Any]])
    }

    // MARK: Sign-in steps

    /// Launches signed out, asks for a magic link and completes the PKCE exchange through the app's callback URL.
    @MainActor
    static func signIn(_ app: XCUIApplication, as user: TestUser) async throws {
        app.launchArguments = ["-reset-session"]
        app.launch()

        // Signed out → sign-in screen.
        XCTAssertTrue(app.staticTexts["signin.title"].waitForExistence(timeout: 10))
        let field = app.textFields["signin.email"]
        field.tap()
        field.typeText(user.email)
        app.buttons["signin.send"].tap()
        XCTAssertTrue(app.staticTexts["signin.linkSent"].waitForExistence(timeout: 10))

        // A real PKCE exchange. The verify URL answers with a redirect to the app's custom scheme;
        // opening that Location in the same install lets the SDK use the stored code verifier.
        let callback = try await callbackURLFromMagicLink(email: user.email)
        app.open(callback)
        acceptOpenPromptIfShown()
    }

    static func callbackURLFromMagicLink(email: String) async throws -> URL {
        let verifyURL = try await magicLinkFromMailpit(email: email)
        let noFollow = NoRedirectDelegate()
        let session = URLSession(configuration: .ephemeral, delegate: noFollow, delegateQueue: nil)
        let (_, response) = try await session.data(from: verifyURL)
        let location = try XCTUnwrap((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Location"))
        return try XCTUnwrap(URL(string: location))
    }

    static func magicLinkFromMailpit(email: String) async throws -> URL {
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            var search = URLComponents(url: mailpitURL.appendingPathComponent("api/v1/search"), resolvingAgainstBaseURL: false)!
            search.queryItems = [URLQueryItem(name: "query", value: "to:\(email)")]
            let (data, _) = try await URLSession.shared.data(from: search.url!)
            let messages = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["messages"] as? [[String: Any]]
            if let id = messages?.first?["ID"] as? String {
                let (body, _) = try await URLSession.shared.data(from: mailpitURL.appendingPathComponent("api/v1/message/\(id)"))
                let html = (try JSONSerialization.jsonObject(with: body) as? [String: Any])?["HTML"] as? String ?? ""
                if let range = html.range(of: #"href="([^"]*/auth/v1/verify[^"]*)""#, options: .regularExpression) {
                    let raw = html[range].dropFirst(6).dropLast().replacingOccurrences(of: "&amp;", with: "&")
                    return try XCTUnwrap(URL(string: raw))
                }
            }
            try await Task.sleep(for: .milliseconds(500))
        }
        throw XCTSkip("No magic link email for \(email) in Mailpit (is `supabase start` running?)")
    }

    /// iOS may ask "Open in AbodeNestly?" when a custom-scheme URL is opened from outside the app.
    @MainActor
    static func acceptOpenPromptIfShown() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let open = springboard.alerts.buttons["Open"]
        if open.waitForExistence(timeout: 3) { open.tap() }
    }
}

private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(
        _ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
