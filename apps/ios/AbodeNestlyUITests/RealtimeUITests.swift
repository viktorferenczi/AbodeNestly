import XCTest

/// E2E-SHOP-2, the realtime part (TESTING §8, SHOP-09-ios) against the real stack: the signed-in member has the Groceries
/// list open with Milk unchecked; a second member (played through PostgREST with the service role, as the other member's
/// check-off would arrive) checks Milk off; the app shows Milk under Checked within 10 s with no tap or reload.
/// Local only (CI skips UI tests). Needs `supabase start` (Realtime on), `pnpm --filter @abodenestly/api dev` on :3000 and
/// `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class RealtimeUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var user: E2E.TestUser?
    private let householdId = UUID().uuidString.lowercased()
    private let listId = UUID().uuidString.lowercased()
    private let itemId = UUID().uuidString.lowercased()

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

    func testSHOP09_remoteCheckOffAppearsWithoutAction() async throws {
        let user = try XCTUnwrap(user)
        try await insert("household", ["id": householdId, "name": "Realtime Home", "created_by": user.id])
        try await insert("member", ["household_id": householdId, "user_id": user.id, "role": "owner"])
        try await insert("shopping_list", ["id": listId, "household_id": householdId, "name": "Groceries"])
        try await insert("list_item", ["id": itemId, "household_id": householdId, "list_id": listId, "name": "Milk"])

        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)
        XCTAssertTrue(app.staticTexts["tab.shopping.content"].waitForExistence(timeout: 20), app.debugDescription)
        let listRow = app.buttons["lists.row"].firstMatch
        XCTAssertTrue(listRow.waitForExistence(timeout: 20), app.debugDescription)
        listRow.tap()
        XCTAssertTrue(app.buttons["list.row.Milk.check"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.staticTexts["list.checked"].exists)
        // Let the channel join; the join itself also pulls, so a change made before it would be found anyway.
        try await Task.sleep(for: .seconds(3))

        // AC2: the other member checks Milk off.
        var request = E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/list_item?id=eq.\(itemId)", method: "PATCH")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "checked": true, "checked_at": ISO8601DateFormatter().string(from: Date()),
        ])
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 204, "the other member's check-off")

        // AC1/AC2: no tap, no reload; Milk moves under Checked.
        XCTAssertTrue(app.staticTexts["list.checked"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["list.row.Milk.check"].exists)
    }
}
