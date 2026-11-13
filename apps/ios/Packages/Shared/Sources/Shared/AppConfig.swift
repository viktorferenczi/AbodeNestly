import Foundation

/// Build-time configuration injected through xcconfig → Info.plist (SPEC §3). Never hard-code these values.
public struct AppConfig: Sendable, Equatable {
    public let apiBaseURL: URL
    public let supabaseURL: URL
    public let supabaseAnonKey: String
    /// Custom URL scheme for auth callbacks, e.g. `abodenestly-dev`.
    public let authURLScheme: String
    /// Origin of the web app; invite links are `<webBaseURL>/join/<code>`.
    public let webBaseURL: URL

    public init(apiBaseURL: URL, supabaseURL: URL, supabaseAnonKey: String, authURLScheme: String, webBaseURL: URL) {
        self.apiBaseURL = apiBaseURL
        self.supabaseURL = supabaseURL
        self.supabaseAnonKey = supabaseAnonKey
        self.authURLScheme = authURLScheme
        self.webBaseURL = webBaseURL
    }

    /// The URL Supabase redirects to after a magic link or OAuth round trip.
    public var authCallbackURL: URL {
        URL(string: "\(authURLScheme)://auth/callback")!
    }

    /// Read once from the app bundle. A missing key is a build misconfiguration, so it traps loudly.
    public static let main = AppConfig(infoDictionary: Bundle.main.infoDictionary ?? [:])

    public init(infoDictionary info: [String: Any]) {
        func string(_ key: String) -> String {
            guard let value = info[key] as? String, !value.isEmpty else {
                preconditionFailure("Missing Info.plist key \(key); check Config/*.xcconfig")
            }
            return value
        }
        func url(_ key: String) -> URL {
            guard let url = URL(string: string(key)), url.scheme != nil else {
                preconditionFailure("Info.plist key \(key) is not a URL")
            }
            return url
        }
        self.init(
            apiBaseURL: url("API_BASE_URL"),
            supabaseURL: url("SUPABASE_URL"),
            // The anon key may be blank until Dev.local.xcconfig is filled in; requests will 401, not crash.
            supabaseAnonKey: (info["SUPABASE_ANON_KEY"] as? String) ?? "",
            authURLScheme: string("AUTH_URL_SCHEME"),
            webBaseURL: url("WEB_BASE_URL")
        )
    }
}
