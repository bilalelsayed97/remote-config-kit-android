package io.github.bilalelsayed97.remoteconfigkit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.bilalelsayed97.remoteconfigkit.InitialFetchPolicy
import io.github.bilalelsayed97.remoteconfigkit.RemoteConfigKit
import io.github.bilalelsayed97.remoteconfigkit.RemoteConfigOptions
import io.github.bilalelsayed97.remoteconfigkit.TargetingContext

private const val CONFIG_URL = "https://raw.githubusercontent.com/OWNER/REPO/main/remote-config.json"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    ConfigBootstrap(Modifier.padding(padding))
                }
            }
        }
    }
}

private sealed interface Phase {
    data object Loading : Phase
    data class Ready(val config: RemoteConfigKit) : Phase
    data class Failed(val message: String) : Phase
}

@Composable
private fun ConfigBootstrap(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val phase by produceState<Phase>(Phase.Loading) {
        value = try {
            val config = RemoteConfigKit.create(
                context,
                RemoteConfigOptions(
                    url = CONFIG_URL,
                    context = TargetingContext(country = "SA"),
                    defaults = mapOf("new_checkout_enabled" to false),
                ),
            )
            config.start(InitialFetchPolicy.USE_DEFAULTS)
            Phase.Ready(config)
        } catch (error: Exception) {
            Phase.Failed(error.message ?: error.toString())
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val current = phase) {
            Phase.Loading -> CircularProgressIndicator()
            is Phase.Failed -> Text("Config unavailable: ${current.message}")
            is Phase.Ready -> {
                DisposableEffect(current.config) { onDispose { current.config.close() } }
                ConfigView(current.config)
            }
        }
    }
}

@Composable
private fun ConfigView(config: RemoteConfigKit) {
    // Reading revision recomposes this view after each activation or context change.
    val revision by config.revision.collectAsStateWithLifecycle()
    val enabled = config.getBool("new_checkout_enabled")
    Text(if (enabled) "New checkout enabled" else "New checkout disabled", style = MaterialTheme.typography.titleLarge)
    ConfigDebugView(config, revision)
}

/** A private debug view: installation ID and the exact targeting attributes. */
@Composable
private fun ConfigDebugView(config: RemoteConfigKit, revision: Long) {
    val inspection = config.inspect("new_checkout_enabled")
    SelectionContainer {
        Text(
            "Revision: $revision\n" +
                "Installation ID: ${config.installationId}\n" +
                "Matched condition: ${inspection.matchedConditionId}\n" +
                "Targeting: ${inspection.targeting.toJson()}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
