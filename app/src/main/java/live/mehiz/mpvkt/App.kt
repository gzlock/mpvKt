package live.mehiz.mpvkt

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import live.mehiz.mpvkt.di.AppModule
import live.mehiz.mpvkt.di.DatabaseModule
import live.mehiz.mpvkt.di.FileManagerModule
import live.mehiz.mpvkt.di.PreferencesModule
import live.mehiz.mpvkt.network.remote.RemoteRuntime
import live.mehiz.mpvkt.presentation.crash.CrashActivity
import live.mehiz.mpvkt.presentation.crash.GlobalExceptionHandler
import live.mehiz.mpvkt.ui.remote.thumbnail.RemoteThumbnailFetcher
import live.mehiz.mpvkt.ui.remote.thumbnail.RemoteVideoThumbnailKeyer
import org.koin.android.ext.koin.androidContext
import org.koin.androix.startup.KoinStartup
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.context.GlobalContext
import org.koin.dsl.koinConfiguration
import com.google.android.gms.ads.MobileAds // 1. 导入 AdMob SDK

@OptIn(KoinExperimentalAPI::class)
class App : Application(), KoinStartup, SingletonImageLoader.Factory {
  override fun onCreate() {
    super.onCreate()
    Thread.setDefaultUncaughtExceptionHandler(
      GlobalExceptionHandler(applicationContext, CrashActivity::class.java),
    )
    // 2. 初始化 Google Mobile Ads SDK
    MobileAds.initialize(this) { initializationStatus ->
      // 初始化完成后的回调（可选）
    }
  }

  override fun newImageLoader(context: Context): ImageLoader {
    val runtime = GlobalContext.get().get<RemoteRuntime>()
    return ImageLoader.Builder(context)
      .components {
        add(RemoteVideoThumbnailKeyer())
        add(RemoteThumbnailFetcher.Factory(runtime))
      }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("remote_thumbnail_cache"))
          .maxSizeBytes(DISK_CACHE_MAX_BYTES)
          .build()
      }
      .build()
  }

  override fun onKoinStartup() = koinConfiguration {
    androidContext(this@App)
    modules(
      AppModule,
      PreferencesModule,
      DatabaseModule,
      FileManagerModule,
    )
  }

  private companion object {
    const val DISK_CACHE_MAX_BYTES = 100L * 1024 * 1024
  }
}
