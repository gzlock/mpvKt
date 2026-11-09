package live.mehiz.mpvkt.di

import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import live.mehiz.mpvkt.network.remote.RemoteProfileStore
import live.mehiz.mpvkt.network.remote.RemoteRuntime
import live.mehiz.mpvkt.network.remote.RemoteBrowserPreferences

// generic dependencies for the app's needs
val AppModule = module {
  single {
    Json {
      isLenient = true
      ignoreUnknownKeys = true
    }
  }
  single { RemoteProfileStore(androidContext(), get()) }
  single { RemoteBrowserPreferences(androidContext()) }
  single { RemoteRuntime(get()) }
}
