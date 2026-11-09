package live.mehiz.mpvkt.ui.remote.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** 一次原生广告请求的结果。 */
internal sealed interface RemoteNativeAdLoadResult {
  class Success(val nativeAd: NativeAd) : RemoteNativeAdLoadResult

  /** [code] 为 AdMob 错误码（如 3 = 无填充），负数为本地错误（超时/异常）。 */
  class Failure(val code: Int, val message: String) : RemoteNativeAdLoadResult
}

/**
 * 原生高级广告（Native Advanced）的请求封装。
 *
 * - 使用 Application Context：加载好的 NativeAd 会缓存在 ViewModel 里，跨屏幕旋转存活，
 *   若用 Activity Context 构建，旋转后旧 Activity 会被广告对象间接持有而泄漏。
 * - 按 Google 官方建议在后台线程构建 AdLoader，再回到调用方（主线程）发起请求。
 * - 结果保证“只回调一次，且在主线程”。同一请求多余的、或超时后才到达的广告会立即 destroy()。
 */
internal class RemoteNativeAdLoader(
  context: Context,
  private val adUnitId: String,
) {
  private val appContext = context.applicationContext

  /**
   * 请求一条原生广告。需在主线程的协程中调用（如 viewModelScope）。
   *
   * [onResult] 收到 [RemoteNativeAdLoadResult.Success] 后，广告的所有权就转交给调用方，
   * 由调用方负责最终调用 [NativeAd.destroy]。
   */
  suspend fun load(onResult: (RemoteNativeAdLoadResult) -> Unit) {
    val callback = SingleResultCallback(onResult)
    try {
      val adLoader = withContext(Dispatchers.IO) { buildAdLoader(callback) }
      adLoader.loadAd(AdRequest.Builder().build())
      // SDK 正常情况下必定回调成功或失败；超时只是兜底，避免请求“悬空”一直占用并发名额。
      val finished = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { callback.awaitResult() }
      if (finished == null) {
        callback.deliver(RemoteNativeAdLoadResult.Failure(ERROR_CODE_TIMEOUT, "Native ad request timed out"))
      }
    } catch (e: CancellationException) {
      // 只有 ViewModel 被清理时才会取消；之后到达的广告由调用方识别为“过期回调”并销毁。
      throw e
    } catch (e: Exception) {
      callback.deliver(RemoteNativeAdLoadResult.Failure(ERROR_CODE_EXCEPTION, e.message.orEmpty()))
    }
  }

  private fun buildAdLoader(callback: SingleResultCallback): AdLoader =
    AdLoader.Builder(appContext, adUnitId)
      .forNativeAd { nativeAd -> callback.deliver(RemoteNativeAdLoadResult.Success(nativeAd)) }
      .withAdListener(
        object : AdListener() {
          override fun onAdFailedToLoad(adError: LoadAdError) {
            callback.deliver(RemoteNativeAdLoadResult.Failure(adError.code, adError.message))
          }
        },
      )
      .withNativeAdOptions(
        NativeAdOptions.Builder()
          // 列表项更适合横向媒体素材
          .setMediaAspectRatio(NativeAdOptions.NATIVE_MEDIA_ASPECT_RATIO_LANDSCAPE)
          // AdChoices 放右上角，布局中已为它预留空间
          .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
          // 列表中的视频广告默认静音，避免突然出声
          .setVideoOptions(VideoOptions.Builder().setStartMuted(true).build())
          .build(),
      )
      .build()

  /** 把 SDK 回调收敛为“主线程上恰好一次”的结果。 */
  private class SingleResultCallback(
    private val onResult: (RemoteNativeAdLoadResult) -> Unit,
  ) {
    private val delivered = AtomicBoolean(false)
    private val finished = CompletableDeferred<Unit>()

    suspend fun awaitResult() = finished.await()

    fun deliver(result: RemoteNativeAdLoadResult) {
      if (Looper.getMainLooper().isCurrentThread) {
        dispatch(result)
      } else {
        mainHandler.post { dispatch(result) }
      }
    }

    private fun dispatch(result: RemoteNativeAdLoadResult) {
      if (delivered.compareAndSet(false, true)) {
        finished.complete(Unit)
        onResult(result)
      } else if (result is RemoteNativeAdLoadResult.Success) {
        // 已经有结果了（例如已判定超时），多余的广告没人持有，必须立即销毁
        result.nativeAd.destroy()
      }
    }
  }

  private companion object {
    const val REQUEST_TIMEOUT_MS = 60_000L
    const val ERROR_CODE_TIMEOUT = -1
    const val ERROR_CODE_EXCEPTION = -2
    val mainHandler = Handler(Looper.getMainLooper())
  }
}
