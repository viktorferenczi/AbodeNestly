import XCTest

/// E2E-CORE-1, create half: sign in, create a household from Welcome, land on Home, and check the
/// server seeded the defaults. Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and
/// `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY` (see apps/ios/README.md).
@MainActor
final class WelcomeUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var user: E2E.TestUser?

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        user = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        guard let user else { return }
        // The household first: it references the user through `created_by`.
        let households = try? await E2E.rows(key: serviceRoleKey, table: "household", query: "created_by=eq.\(user.id)&select=id")
        for id in households?.compactMap({ $0["id"] as? String }) ?? [] {
            _ = try? await URLSession.shared.data(
                for: E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/household?id=eq.\(id)", method: "DELETE")
            )
        }
        await E2E.deleteUser(key: serviceRoleKey, id: user.id)
    }

    func testCORE02_createHouseholdLandsOnHomeWithDefaultsSeeded() async throws {
        let user = try XCTUnwrap(user)
        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)

        // Welcome greets the person by display name.
        XCTAssertTrue(app.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.staticTexts["welcome.title"].label, "Welcome, \(user.localPart)")

        let name = app.textFields["welcome.name"]
        name.tap()
        name.typeText("Test Home")
        app.buttons["welcome.create"].tap()

        // B4: the new household opens on its Home tab.
        XCTAssertTrue(app.staticTexts["tab.home.content"].waitForExistence(timeout: 15))

        // The server seeded one list and the three default locations.
        let households = try await E2E.rows(
            key: serviceRoleKey, table: "household", query: "created_by=eq.\(user.id)&select=id,name"
        )
        XCTAssertEqual(households.count, 1)
        XCTAssertEqual(households.first?["name"] as? String, "Test Home")
        let householdId = try XCTUnwrap(households.first?["id"] as? String)

        let lists = try await E2E.rows(
            key: serviceRoleKey, table: "shopping_list", query: "household_id=eq.\(householdId)&select=name"
        )
        XCTAssertEqual(lists.compactMap { $0["name"] as? String }, ["Groceries"])
        let locations = try await E2E.rows(
            key: serviceRoleKey, table: "location", query: "household_id=eq.\(householdId)&select=name"
        )
        XCTAssertEqual(Set(locations.compactMap { $0["name"] as? String }), ["Fridge", "Freezer", "Pantry"])
    }
}
