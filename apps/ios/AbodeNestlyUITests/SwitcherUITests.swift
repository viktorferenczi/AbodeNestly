import XCTest

/// E2E-CORE-2: a person in two households switches between them; the tab is kept, only the target household's
/// local rows are shown, and the switch works offline. Households and members are seeded with the service role
/// (the API is not involved); the app seeds one local list per household through the Debug-only `E2E_LOCAL_LISTS`.
/// Needs `supabase start` and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class SwitcherUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var user: E2E.TestUser?
    private let alphaId = UUID().uuidString.lowercased()
    private let betaId = UUID().uuidString.lowercased()

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        user = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        guard let user else { return }
        // The households first: they reference the user through `created_by`; members cascade.
        for id in [alphaId, betaId] {
            _ = try? await URLSession.shared.data(
                for: E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/household?id=eq.\(id)", method: "DELETE")
            )
        }
        await E2E.deleteUser(key: serviceRoleKey, id: user.id)
    }

    private func insert(_ table: String, _ row: [String: Any]) async throws {
        var request = E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/\(table)", method: "POST")
        request.httpBody = try JSONSerialization.data(withJSONObject: row)
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 201, "insert into \(table)")
    }

    /// Alpha (Pantry on) is joined first, so it is where the person lands; Beta has Pantry off.
    private func seedHouseholds(for user: E2E.TestUser) async throws {
        try await insert("household", ["id": alphaId, "name": "Alpha", "created_by": user.id])
        try await insert("member", ["household_id": alphaId, "user_id": user.id, "role": "owner", "joined_at": "2026-01-01T00:00:00Z"])
        try await insert("household", ["id": betaId, "name": "Beta", "created_by": user.id, "module_pantry": false])
        try await insert("member", ["household_id": betaId, "user_id": user.id, "role": "member", "joined_at": "2026-02-01T00:00:00Z"])
    }

    private func title(_ app: XCUIApplication) -> XCUIElement { app.buttons["topbar.title"] }

    private func waitForTitle(_ app: XCUIApplication, _ name: String, file: StaticString = #filePath, line: UInt = #line) {
        let matches = NSPredicate(format: "value == %@", name)
        let result = XCTWaiter().wait(for: [expectation(for: matches, evaluatedWith: title(app))], timeout: 15)
        XCTAssertEqual(result, .completed, "title should be \(name), is \(String(describing: title(app).value))", file: file, line: line)
    }

    private func switchTo(_ app: XCUIApplication, rowId: String) {
        title(app).tap()
        let row = app.descendants(matching: .any)["switcher.row.\(rowId)"]
        XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
        row.tap()
        XCTAssertTrue(app.buttons["switcher.create"].waitForNonExistence(timeout: 15), "the sheet closes after the switch")
    }

    private func localLists(_ app: XCUIApplication, equals expected: String, file: StaticString = #filePath, line: UInt = #line) {
        let label = app.staticTexts["shopping.local-lists"]
        XCTAssertTrue(label.waitForExistence(timeout: 10), file: file, line: line)
        let matches = NSPredicate(format: "label == %@", expected)
        let result = XCTWaiter().wait(for: [expectation(for: matches, evaluatedWith: label)], timeout: 10)
        XCTAssertEqual(result, .completed, "local lists should be \(expected), are \(label.label)", file: file, line: line)
    }

    func testCORE07_switchKeepsTab_showsOnlyTargetRows_andWorksOffline() async throws {
        let user = try XCTUnwrap(user)
        try await seedHouseholds(for: user)
        let app = XCUIApplication()
        let seeds = try JSONSerialization.data(withJSONObject: [alphaId: "Alpha list", betaId: "Beta list"])
        app.launchEnvironment["E2E_LOCAL_LISTS"] = String(decoding: seeds, as: UTF8.self)
        try await E2E.signIn(app, as: user)

        // Lands on Alpha's Shopping tab (all modules on, Pantry included).
        XCTAssertTrue(app.tabBars.buttons["Shopping"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.tabBars.buttons["Pantry"].exists)
        waitForTitle(app, "Alpha")
        localLists(app, equals: "Alpha list")

        // Switch to Beta: same tab, only Beta's rows, Pantry gone.
        switchTo(app, rowId: betaId)
        waitForTitle(app, "Beta")
        XCTAssertTrue(app.tabBars.buttons["Shopping"].isSelected)
        XCTAssertFalse(app.tabBars.buttons["Pantry"].exists)
        localLists(app, equals: "Beta list")

        // Relaunch without a reset, forced offline: switching back needs no network.
        app.terminate()
        app.launchArguments = ["-force-offline"]
        app.launch()
        waitForTitle(app, "Beta")
        switchTo(app, rowId: alphaId)
        waitForTitle(app, "Alpha")
        app.tabBars.buttons["Shopping"].tap()
        localLists(app, equals: "Alpha list")
    }
}
