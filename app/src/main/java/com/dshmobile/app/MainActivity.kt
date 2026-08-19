package com.dshmobile.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshmobile.app.ui.ChatScreen
import com.dshmobile.app.ui.ChatViewModel
import com.dshmobile.app.ui.SettingsScreen
import com.dshmobile.app.ui.theme.DshTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {

    /**
     * Intents that carry shared content. The activity is `singleTask`, so a share while the app is
     * already open arrives through [onNewIntent] rather than a fresh instance; both paths funnel
     * here so the composable sees them the same way.
     */
    private val incoming = Channel<Intent>(Channel.BUFFERED)
    private val incomingFlow: Flow<Intent> = incoming.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DshRoot(incomingFlow) }
        intent?.let { incoming.trySend(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.trySend(intent)
    }
}

private enum class Screen { CHAT, SETTINGS }

@Composable
private fun DshRoot(incoming: Flow<Intent>) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as DshApp).container }
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(container, context.applicationContext),
    )
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.CHAT) }

    LaunchedEffect(Unit) {
        incoming.collect { intent ->
            screen = Screen.CHAT
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let(viewModel::ingestSharedText)
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let { viewModel.ingestSharedImages(listOf(it)) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.filterNotNull()
                        ?.let(viewModel::ingestSharedImages)
                }
            }
        }
    }

    DshTheme(themeMode = settings.themeMode) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    if (targetState == Screen.SETTINGS) {
                        (slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(220)) { -it / 6 } + fadeOut(tween(160)))
                    } else {
                        (slideInHorizontally(tween(220)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(160)))
                    }.using(SizeTransform(clip = false))
                },
                label = "screen",
            ) { target ->
                when (target) {
                    Screen.CHAT -> ChatScreen(
                        viewModel = viewModel,
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.SETTINGS -> {
                        BackHandler { screen = Screen.CHAT }
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.CHAT },
                        )
                    }
                }
            }
        }
    }
}
