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
                SignedInView(session: session)
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
                WelcomePlaceholder(session: session)
            case .household(_, let tab):
                MainTabs(household: session.household, start: tab)
            case .home:
                MainTabs(household: nil, start: .home)
            }
        }
    }
}

/// B2 content (create or join) is CORE-02; this only proves the landing rule.
private struct WelcomePlaceholder: View {
    let session: SessionModel

    var body: some View {
        VStack(spacing: 16) {
            Text("Welcome")
                .font(.title.weight(.semibold))
                .foregroundStyle(Tokens.text)
                .accessibilityIdentifier("welcome.title")
            Button("Sign out") { Task { await session.signOut() } }
                .accessibilityIdentifier("welcome.signout")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Bottom tabs filtered by the household's module flags (DESIGN A6). Content arrives with the module stories.
private struct MainTabs: View {
    let household: LandingHousehold?
    @State private var selection: AppTab

    init(household: LandingHousehold?, start: AppTab) {
        self.household = household
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
                Text(title(tab))
                    .foregroundStyle(Tokens.text)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .tabItem { Label(title(tab), systemImage: icon(tab)) }
                    .tag(tab)
                    .accessibilityIdentifier("tab.\(tab.rawValue).content")
            }
        }
        .tint(Tokens.accent)
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
