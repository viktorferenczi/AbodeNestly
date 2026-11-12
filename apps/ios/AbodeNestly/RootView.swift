import Contract
import Core
import SwiftUI
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

    init(session: SessionModel, environment: AppEnvironment) {
        self.session = session
        self.environment = environment
        _welcome = State(initialValue: WelcomeModel(api: environment.api) { [session] outcome in
            switch outcome {
            case .created(let household):
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
            TopBar(title: "AbodeNestly", trailing: session.profile?.displayName)
            content
        }
        .background(Tokens.bg)
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
            case .household(_, let tab):
                MainTabs(household: session.household, start: tab, environment: environment)
            case .home:
                MainTabs(household: nil, start: .home, environment: environment)
            }
        }
    }
}

/// Bottom tabs filtered by the household's module flags (DESIGN A6). Content arrives with the module stories.
private struct MainTabs: View {
    let household: LandingHousehold?
    let environment: AppEnvironment
    @State private var selection: AppTab

    init(household: LandingHousehold?, start: AppTab, environment: AppEnvironment) {
        self.household = household
        self.environment = environment
        _selection = State(initialValue: start)
    }

    private var tabs: [AppTab] {
        var tabs: [AppTab] = []
        if household?.moduleShopping == true { tabs.append(.shopping) }
        if household?.modulePantry == true { tabs.append(.pantry) }
        if household?.moduleMaintenance == true { tabs.append(.maintenance) }
        return tabs + [.home]
    }

    var body: some View {
        TabView(selection: $selection) {
            ForEach(tabs, id: \.self) { tab in
                tabContent(tab)
                    .tabItem { Label(title(tab), systemImage: icon(tab)) }
                    .tag(tab)
            }
        }
        .tint(Tokens.accent)
    }

    @ViewBuilder
    private func tabContent(_ tab: AppTab) -> some View {
        if tab == .home {
            // The household may be unknown offline at launch; fall back to the last one used.
            let householdId = household?.id ?? environment.lastHousehold.read()
            NavigationStack {
                VStack(spacing: 16) {
                    Text(title(tab))
                        .foregroundStyle(Tokens.text)
                        .accessibilityIdentifier("tab.home.content")
                    if let householdId {
                        NavigationLink("Members") {
                            MembersView(
                                householdId: householdId, api: environment.api,
                                isOnline: environment.network.isOnline, webBaseURL: environment.config.webBaseURL
                            )
                        }
                        .accessibilityIdentifier("home.members")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Tokens.bg)
            }
        } else {
            Text(title(tab))
                .foregroundStyle(Tokens.text)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier("tab.\(tab.rawValue).content")
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
