package com.dshmobile.app

import android.app.Application
import android.content.Context
import com.dshmobile.app.data.ConversationStore
import com.dshmobile.app.data.SettingsStore
import com.dshmobile.app.net.OpenAiClient
import com.dshmobile.app.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** Hand-rolled container: three long-lived singletons don't justify a DI framework. */
class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(File(context.filesDir, "settings.json"), scope)
    val conversations = ConversationStore(context.filesDir, scope)
    val client = OpenAiClient()
    val updater = Updater(context.applicationContext, settings, scope)
}

class DshApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
