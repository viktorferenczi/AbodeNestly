import CryptoKit
import Foundation

/// Sign in with Apple: Apple receives the SHA-256 of a random nonce, Supabase receives the raw one.
public struct Nonce: Sendable, Equatable {
    public let raw: String

    public init(raw: String = Nonce.randomString()) {
        self.raw = raw
    }

    public var hashed: String {
        SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public static func randomString(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var generator = SystemRandomNumberGenerator()
        return String((0..<length).map { _ in charset.randomElement(using: &generator)! })
    }
}
