import XCTest

/// E2E-CORE-1, first half: sign in with a real PKCE magic link against `supabase start`.
/// Needs `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY` (see apps/ios/README.md); never uses Apple or Google (TESTING §5).
@MainActor
final class SignInUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var user: E2E.TestUser?

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        user = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        guard let user else { return }
        await E2E.deleteUser(key: serviceRoleKey, id: user.id)
    }

    func testCORE01_magicLinkLandsOnWelcomeWithDisplayNameAndSurvivesRelaunch() async throws {
        let user = try XCTUnwrap(user)
        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)

        // AC4: no household yet → Welcome. AC8: profile trigger set display_name to the email local part.
        XCTAssertTrue(app.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.staticTexts["topbar.trailing"].label, user.localPart)

        // AC6: killed and reopened (no -reset-session) → still signed in.
        app.terminate()
        let relaunched = XCUIApplication()
        relaunched.launch()
        XCTAssertTrue(relaunched.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        XCTAssertEqual(relaunched.staticTexts["topbar.trailing"].label, user.localPart)
    }
}
