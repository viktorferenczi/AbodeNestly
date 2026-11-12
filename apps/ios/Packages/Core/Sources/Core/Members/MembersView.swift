import SwiftUI
import UI

/// DESIGN B6. Member rows (avatar, role, overflow menu) arrive with CORE-04 and render above the button.
public struct MembersView: View {
    private let householdId: UUID
    private let api: any ApiService
    private let isOnline: Bool
    private let webBaseURL: URL
    /// Created on tap and kept for the sheet's life, so a parent re-render never resets its state.
    @State private var invite: InviteSheetModel?

    public init(householdId: UUID, api: any ApiService, isOnline: Bool, webBaseURL: URL) {
        self.householdId = householdId
        self.api = api
        self.isOnline = isOnline
        self.webBaseURL = webBaseURL
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Members")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Tokens.text)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Invite") { invite = InviteSheetModel(householdId: householdId, api: api) }
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .frame(minHeight: Tokens.minTouchTarget)
                    .accessibilityIdentifier("members.invite")
            }
            Spacer(minLength: 0)
        }
        .padding(Tokens.screenPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Tokens.bg)
        .navigationTitle("Members")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $invite) { model in
            InviteSheetView(model: model, isOnline: isOnline, webBaseURL: webBaseURL)
        }
        .accessibilityIdentifier("members.screen")
    }
}
