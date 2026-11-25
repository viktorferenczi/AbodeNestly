import Foundation

/// Client twin of the SQL `name_key`: `lower(regexp_replace(trim(p), '\s+', ' ', 'g'))` (SPEC §5). Postgres `trim()`
/// strips only U+0020, so a leading or trailing tab survives the trim and then collapses to one space, exactly as in
/// `web/src/lib/nameKey.ts`.
public enum NameKey {
    public static func make(_ name: String) -> String {
        var scalars = Substring(name)
        while scalars.first == " " { scalars = scalars.dropFirst() }
        while scalars.last == " " { scalars = scalars.dropLast() }
        var out = ""
        var inSpace = false
        for character in scalars {
            if character.isWhitespace {
                if !inSpace { out.append(" ") }
                inSpace = true
            } else {
                out.append(character)
                inSpace = false
            }
        }
        return out.lowercased()
    }
}
