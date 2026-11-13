import XCTest

/// E2E-CORE-1, invite half: user A creates a household and reads its invite code; user B signs in on a
/// fresh launch, joins with that code and lands on Home. Needs `supabase start`,
/// `pnpm --filter @abodenestly/api dev` on :3000 and `TEST_RUNNER_SUPABASE_SERVICE_ROLE_KEY`.
@MainActor
final class InviteSheetUITests: XCTestCase {
    private var serviceRoleKey = ""
    private var owner: E2E.TestUser?
    private var joiner: E2E.TestUser?

    override func setUp() async throws {
        continueAfterFailure = false
        serviceRoleKey = try E2E.serviceRoleKey()
        owner = try await E2E.createUser(key: serviceRoleKey)
        joiner = try await E2E.createUser(key: serviceRoleKey)
    }

    override func tearDown() async throws {
        if let owner {
            // Households first: they reference the owner through `created_by`.
            let households = try? await E2E.rows(
                key: serviceRoleKey, table: "household", query: "created_by=eq.\(owner.id)&select=id"
            )
            for id in households?.compactMap({ $0["id"] as? String }) ?? [] {
                _ = try? await URLSession.shared.data(
                    for: E2E.adminRequest(key: serviceRoleKey, path: "rest/v1/household?id=eq.\(id)", method: "DELETE")
                )
            }
            await E2E.deleteUser(key: serviceRoleKey, id: owner.id)
        }
        if let joiner { await E2E.deleteUser(key: serviceRoleKey, id: joiner.id) }
    }

    func testCORE03_inviteCodeLetsASecondUserJoin() async throws {
        let owner = try XCTUnwrap(owner), joiner = try XCTUnwrap(joiner)

        // User A creates a household, then opens Home → Members → Invite.
        let appA = XCUIApplication()
        try await E2E.signIn(appA, as: owner)
        XCTAssertTrue(appA.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        let name = appA.textFields["welcome.name"]
        name.tap()
        name.typeText("Test Home")
        appA.buttons["welcome.create"].tap()
        XCTAssertTrue(appA.staticTexts["tab.home.content"].waitForExistence(timeout: 15))
        appA.buttons["home.members"].tap()
        appA.buttons["members.invite"].tap()

        // `textSelection` can change the element type, so match the identifier whatever it is.
        let codeText = appA.descendants(matching: .any)["invite.code"]
        XCTAssertTrue(codeText.waitForExistence(timeout: 15), appA.debugDescription)
        let code = codeText.label.replacingOccurrences(of: "Invite code ", with: "")
        XCTAssertFalse(code.isEmpty)
        XCTAssertTrue(appA.buttons["invite.share"].isEnabled)
        appA.terminate()

        // User B starts signed out, enters the code on Welcome and lands on Home.
        let appB = XCUIApplication()
        try await E2E.signIn(appB, as: joiner)
        XCTAssertTrue(appB.staticTexts["welcome.title"].waitForExistence(timeout: 15))
        let field = appB.textFields["welcome.code"]
        field.tap()
        field.typeText(code)
        appB.buttons["welcome.join"].tap()
        XCTAssertTrue(appB.staticTexts["tab.home.content"].waitForExistence(timeout: 15))

        // Both users are active members of the household.
        let households = try await E2E.rows(
            key: serviceRoleKey, table: "household", query: "created_by=eq.\(owner.id)&select=id"
        )
        let householdId = try XCTUnwrap(households.first?["id"] as? String)
        let members = try await E2E.rows(
            key: serviceRoleKey, table: "member",
            query: "household_id=eq.\(householdId)&left_at=is.null&select=user_id,role"
        )
        XCTAssertEqual(Set(members.compactMap { $0["user_id"] as? String }), [owner.id, joiner.id])
        let joinerRow = members.first { $0["user_id"] as? String == joiner.id }
        XCTAssertEqual(joinerRow?["role"] as? String, "member")
    }
}
