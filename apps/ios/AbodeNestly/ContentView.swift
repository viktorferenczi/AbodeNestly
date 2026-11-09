import Auth
import GRDB
import Realtime
import SwiftUI

/// Empty shell. Tabs filtered by module flags arrive with the CORE stories.
/// The GRDB / Auth / Realtime imports keep the dependency link in place until real code uses them.
struct ContentView: View {
    var body: some View {
        NavigationStack {
            Text("AbodeNestly")
                .font(.title)
                .navigationTitle("AbodeNestly")
        }
    }
}

#Preview {
    ContentView()
}
