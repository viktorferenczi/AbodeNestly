import Contract
import Core
import Foundation
import Shopping
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

/// What the Realtime subscription depends on; a change restarts it.
private struct RealtimeKey: Equatable {
    let householdId: UUID?
    let modules: HomeModules?
    let awake: Bool
}

private struct SignedInView: View {
    let session: SessionModel
    let environment: AppEnvironment
    @State private var welcome: WelcomeModel
    /// Lives as long as the signed-in session, not the Profile screen, so queued edits drain on reconnect
    /// and on foreground wherever the person is (interim stand-in for SHOP-08's `SyncEngine.kick()`).
    @State private var profile: ProfileModel
    /// What the TopBar chip and the sync sheet (DESIGN B12) show; lives with the signed-in session.
    @State private var syncStatus: SyncStatusModel
    @State private var showSync = false
    @State private var showSwitcher = false
    /// "Create or join another" was tapped: present Welcome once the switcher sheet is gone (one sheet at a time).
    @State private var wantsWelcome = false
    @State private var showWelcome = false
    @Environment(\.scenePhase) private var scenePhase

    init(session: SessionModel, environment: AppEnvironment) {
        self.session = session
        self.environment = environment
        let network = environment.network
        _syncStatus = State(initialValue: SyncStatusModel(
            engine: environment.sync, database: environment.database, network: network
        ))
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
                onTitleTap: showsSwitcher ? { showSwitcher = true } : nil,
                // The chip is hidden when idle; it sits in the global bar because the sheet also shows core writes.
                status: syncStatus.chip, onStatusTap: { showSync = true },
                // Search reads the open household's local database, so it needs one (not Welcome or the load error).
                actions: showsSwitcher && searchModules != nil ? [TopBarAction(
                    systemImage: "magnifyingglass", label: "Search", identifier: "topbar.search",
                    action: { session.searchRequested = true }
                )] : []
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
        .sheet(isPresented: $showSync) {
            SyncSheetView(model: syncStatus, onClose: { showSync = false })
                .presentationDetents([.medium, .large])
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
        // The same triggers as the profile drain (foreground, reconnect), plus entering or switching a household.
        .onChange(of: environment.network.isOnline) { _, online in
            profile.connectivityChanged(online)
            if online { kickSync() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                profile.kickDrain()
                kickSync()
            } else if phase == .background {
                environment.backgroundSync.scheduleNext()
            }
        }
        .onChange(of: session.currentHouseholdId, initial: true) { _, _ in kickSync() }
        .task(id: session.currentHouseholdId) { await syncStatus.run(householdId: session.currentHouseholdId) }
        // SHOP-09: Realtime changes kick the sync engine. Restarts (and so unsubscribes first) when the household or its
        // modules change, and is dropped while the app is in the background.
        .task(id: RealtimeKey(householdId: session.currentHouseholdId, modules: searchModules, awake: scenePhase != .background)) {
            guard scenePhase != .background, let householdId = session.currentHouseholdId, let modules = searchModules else { return }
            await HouseholdRealtime.run(realtime: environment.realtime, householdId: householdId, modules: modules, sync: environment.sync)
        }
        // SPEC §6.4: every 5 minutes while the app is in the foreground. The task restarts with the scene phase.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(300))
                if Task.isCancelled { break }
                kickSync()
            }
        }
    }

    private func kickSync() {
        let sync = environment.sync
        let env = environment
        // SHOP-10: queued barcode lookups are drained on household open, on reconnect and after each sync run.
        let household = session.household?.moduleShopping == true ? session.currentHouseholdId : nil
        Task {
            await sync.kick()
            if let household { await drainBarcodeLookups(environment: env, householdId: household) }
        }
    }

    /// Flags search filters by, from the cache when the household is unknown (offline `.home`); `nil` hides the magnifier.
    private var searchModules: HomeModules? {
        guard let userId = session.userId else { return nil }
        return Search.modules(
            householdId: session.currentHouseholdId ?? environment.lastHousehold.read(), household: session.household,
            cached: HouseholdsCache().read(userId: userId)
        )
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

    fileprivate enum HomeRoute: Hashable { case settings, members, profile, search, recentlyDeleted }

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
        // The TopBar magnifier (CORE-09), from any tab.
        .onChange(of: session.searchRequested, initial: true) { _, requested in
            guard requested else { return }
            session.searchRequested = false
            session.selectedTab = .home
            homePath = [.search]
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: AppTab) -> some View {
        if tab == .home {
            // The household may be unknown offline at launch; fall back to the last one used.
            let householdId = household?.id ?? environment.lastHousehold.read()
            NavigationStack(path: $homePath) {
                if let householdId {
                    HomeScreen(
                        householdId: householdId, household: household, environment: environment,
                        onOpenModule: { session.selectedTab = $0 },
                        onOpenRoute: { homePath.append($0) }
                    )
                    .navigationDestination(for: HomeRoute.self) { route in
                        switch route {
                        case .settings:
                            HouseholdSettingsScreen(householdId: householdId, session: session, environment: environment)
                        case .members:
                            MembersScreen(householdId: householdId, session: session, environment: environment)
                        case .profile:
                            ProfileView(
                                model: profile, isOnline: environment.network.isOnline,
                                notifications: {
                                    // Without a signed-in user (not reachable from here) there is nothing to write for.
                                    if let userId = session.userId {
                                        NotificationPrefsScreen(
                                            householdId: householdId, userId: userId, environment: environment,
                                            modules: Search.modules(
                                                householdId: householdId, household: household,
                                                cached: HouseholdsCache().read(userId: userId)
                                            )
                                        )
                                    }
                                }
                            )
                        case .recentlyDeleted:
                            RecentlyDeletedScreen(
                                householdId: householdId, environment: environment,
                                modules: Search.modules(
                                    householdId: householdId, household: household,
                                    cached: session.userId.map { HouseholdsCache().read(userId: $0) } ?? []
                                )
                            )
                        case .search:
                            SearchScreen(
                                householdId: householdId, environment: environment,
                                modules: Search.modules(
                                    householdId: householdId, household: household,
                                    cached: session.userId.map { HouseholdsCache().read(userId: $0) } ?? []
                                ),
                                // The entity screens come with the module stories: switch to the module's tab.
                                onOpen: { target in
                                    homePath = []
                                    session.selectedTab = target.module.tab
                                }
                            )
                        }
                    }
                } else {
                    // No household known yet (offline before any was read): nothing to show a feed for.
                    Text(title(tab))
                        .foregroundStyle(Tokens.text)
                        .accessibilityIdentifier("tab.home.content")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Tokens.bg)
                }
            }
        } else if tab == .shopping, let householdId = household?.id ?? session.currentHouseholdId {
            ShoppingScreen(householdId: householdId, userId: session.userId, environment: environment)
        } else {
            Text(title(tab))
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("tab.\(tab.rawValue).content")
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

/// The Shopping tab: the lists overview (SHOP-01) on its own stack. Owns the `ListsModel`, so a re-render of the tabs
/// never resets it; the tabs are rebuilt (`.id`) for a different household.
private struct ShoppingScreen: View {
    @State private var model: ListsModel
    @State private var path: [ShoppingRoute] = []
    @State private var stores: StoresModel
    let householdId: UUID
    let userId: UUID?
    let environment: AppEnvironment

    struct ListRoute: Hashable {
        let id: UUID
        let name: String
    }

    enum ShoppingRoute: Hashable {
        case list(ListRoute)
        case stores
    }

    init(householdId: UUID, userId: UUID?, environment: AppEnvironment) {
        self.householdId = householdId
        self.userId = userId
        self.environment = environment
        let sync = environment.sync
        _model = State(initialValue: ListsModel(
            householdId: householdId, database: environment.database, kick: { await sync.kick() }
        ))
        _stores = State(initialValue: StoresModel(
            householdId: householdId, database: environment.database, kick: { await sync.kick() }
        ))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ListsView(
                model: model, isOnline: environment.network.isOnline, onOpenStores: { path.append(.stores) },
                onOpen: { row in path.append(.list(ListRoute(id: row.id, name: row.name))) }
            )
            #if DEBUG
            // E2E reads the text by identifier; it takes no room, so the heading stays the top of the screen.
            .overlay(alignment: .topLeading) {
                LocalListsDebugView(householdId: householdId, database: environment.database)
                    .frame(width: 1, height: 1)
                    .clipped()
            }
            #endif
            .navigationDestination(for: ShoppingRoute.self) { route in
                switch route {
                case .list(let route):
                    ListDetailScreen(
                        route: route, householdId: householdId, userId: userId, environment: environment,
                        onManageStores: { path.append(.stores) }
                    )
                case .stores:
                    StoresView(model: stores, isOnline: environment.network.isOnline)
                }
            }
        }
    }
}

/// A list's detail (SHOP-02). Owns its `ListDetailModel`, so a re-render of the stack never resets what was typed.
private struct ListDetailScreen: View {
    @State private var model: ListDetailModel
    let title: String
    let environment: AppEnvironment
    let onManageStores: () -> Void

    init(
        route: ShoppingScreen.ListRoute, householdId: UUID, userId: UUID?, environment: AppEnvironment,
        onManageStores: @escaping () -> Void
    ) {
        self.onManageStores = onManageStores
        title = route.name
        self.environment = environment
        let sync = environment.sync
        _model = State(initialValue: ListDetailModel(
            householdId: householdId, listId: route.id, userId: userId, database: environment.database,
            // A sync run that ended also resolves the lookups queued while offline (SHOP-10).
            kick: {
                await sync.kick()
                await drainBarcodeLookups(environment: environment, householdId: householdId)
            }
        ))
    }

    var body: some View {
        let api = environment.api
        let network = environment.network
        let webBase: URL = environment.config.webBaseURL
        ListDetailView(
            model: model, title: title, isOnline: environment.network.isOnline, onManageStores: onManageStores,
            makeScanner: { [model] in
                model.makeScanner(
                    device: AVScannerDevice(), lookup: { await api.product(barcode: $0) }, isOnline: { network.isOnline }
                )
            },
            makeShare: { [model] in
                model.makeShare(
                    webBase: webBase, share: { await shareResult(api, listId: $0) }, stop: { await stopResult(api, listId: $0) },
                    isOnline: { network.isOnline }
                )
            },
            makeVoice: { [model] in
                model.makeVoice(recognizer: AppleSpeechRecognizer(), isOnline: { network.isOnline })
            }
        )
    }
}

/// SHOP-13: the API calls of the share sheet, with `ApiFailure` reduced to what the sheet tells apart.
private func shareResult(_ api: any ApiService, listId: UUID) async -> Result<String, ShareFailure> {
    switch await api.shareList(listId: listId) {
    case .success(let url): return .success(url)
    case .failure(let failure): return .failure(failure == .network ? .network : .failed)
    }
}

private func stopResult(_ api: any ApiService, listId: UUID) async -> Result<Void, ShareFailure> {
    switch await api.stopSharing(listId: listId) {
    case .success: return .success(())
    case .failure(let failure): return .failure(failure == .network ? .network : .failed)
    }
}

/// Resolves the barcode lookups queued while offline for the open household (SHOP-10). A rename it makes is pushed by
/// the kick it asks for, which does not drain again.
private func drainBarcodeLookups(environment: AppEnvironment, householdId: UUID) async {
    let api = environment.api
    let network = environment.network
    let sync = environment.sync
    try? await BarcodeLookupDrainer.shared.drain(BarcodeLookupDrainer.Request(
        database: environment.database, householdId: householdId, lookup: { await api.product(barcode: $0) },
        isOnline: { network.isOnline }, onRenamed: { await sync.kick() }
    ))
}

/// Owns the `HomeModel` for the Home tab, so a re-render of the tabs never resets it. The tabs are rebuilt (`.id`)
/// for a different household, which starts a fresh model on that household's database.
private struct HomeScreen: View {
    @State private var model: HomeModel
    /// Settings can flip a module while Home is up; the model follows.
    let household: LandingHousehold?
    let onOpenModule: (AppTab) -> Void
    let onOpenRoute: (MainTabs.HomeRoute) -> Void

    init(
        householdId: UUID, household: LandingHousehold?, environment: AppEnvironment,
        onOpenModule: @escaping (AppTab) -> Void, onOpenRoute: @escaping (MainTabs.HomeRoute) -> Void
    ) {
        self.household = household
        self.onOpenModule = onOpenModule
        self.onOpenRoute = onOpenRoute
        let sync = environment.sync
        _model = State(initialValue: HomeModel(
            householdId: householdId, modules: household.map(HomeModules.init),
            database: environment.database, kick: { await sync.kick() }
        ))
    }

    var body: some View {
        HomeView(
            model: model, onOpenModule: onOpenModule,
            onInvite: { onOpenRoute(.members) }, onOpenMembers: { onOpenRoute(.members) },
            onOpenSettings: { onOpenRoute(.settings) },
            onOpenRecentlyDeleted: { onOpenRoute(.recentlyDeleted) }, onOpenProfile: { onOpenRoute(.profile) }
        )
        .onChange(of: household) { _, household in model.modules = household.map(HomeModules.init) }
        // The household file is open by now (the session opens it before publishing the destination), unlike
        // the first kick on `SignedInView`, which can run before `open` and has nothing to pull into.
        .task { await model.refresh() }
    }
}

/// Owns the `SearchModel` for the pushed screen, so a re-render of the tabs never resets it.
private struct SearchScreen: View {
    @State private var model: SearchModel
    /// Settings can flip a module while Search is up; the model follows.
    let modules: HomeModules?
    let onOpen: (SearchTarget) -> Void

    init(householdId: UUID, environment: AppEnvironment, modules: HomeModules?, onOpen: @escaping (SearchTarget) -> Void) {
        self.modules = modules
        self.onOpen = onOpen
        _model = State(initialValue: SearchModel(
            householdId: householdId, modules: modules,
            database: environment.database, store: RecentSearchStore(householdId: householdId)
        ))
    }

    var body: some View {
        SearchView(model: model, onOpen: onOpen)
            .onChange(of: modules) { _, modules in model.modules = modules }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// Owns the `RecentlyDeletedModel` for the pushed screen, so a re-render of the tabs never resets it.
private struct RecentlyDeletedScreen: View {
    @State private var model: RecentlyDeletedModel
    /// Settings can flip a module while this is up; the model follows.
    let modules: HomeModules?

    init(householdId: UUID, environment: AppEnvironment, modules: HomeModules?) {
        self.modules = modules
        let sync = environment.sync
        _model = State(initialValue: RecentlyDeletedModel(
            householdId: householdId, modules: modules, database: environment.database, kick: { await sync.kick() }
        ))
    }

    var body: some View {
        RecentlyDeletedView(model: model)
            .onChange(of: modules) { _, modules in model.modules = modules }
            .navigationTitle("Recently deleted")
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// Owns the `NotificationPrefsModel` for the pushed screen, so a re-render of the tabs never resets it.
private struct NotificationPrefsScreen: View {
    @State private var model: NotificationPrefsModel
    /// Settings can flip a module while this is up; the model follows.
    let modules: HomeModules?

    init(householdId: UUID, userId: UUID, environment: AppEnvironment, modules: HomeModules?) {
        self.modules = modules
        let sync = environment.sync
        _model = State(initialValue: NotificationPrefsModel(
            householdId: householdId, userId: userId, modules: modules, database: environment.database,
            kick: { await sync.kick() }
        ))
    }

    var body: some View {
        NotificationPrefsView(model: model, openURL: { UIApplication.shared.open($0) })
            .onChange(of: modules) { _, modules in model.modules = modules }
            .navigationTitle("Notifications")
            .navigationBarTitleDisplayMode(.inline)
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
