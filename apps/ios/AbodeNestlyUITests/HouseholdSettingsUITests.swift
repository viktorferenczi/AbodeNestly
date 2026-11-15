import XCTest

/// E2E-CORE-3: an owner switches Pantry off in Settings; the Pantry tab disappears without a relaunch and
/// `household.module_pantry` is false. The "no pantry badges" check has nothing to assert until the SHOP/PANT screens exist.
/// Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class HouseholdSettingsUITests: XCTestCase {
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

    func testCORE05_owner_switchesPantryOff_tabDisappearsWithoutReload() async throws {
        let user = try XCTUnwrap(user)
        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)

        XCTAssertTrue(app.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        let name = app.textFields["welcome.name"]
        name.tap()
        name.typeText("Test Home")
        app.buttons["welcome.create"].tap()

        // All modules start on, so the Pantry tab is there.
        XCTAssertTrue(app.tabBars.buttons["Pantry"].waitForExistence(timeout: 15))

        app.tabBars.buttons["Home"].tap()
        XCTAssertTrue(app.staticTexts["tab.home.content"].waitForExistence(timeout: 10))
        app.buttons["home.settings"].tap()

        let pantry = app.switches["settings.module.pantry"]
        XCTAssertTrue(pantry.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertEqual(pantry.value as? String, "1")
        // The row is 48 pt tall at least (DESIGN A5); tapping its trailing switch flips it.
        pantry.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()

        // The tab goes away without a relaunch.
        let gone = NSPredicate(format: "exists == false")
        await fulfillment(of: [expectation(for: gone, evaluatedWith: app.tabBars.buttons["Pantry"])], timeout: 15)
        XCTAssertTrue(app.tabBars.buttons["Shopping"].exists)
        XCTAssertTrue(app.tabBars.buttons["Home"].exists)

        // The server has the flag.
        let households = try await E2E.rows(
            key: serviceRoleKey, table: "household", query: "created_by=eq.\(user.id)&select=module_pantry,module_shopping"
        )
        XCTAssertEqual(households.first?["module_pantry"] as? Bool, false)
        XCTAssertEqual(households.first?["module_shopping"] as? Bool, true)
    }
}
