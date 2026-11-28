package com.abodenestly.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.abodenestly.shopping.AssistantResult
import com.abodenestly.shopping.AssistantShortcut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * SHOP-15: the target of the Google Assistant App Action (`res/xml/shortcuts.xml`). Adds the spoken item to the last
 * opened household's default list, tells the person with a Toast (a launch-intent App Action has no spoken reply), kicks
 * sync after a success and finishes without ever showing the main UI. The list name the Assistant may send is ignored.
 */
class AssistantAddActivity : ComponentActivity() {
    private val graph get() = (application as AbodeNestlyApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = intent.getStringExtra(EXTRA_ITEM).orEmpty()
        lifecycleScope.launch {
            val result = run(item)
            Toast.makeText(applicationContext, result.reply, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private suspend fun run(item: String): AssistantResult = try {
        val session = graph.auth.currentSession()
        // Signed out, or no household opened yet: the no-household reply, never a crash.
        val household: UUID? = if (session == null) null else graph.lastHousehold.read()
        val result = AssistantShortcut.add(item, household, session?.userId, graph.database)
        if (result is AssistantResult.Added) graph.sync.kick()
        result
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AssistantResult.Failed
    }

    companion object {
        /** The App Action's `itemList.itemListElement.name` parameter, mapped in shortcuts.xml. */
        const val EXTRA_ITEM = "item"
    }
}
