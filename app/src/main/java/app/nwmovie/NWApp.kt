package app.nwmovie

import android.app.Application
import app.nwmovie.data.CacheManager
import app.nwmovie.data.DownloadService
import app.nwmovie.data.telegram.Tg
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NWApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        app.nwmovie.data.Integrity.verify(this)
        ServiceLocator.init(this)
        DownloadService.ensureChannel(this)
        ServiceLocator.tg.start()
        ServiceLocator.scope.launch {
            // Housekeeping (size limits + 3-day inactivity cleanup) once Telegram is connected.
            ServiceLocator.tg.auth.filterIsInstance<Tg.Auth.Ready>().first()
            runCatching { CacheManager.autoClean(this@NWApp) }
        }
    }

    override fun newImageLoader(): ImageLoader = ServiceLocator.images
}
