import Testing
@testable import Shopping

@Suite("SHOP-15 assistant replies")
struct AddToShoppingListIntentTests {
    @Test("SHOP-15: each result has its spoken reply", arguments: [
        (AssistantResult.added(name: "Milk", list: "Groceries"), "Added Milk to Groceries."),
        (AssistantResult.noItem, "I didn't catch an item. Nothing was added."),
        (AssistantResult.shoppingOff, "Shopping is turned off for this household. Nothing was added."),
        (AssistantResult.noHousehold, "Open AbodeNestly and sign in first."),
        (AssistantResult.noList, "There's no shopping list to add to."),
        (AssistantResult.failed, "Couldn't add that. Try again in the app."),
    ])
    func reply(result: AssistantResult, text: String) {
        #expect(result.reply == text)
    }
}
