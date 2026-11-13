import XCTest
@testable import Shared

final class AppConfigTests: XCTestCase {
    func testCORE01_readsKeysFromInfoDictionary() {
        let config = AppConfig(infoDictionary: [
            "API_BASE_URL": "http://localhost:3000",
            "SUPABASE_URL": "http://127.0.0.1:54321",
            "SUPABASE_ANON_KEY": "anon",
            "AUTH_URL_SCHEME": "abodenestly-dev",
            "WEB_BASE_URL": "http://localhost:5173",
        ])
        XCTAssertEqual(config.supabaseURL.absoluteString, "http://127.0.0.1:54321")
        XCTAssertEqual(config.supabaseAnonKey, "anon")
        XCTAssertEqual(config.webBaseURL.absoluteString, "http://localhost:5173")
        XCTAssertEqual(config.authCallbackURL.absoluteString, "abodenestly-dev://auth/callback")
    }
}
