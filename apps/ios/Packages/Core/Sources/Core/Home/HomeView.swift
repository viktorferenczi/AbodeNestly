import Contract
import SwiftUI
import UI

/// DESIGN B4 Home tab. States: empty-activity, populated, all-modules-off. Footer links and the module cards are
/// callbacks so the app target owns navigation.
public struct HomeView: View {
    private let model: HomeModel
    private let onOpenModule: (AppTab) -> Void
    private let onInvite: () -> Void
    private let onOpenMembers: () -> Void
    private let onOpenSettings: () -> Void
    private let onOpenProfile: () -> Void
    private let onOpenRecentlyDeleted: () -> Void

    public init(
        model: HomeModel, onOpenModule: @escaping (AppTab) -> Void, onInvite: @escaping () -> Void,
        onOpenMembers: @escaping () -> Void, onOpenSettings: @escaping () -> Void, onOpenRecentlyDeleted: @escaping () -> Void,
        onOpenProfile: @escaping () -> Void
    ) {
        self.model = model
        self.onOpenModule = onOpenModule
        self.onInvite = onInvite
        self.onOpenMembers = onOpenMembers
        self.onOpenSettings = onOpenSettings
        self.onOpenRecentlyDeleted = onOpenRecentlyDeleted
        self.onOpenProfile = onOpenProfile
    }

    public var body: some View {
        List {
            if model.loaded {
                topSection
                activitySection
            }
            footerSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Tokens.bg)
        .refreshable { await model.refresh() }
        .task { await model.start() }
    }

    @ViewBuilder
    private var topSection: some View {
        if model.state == .allModulesOff {
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Every module is off. Turn one on in Settings.").foregroundStyle(Tokens.text)
                    Button("Settings", action: onOpenSettings)
                        .buttonStyle(.borderedProminent)
                        .tint(Tokens.accent)
                        .accessibilityIdentifier("home.allOff.settings")
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("home.allOff")
            }
        } else if let modules = model.modules {
            Section("Today") {
                // Titles and tap-through only: the counts come with the module stories.
                if modules.shopping { card(.shopping, "Shopping") }
                if modules.pantry { card(.pantry, "Pantry") }
                if modules.maintenance { card(.maintenance, "Maintenance") }
            }
        }
    }

    private func card(_ tab: AppTab, _ title: String) -> some View {
        Button { onOpenModule(tab) } label: {
            Text(title).foregroundStyle(Tokens.text).fontWeight(.semibold)
            .frame(maxWidth: .infinity, minHeight: Tokens.minTouchTarget, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(title)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("home.today.\(tab.rawValue)")
    }

    @ViewBuilder
    private var activitySection: some View {
        Section {
            if model.feed.isEmpty {
                if model.state == .empty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Things your household does show up here.").foregroundStyle(Tokens.text)
                        Button("Invite a member", action: onInvite)
                            .buttonStyle(.borderedProminent)
                            .tint(Tokens.accent)
                            .accessibilityIdentifier("home.empty.invite")
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("home.empty")
                }
            } else {
                ForEach(model.feed) { activity in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(activity.summary).foregroundStyle(Tokens.text)
                        Text(activity.createdAt, format: .dateTime.month(.abbreviated).day().hour().minute())
                            .font(.caption)
                            .foregroundStyle(Tokens.text2)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("home.feed.row")
                }
            }
        } header: {
            // Also the anchor the UI tests wait for to know Home is up.
            Text("Activity")
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("tab.home.content")
        }
        .accessibilityIdentifier("home.feed")
    }

    private var footerSection: some View {
        Section {
            footerLink("Members", id: "home.members", action: onOpenMembers)
            footerLink("Settings", id: "home.settings", action: onOpenSettings)
            footerLink("Recently deleted", id: "home.deleted", action: onOpenRecentlyDeleted)
            footerLink("Profile", id: "home.profile", action: onOpenProfile)
        }
    }

    private func footerLink(_ title: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(Tokens.accent)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(Tokens.text2)
            }
            .frame(minHeight: Tokens.minTouchTarget)
            .contentShape(Rectangle())
        }
        .accessibilityIdentifier(id)
    }
}
