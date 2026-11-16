import Contract
import Core
import Foundation
import SwiftUI
import Sync
import UI

struct RootView: View {
    @Environment(\.appEnvironment) private var environment

    var body: some View {
        if let environment {
            RootContent(environment: environment)
        }
    }
}

private struct RootContent: View {
    let environment: AppEnvironment
    @State private var session: SessionModel
    @State private var signIn: SignInModel

    init(environment: AppEnvironment) {
        self.environment = environment
        let network = environment.network
        _session = State(initialValue: SessionModel(
            auth: environment.auth,
            lastHousehold: environment.lastHousehold,
            database: environment.database,
            api: environment.api,
            isOnline: { network.isOnline }
        ))
        _signIn = State(initialValue: SignInModel(
            auth: environment.auth,
            redirectTo: environment.config.authCallbackURL
        ))
    }

    var body: some View {
        Group {
            switch session.status {
            case .loading:
                Tokens.bg.ignoresSafeArea()
            case .signedOut:
                SignInView(model: signIn, isOnline: environment.network.isOnline)
            case .signedIn:
                SignedInView(session: session, environment: environment)
            }
        }
        .task {
            #if DEBUG
            // UI tests start from a known signed-out state; the launch argument is absent on relaunch (AC6).
            if CommandLine.arguments.contains("-reset-session") { await environment.auth.signOut() }
            #endif
            await session.start()
        }
    }
}

private struct SignedInView: View {
    let session: SessionModel
    let environment: AppEnvironment
    @State private var welcome: WelcomeModel
    /// Lives as long as the signed-in session, not the Profile screen, so queued edits drain on reconnect
    /// and on foreground wherever the person is (interim stand-in for SHOP-08's `SyncEngine.kick()`).
    @State private var profile: ProfileModel
    @State private var showSwitcher = false
    /// "Create or join another" was tapped: present Welcome once the switcher sheet is gone (one sheet at a time).
    @State private var wantsWelcome = false
    @State private var showWelcome = false
    @Environment(\.scenePhase) private var scenePhase

    init(session: SessionModel, environment: AppEnvironment) {
        self.session = session
        self.environment = environment
        let network = environment.network
        _profile = State(initialValue: ProfileModel(
            // Only built while signed in; the nil-UUID fallback never matches a row.
            userId: session.userId ?? UUID(uuidString: "00000000-0000-0000-0000-000000000000")!,
            email: session.email, session: session, auth: environment.auth,
            isOnline: { network.isOnline }
        ))
        _welcome = State(initialValue: WelcomeModel(api: environment.api) { [session] outcome in
            switch outcome {
            case .created(let household):
                if let userId = session.userId {
                    HouseholdsCache().upsert(HouseholdWithRole(household: household, role: .owner), userId: userId)
                }
                session.enter(LandingHousehold(
                    id: household.id, moduleShopping: household.moduleShopping,
                    modulePantry: household.modulePantry, moduleMaintenance: household.moduleMaintenance
                ))
            case .joined(let householdId):
                session.enterJoined(householdId: householdId)
            }
        })
    }

    var body: some View {
        VStack(spacing: 0) {
            TopBar(
                title: session.householdName ?? "AbodeNestly", trailing: session.profile?.displayName,
                // No household yet (Welcome, loading): nothing to switch from.
                onTitleTap: showsSwitcher ? { showSwitcher = true } : nil
            )
            content
        }
        .background(Tokens.bg)
        .sheet(isPresented: $showSwitcher, onDismiss: {
            if wantsWelcome {
                wantsWelcome = false
                showWelcome = true
            }
        }) {
            if let userId = session.userId {
                SwitcherSheet(
                    userId: userId, session: session, environment: environment,
                    onDismiss: { showSwitcher = false },
                    onCreateOrJoin: {
                        wantsWelcome = true
                        showSwitcher = false
                    }
                )
            }
        }
        .sheet(isPresented: $showWelcome) {
            NavigationStack {
                WelcomeView(
                    model: welcome, displayName: session.profile?.displayName,
                    isOnline: environment.network.isOnline,
                    onSignOut: { Task { await session.signOut() } }
                )
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showWelcome = false }.accessibilityIdentifier("switcher.welcome.cancel")
                    }
                }
            }
        }
        // Created or joined: the household changed under the sheet.
        .onChange(of: session.household?.id) { _, _ in showWelcome = false }
        .task { await profile.start() }
        .onChange(of: environment.network.isOnline) { _, online in profile.connectivityChanged(online) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { profile.kickDrain() }
        }
    }

    private var showsSwitcher: Bool {
        if case .welcome? = session.destination { return false }
        return session.destination != nil && !session.loadFailed
    }

    @ViewBuilder
    private var content: some View {
        if session.loadFailed {
            VStack(spacing: 16) {
                Text("Couldn't load your households.")
                    .foregroundStyle(Tokens.text)
                Button("Try again") { session.retry() }
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("households.error")
        } else {
            switch session.destination {
            case nil:
                Color.clear
            case .welcome:
                WelcomeView(
                    model: welcome, displayName: session.profile?.displayName,
                    isOnline: environment.network.isOnline,
                    onSignOut: { Task { await session.signOut() } }
                )
            case .household(let id, _):
                // A new id rebuilds the tabs so nothing pushed (Settings, Members) keeps the old household's model.
                MainTabs(session: session, profile: profile, household: session.household, environment: environment)
                    .id(id)
            case .home:
                MainTabs(session: session, profile: profile, household: nil, environment: environment)
            }
        }
    }
}

