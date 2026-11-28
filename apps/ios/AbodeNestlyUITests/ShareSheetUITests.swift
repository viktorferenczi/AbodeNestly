import XCTest

/// E2E-SHOP-4, the iOS part (TESTING §8, SHOP-13-ios) against the real API: open a list, List actions → Share, turn the
/// switch on, and the link and the unchecked-items line show; the server list now has a `share_token` and the link ends
/// with it. Stop sharing returns the switch to off and clears the server token. (The public page is web's, SHOP-13-web.)
/// Local only (CI skips UI tests). Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and
/// `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class ShareSheetUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var user: E2E.TestUser?
    private let householdId = UUID().uuidString.lowercased()
    private let listId = UUID().uuidString.lowercased()

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        user = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        guard let user else { return }
        _ = try? await URLSession.shared.data(
            for: E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/household?id=eq.\(householdId)", method: "DELETE")
        )
        await E2E.deleteUser(key: serviceRoleKey, id: user.id)
    }

    private func insert(_ table: String, _ row: [String: Any]) async throws {
        var request = E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/\(table)", method: "POST")
        request.httpBody = try JSONSerialization.data(withJSONObject: row)
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 201, "insert into \(table)")
    }

    private func serverToken() async throws -> String? {
        let rows = try await E2E.rows(key: serviceRoleKey, table: "shopping_list", query: "id=eq.\(listId)&select=share_token")
        return rows.first?["share_token"] as? String
    }

    func testSHOP13_shareAndStop() async throws {
        let user = try XCTUnwrap(user)
        try await insert("household", ["id": householdId, "name": "Share Home", "created_by": user.id])
        try await insert("member", ["household_id": householdId, "user_id": user.id, "role": "owner"])
        try await insert("shopping_list", ["id": listId, "household_id": householdId, "name": "Groceries"])

        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)
        XCTAssertTrue(app.staticTexts["tab.shopping.content"].waitForExistence(timeout: 20), app.debugDescription)
        let listRow = app.buttons["lists.row"].firstMatch
        XCTAssertTrue(listRow.waitForExistence(timeout: 20), app.debugDescription)
        listRow.tap()

        // AC1: Share is in List actions with nothing checked, and the switch starts off.
        let actions = app.buttons["list.actions"]
        XCTAssertTrue(actions.waitForExistence(timeout: 10), app.debugDescription)
        actions.tap()
        app.buttons["list.actions.share"].tap()
        let toggle = app.switches["share.switch"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(toggle.value as? String, "0")
        XCTAssertFalse(app.staticTexts["share.link"].exists)

        // AC2: on shows the link and the line about unchecked items; the server holds the token the link ends with.
        toggle.tap()
        let link = app.staticTexts["share.link"]
        XCTAssertTrue(link.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(app.staticTexts["share.unchecked"].exists)
        XCTAssertTrue(app.buttons["share.copy"].exists)
        let token = try await serverToken()
        let minted = try XCTUnwrap(token)
        XCTAssertTrue((link.value as? String ?? link.label).hasSuffix("/shared/\(minted)"), link.debugDescription)

        // AC3: Stop sharing returns the switch to off and clears the server token.
        app.buttons["share.stop"].tap()
        XCTAssertTrue(app.staticTexts["share.link"].waitForNonExistence(timeout: 15), app.debugDescription)
        XCTAssertEqual(app.switches["share.switch"].value as? String, "0")
        let cleared = try await serverToken()
        XCTAssertNil(cleared)
    }
}
