import XCTest

/// E2E-SHOP-3, the create and assign part (TESTING §8, SHOP-05-ios) against the real API: Shopping → Stores shows the
/// empty state; add Test Market and it shows; open Groceries, tap the store chip, pick Test Market, Save; the chip reads
/// Test Market, so does the row on Shopping, and the server list has the store's id. SHOP-06-ios adds the re-sort.
/// Local only (CI skips UI tests). Needs `supabase start`, `pnpm --filter @abodenestly/api dev` on :3000 and
/// `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class StoresUITests: XCTestCase {
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

    func testSHOP05_createAndAssignStore() async throws {
        let user = try XCTUnwrap(user)
        try await insert("household", ["id": householdId, "name": "Store Home", "created_by": user.id])
        try await insert("member", ["household_id": householdId, "user_id": user.id, "role": "owner"])
        try await insert("shopping_list", ["id": listId, "household_id": householdId, "name": "Groceries"])

        let app = XCUIApplication()
        try await E2E.signIn(app, as: user)
        XCTAssertTrue(app.staticTexts["tab.shopping.content"].waitForExistence(timeout: 20), app.debugDescription)

        // AC1: no stores yet.
        app.buttons["lists.stores"].tap()
        XCTAssertTrue(app.otherElements["stores.empty"].waitForExistence(timeout: 10), app.debugDescription)

        // AC2: add Test Market.
        app.buttons["stores.empty.new"].tap()
        let name = app.textFields["storesheet.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 10), app.debugDescription)
        name.tap()
        name.typeText("Test Market")
        app.buttons["storesheet.save"].tap()
        XCTAssertTrue(app.staticTexts["Test Market"].waitForExistence(timeout: 10), app.debugDescription)

        // AC3: pick it on the list.
        app.navigationBars.buttons.firstMatch.tap()
        let listRow = app.buttons["lists.row"].firstMatch
        XCTAssertTrue(listRow.waitForExistence(timeout: 10), app.debugDescription)
        listRow.tap()
        let chip = app.buttons["list.store"]
        XCTAssertTrue(chip.waitForExistence(timeout: 10), app.debugDescription)
        chip.tap()
        let choice = app.buttons["storepicker.row"].firstMatch
        XCTAssertTrue(choice.waitForExistence(timeout: 10), app.debugDescription)
        choice.tap()
        app.buttons["storepicker.save"].tap()
        XCTAssertTrue(app.buttons["list.store"].staticTexts["Test Market"].waitForExistence(timeout: 10), app.debugDescription)

        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["lists.row.store"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(app.staticTexts["lists.row.store"].label, "Test Market")

        // After sync the server list points at the store.
        var linked = false
        for _ in 0..<20 where !linked {
            let store = try await E2E.rows(key: serviceRoleKey, table: "store", query: "household_id=eq.\(householdId)&name=eq.Test%20Market&select=id").first
            let list = try await E2E.rows(key: serviceRoleKey, table: "shopping_list", query: "id=eq.\(listId)&select=store_id").first
            linked = store?["id"] as? String != nil && list?["store_id"] as? String == store?["id"] as? String
            if !linked { try await Task.sleep(for: .seconds(1)) }
        }
        XCTAssertTrue(linked, "the list's store_id reached the server")
    }
}
