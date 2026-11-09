import XCTest

/// E2E-CORE-1, first half: sign in with a real PKCE magic link against `supabase start`.
/// Needs `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY` (see apps/ios/README.md); never uses Apple or Google (TESTING §5).
@MainActor
final class SignInUITests: XCTestCase {
    private let supabaseURL = URL(string: "http://127.0.0.1:54321")!
    private let mailpitURL = URL(string: "http://127.0.0.1:54324")!
    private var serviceRoleKey = ""
    private var userId = ""
    private var email = ""
    private var localPart = ""

    override func setUp() async throws {
        continueAfterFailure = false
        guard let key = ProcessInfo.processInfo.environment["SUPABASE_SERVICE_ROLE_KEY"], !key.isEmpty else {
            throw XCTSkip("Set TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY to run the e2e journey")
        }
        serviceRoleKey = key
        // TESTING §5: every run creates its own user and deletes it at the end.
        localPart = "e2e-\(UUID().uuidString.lowercased())"
        email = "\(localPart)@test.local"
        var request = adminRequest(path: "auth/v1/admin/users", method: "POST")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["email": email, "email_confirm": true])
        let (data, _) = try await URLSession.shared.data(for: request)
        userId = try XCTUnwrap((try JSONSerialization.jsonObject(with: data) as? [String: Any])?["id"] as? String)
    }

    override func tearDown() async throws {
        guard !userId.isEmpty else { return }
        _ = try? await URLSession.shared.data(for: adminRequest(path: "auth/v1/admin/users/\(userId)", method: "DELETE"))
    }


    func testCORE01_magicLinkLandsOnWelcomeWithDisplayNameAndSurvivesRelaunch() async throws {
        let app = XCUIApplication()
        app.launchArguments = ["-reset-session"]
        app.launch()

        // AC1: signed out → sign-in screen.
        XCTAssertTrue(app.staticTexts["signin.title"].waitForExistence(timeout: 10))

        let field = app.textFields["signin.email"]
        field.tap()
        field.typeText(email)
        app.buttons["signin.send"].tap()
        XCTAssertTrue(app.staticTexts["signin.linkSent"].waitForExistence(timeout: 10))

        // AC4: a real PKCE exchange. The verify URL answers with a redirect to the app's custom scheme;
        // opening that Location in the same install lets the SDK use the stored code verifier.
        let callback = try await callbackURLFromMagicLink()
        app.open(callback)
        acceptOpenPromptIfShown()

        // AC4: no household yet → Welcome. AC8: profile trigger set display_name to the email local part.
        XCTAssertTrue(app.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.staticTexts["topbar.trailing"].label, localPart)

        // AC6: killed and reopened (no -reset-session) → still signed in.
        app.terminate()
        let relaunched = XCUIApplication()
        relaunched.launch()
        XCTAssertTrue(relaunched.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        XCTAssertEqual(relaunched.staticTexts["topbar.trailing"].label, localPart)
    }

    // MARK: Helpers

    private func adminRequest(path: String, method: String) -> URLRequest {
        var request = URLRequest(url: supabaseURL.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue(serviceRoleKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(serviceRoleKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return request
    }

    private func callbackURLFromMagicLink() async throws -> URL {
        let verifyURL = try await magicLinkFromMailpit()
        let noFollow = NoRedirectDelegate()
        let session = URLSession(configuration: .ephemeral, delegate: noFollow, delegateQueue: nil)
        let (_, response) = try await session.data(from: verifyURL)
        let location = try XCTUnwrap((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Location"))
        return try XCTUnwrap(URL(string: location))
    }

    private func magicLinkFromMailpit() async throws -> URL {
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

    private func acceptOpenPromptIfShown() {
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