/// Bottom tabs filtered by the household's module flags (DESIGN A6). Content arrives with the module stories.
private struct MainTabs: View {
    let session: SessionModel
    let profile: ProfileModel
    let household: LandingHousehold?
    let environment: AppEnvironment
    @State private var homePath: [HomeRoute] = []

    private enum HomeRoute: Hashable { case settings }

    init(session: SessionModel, profile: ProfileModel, household: LandingHousehold?, environment: AppEnvironment) {
        self.session = session
        self.profile = profile
        self.household = household
        self.environment = environment
    }

    /// The selection lives on the session so it survives a household switch (CORE-07 AC2).
    private var selection: Binding<AppTab> {
        Binding(get: { session.selectedTab }, set: { session.selectedTab = $0 })
    }

    private var tabs: [AppTab] {
        var tabs: [AppTab] = []
        if household?.moduleShopping == true { tabs.append(.shopping) }
        if household?.modulePantry == true { tabs.append(.pantry) }
        if household?.moduleMaintenance == true { tabs.append(.maintenance) }
        return tabs + [.home]
    }

    var body: some View {
        TabView(selection: selection) {
            ForEach(tabs, id: \.self) { tab in
                tabContent(tab)
                    .tabItem { Label(title(tab), systemImage: icon(tab)) }
                    .tag(tab)
            }
        }
        .tint(Tokens.accent)
        // A module switched off while its tab is selected: fall back to Home (Settings lives there, so defensive only).
        .onChange(of: tabs) { _, tabs in
            if !tabs.contains(session.selectedTab) { session.selectedTab = .home }
        }
        // Long-press in the switcher: Home → Settings (B5). `initial` covers tabs rebuilt by the household change.
        .onChange(of: session.settingsRequested, initial: true) { _, requested in
            guard requested else { return }
            session.settingsRequested = false
            session.selectedTab = .home
            homePath = [.settings]
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: AppTab) -> some View {
        if tab == .home {
            // The household may be unknown offline at launch; fall back to the last one used.
            let householdId = household?.id ?? environment.lastHousehold.read()
            NavigationStack(path: $homePath) {
                VStack(spacing: 16) {
                    Text(title(tab))
                        .foregroundStyle(Tokens.text)
                        .accessibilityIdentifier("tab.home.content")
                    if let householdId {
                        NavigationLink("Members") {
                            MembersScreen(householdId: householdId, session: session, environment: environment)
                        }
                        .accessibilityIdentifier("home.members")
                        NavigationLink("Profile") {
                            ProfileView(model: profile, isOnline: environment.network.isOnline)
                        }
                        .accessibilityIdentifier("home.profile")
                        NavigationLink("Settings") {
                            HouseholdSettingsScreen(householdId: householdId, session: session, environment: environment)
                        }
                        .accessibilityIdentifier("home.settings")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Tokens.bg)
                .navigationDestination(for: HomeRoute.self) { _ in
                    if let householdId {
                        HouseholdSettingsScreen(householdId: householdId, session: session, environment: environment)
                    }
                }
            }
        } else {
            VStack(spacing: 12) {
                Text(title(tab))
                    .foregroundStyle(Tokens.text)
                    .accessibilityIdentifier("tab.\(tab.rawValue).content")
                #if DEBUG
                if tab == .shopping, let householdId = household?.id ?? session.currentHouseholdId {
                    LocalListsDebugView(householdId: householdId, database: environment.database)
                }
                #endif
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func title(_ tab: AppTab) -> String {
        switch tab {
        case .shopping: "Shopping"
        case .pantry: "Pantry"
        case .maintenance: "Maintenance"
        case .home: "Home"
        }
    }

    private func icon(_ tab: AppTab) -> String {
        switch tab {
        case .shopping: "cart"
        case .pantry: "refrigerator"
        case .maintenance: "wrench.and.screwdriver"
        case .home: "house"
        }
    }
}

/// Owns the `MembersModel` for the pushed screen, so a re-render of the tabs never resets it.
private struct MembersScreen: View {
    let environment: AppEnvironment
    @State private var model: MembersModel

    init(householdId: UUID, session: SessionModel, environment: AppEnvironment) {
        self.environment = environment
        let network = environment.network
        _model = State(initialValue: MembersModel(
            householdId: householdId,
            // Members is only reachable signed in; the nil-UUID fallback never matches a row.
            meId: session.userId ?? UUID(uuidString: "00000000-0000-0000-0000-000000000000")!,
            api: environment.api,
            cache: MembersCache(),
            isOnline: { network.isOnline },
            onLeft: { [session] in session.leftHousehold() }
        ))
    }

    var body: some View {
        MembersView(
            model: model, api: environment.api, isOnline: environment.network.isOnline,
            webBaseURL: environment.config.webBaseURL
        )
    }
}

/// Owns the `HouseholdSettingsModel` for the pushed screen, so a re-render of the tabs never resets it.
private struct HouseholdSettingsScreen: View {
    let environment: AppEnvironment
    @State private var model: HouseholdSettingsModel

    init(householdId: UUID, session: SessionModel, environment: AppEnvironment) {
        self.environment = environment
        let network = environment.network
        _model = State(initialValue: HouseholdSettingsModel(
            householdId: householdId,
            // Settings is only reachable signed in; the nil-UUID fallback never matches a row.
            userId: session.userId ?? UUID(uuidString: "00000000-0000-0000-0000-000000000000")!,
            api: environment.api,
            cache: HouseholdCache(),
            isOnline: { network.isOnline },
            onHouseholdChanged: { [session] in session.updateHousehold($0) },
            onLeft: { [session] in session.leftHousehold() }
        ))
    }

    var body: some View {
        HouseholdSettingsView(model: model, isOnline: environment.network.isOnline)
    }
}

/// Owns the `SwitcherModel` for one presentation of the B3 sheet.
private struct SwitcherSheet: View {
    @State private var model: SwitcherModel
    let onDismiss: () -> Void
    let onCreateOrJoin: () -> Void

    init(
        userId: UUID, session: SessionModel, environment: AppEnvironment,
        onDismiss: @escaping () -> Void, onCreateOrJoin: @escaping () -> Void
    ) {
        self.onDismiss = onDismiss
        self.onCreateOrJoin = onCreateOrJoin
        let network = environment.network
        let currentId = session.currentHouseholdId
        _model = State(initialValue: SwitcherModel(
            userId: userId, currentId: currentId,
            current: currentId.flatMap { HouseholdCache().read(userId: userId, householdId: $0) },
            api: environment.api, cache: HouseholdsCache(), database: environment.database,
            isOnline: { network.isOnline },
            perform: { [session] id, openSettings in try await session.switchTo(id, openSettings: openSettings) }
        ))
    }

    var body: some View {
        SwitcherView(model: model, onDismiss: onDismiss, onCreateOrJoin: onCreateOrJoin)
    }
}

#if DEBUG
/// E2E only: the local `shopping_list` rows of the open household, so isolation between households is visible.
private struct LocalListsDebugView: View {
    let householdId: UUID
    let database: HouseholdDatabase
    @State private var names: [String] = []

    var body: some View {
        Text(names.joined(separator: ", "))
            .foregroundStyle(Tokens.text2)
            .accessibilityIdentifier("shopping.local-lists")
            .task(id: householdId) {
                // The switch opens the database before the destination changes, but a launch may still be opening it.
                for _ in 0..<20 {
                    if let lists = try? await database.lists(), await database.current == householdId {
                        names = lists.map(\.name)
                        return
                    }
                    try? await Task.sleep(for: .milliseconds(100))
                }
            }
    }
}
#endif
