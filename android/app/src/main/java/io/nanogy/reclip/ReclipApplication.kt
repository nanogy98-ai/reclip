package io.nanogy.reclip

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReclipApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            runCatching {
                DownloaderEngine.ensureReady(this@ReclipApplication)
                DownloaderEngine.maybeRefreshExtractor(this@ReclipApplication)
            }
        }
    }
}
