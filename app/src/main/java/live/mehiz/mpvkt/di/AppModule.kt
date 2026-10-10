package live.mehiz.mpvkt.di

import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import live.mehiz.mpvkt.network.smb.SmbProfileStore
import live.mehiz.mpvkt.network.smb.SmbRuntime
import live.mehiz.mpvkt.network.smb.SmbBrowserPreferences

// generic dependencies for the app's needs
val AppModule = module {
  single {
    Json {
      isLenient = true
      ignoreUnknownKeys = true
    }
  }
  single { SmbProfileStore(androidContext(), get()) }
  single { SmbBrowserPreferences(androidContext()) }
  single { SmbRuntime(get()) }
}
