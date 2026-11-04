package live.mehiz.mpvkt.ui.smb.ads

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.RememberObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.ads.nativead.NativeAd
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.mehiz.mpvkt.BuildConfig

/** 广告缓存的归属单位：一个 SMB 目录页面。 */
data class SmbAdPageKey(
  val profileId: String,
  val shareName: String,
  val path: String,
) {
  companion object {
    /** 路径规则与 SmbBrowserViewModel 的滚动位置 key 保持一致，忽略首尾的 '/'。 */
    fun of(profileId: String, shareName: String, path: String) =
      SmbAdPageKey(profileId, shareName, path.trim('/'))
  }
}

/** 广告位的唯一标识：页面 + 页面内第几个广告位。 */
data class SmbAdSlotKey(
  val page: SmbAdPageKey,
  val slotIndex: Int,
)

/** 变体分配周期大小：每多少个广告位为一轮。修改这里即可调整大小广告的分配比例。 */
internal const val SMB_AD_VARIANT_CYCLE_SIZE = 10

/** 每个周期内使用小广告布局的广告位数量；必须小于 [SMB_AD_VARIANT_CYCLE_SIZE]。 */
internal const val SMB_SMALL_AD_SLOTS_PER_CYCLE = 9

/** 原生广告的布局变体。 */
enum class SmbNativeAdVariant {
  /** 小广告：高度高仿普通文件行，只展示图标、广告角标、标题与一行说明。 */
  SMALL,

  /** 大广告：完整卡片，含 MediaView 大图/视频与 CTA 按钮。 */
  LARGE,
}

/**
 * 确定性分配：按广告位序号在周期内的位置决定布局变体。
 *
 * 每 [SMB_AD_VARIANT_CYCLE_SIZE] 个广告位为一个周期，其中前
 * [SMB_SMALL_AD_SLOTS_PER_CYCLE] 个使用小广告，其余使用大广告
 * （默认 10 个中 9 小 1 大，即小广告占比 90%）。
 *
 * 纯函数：同一 slotIndex 永远得到同一结果，重组、滑回或旋转屏幕都不会让布局变化。
 */
internal fun resolveSmbNativeAdVariant(slotIndex: Int): SmbNativeAdVariant =
  if (slotIndex % SMB_AD_VARIANT_CYCLE_SIZE < SMB_SMALL_AD_SLOTS_PER_CYCLE) {
    SmbNativeAdVariant.SMALL
  } else {
    SmbNativeAdVariant.LARGE
  }

/** 单个广告位的状态。 */
@Immutable
sealed interface SmbNativeAdSlotState {
  /** 尚未请求，或广告已被回收（下次显示时重新请求）。 */
  data object Idle : SmbNativeAdSlotState

  /** 正在排队或请求中。 */
  data object Loading : SmbNativeAdSlotState

  /** 加载成功。NativeAd 归 [SmbNativeAdViewModel] 所有，UI 只负责展示，不得自行销毁。 */
  @Immutable
  data class Loaded(val nativeAd: NativeAd, val loadedAtMillis: Long) : SmbNativeAdSlotState

  /** 加载失败（如无填充、网络错误），冷却结束后再次显示时重试。 */
  data class Failed(val errorCode: Int, val failedAtMillis: Long) : SmbNativeAdSlotState
}

/**
 * 广告位凭证：列表项通过 `remember { viewModel.openSlot(key) }` 持有。
 *
 * 借助 [RememberObserver] 与列表项的组合生命周期精确绑定：
 * - 创建时（组合阶段）立即计入引用：列表项可能先读到 Loaded 状态、稍后才上屏
 *   （例如 LazyColumn 的可暂停预组合），这段时间广告也不会被回收；
 * - [onRemembered]（真正上屏）：按需发起广告请求；
 * - [onForgotten] / [onAbandoned]（离开组合 / 组合被放弃）：释放引用。
 *   用 DisposableEffect 无法覆盖“组合被放弃”的情况，引用会泄漏。
 */
class SmbNativeAdSlotHandle internal constructor(
  /** 广告位状态，UI 通过 collectAsState() 订阅。 */
  val state: StateFlow<SmbNativeAdSlotState>,
  /** 该广告位确定使用的布局变体，由广告位序号确定性解析，不随重组变化。 */
  val variant: SmbNativeAdVariant,
  private val onShown: () -> Unit,
  private val onReleased: () -> Unit,
) : RememberObserver {
  private var released = false

  override fun onRemembered() {
    if (!released) onShown()
  }

  override fun onForgotten() = release()

  override fun onAbandoned() = release()

  private fun release() {
    if (released) return
    released = true
    onReleased()
  }
}

/**
 * SMB 文件浏览页原生广告的请求、缓存和销毁管理（Activity 级 ViewModel）。
 *
 * 广告对象的所有权放在这里，而不是列表项 Composable 中：LazyColumn 的 item 一滑出屏幕就会被销毁，
 * 如果跟随 item 销毁广告，来回滑动就会不断重新请求，既浪费请求又会让列表高度反复跳动。
 *
 * [NativeAd.destroy] 的调用时机：
 * - 页面退出（目录页从返回栈移除）：[retainPages]
 * - 列表刷新后广告位不再存在：[trimPage]
 * - 已加载广告超过 [MAX_CACHED_ADS]：回收无人持有且最久未使用的广告
 * - 广告过期后再次显示：[openSlot]
 * - Activity 真正结束：[onCleared]
 * - 请求返回时广告位已作废：[onRequestFinished] 立即销毁晚到的广告
 *
 * 页面退出 / 列表刷新时，若广告仍被列表项持有（例如页面退出动画尚未结束），
 * 会推迟到最后一个凭证释放时（[releaseSlot]）再销毁，避免正在显示的广告失效。
 *
 * 每个广告项对应的 NativeAdView 则随 Composable 一起销毁（见 SmbNativeAdItemView 的 onRelease）。
 *
 * 所有状态都只在主线程读写（组合、RememberObserver 回调与 [SmbNativeAdLoader] 的回调都在主线程），无需加锁。
 */
class SmbNativeAdViewModel(application: Application) : AndroidViewModel(application) {

  private val adLoader = SmbNativeAdLoader(application, BuildConfig.ADMOB_SMB_FILE_BROWSER_AD_UNIT_ID)

  private val slots = HashMap<SmbAdSlotKey, SlotRecord>()

  /** 等待发起请求的广告位（先进先出），用于限制并发请求数。 */
  private val pendingSlots = ArrayDeque<SlotRecord>()
  private var inFlightCount = 0
  private var nextRequestId = 0L
  private var isCleared = false

  /**
   * 打开一个广告位，必须在 `remember` 中调用，返回的凭证由 Compose 负责释放。
   *
   * 过期检查放在这里：列表项首帧就会读取状态，必须在此之前丢弃过期广告，
   * 才不会把即将销毁的广告绑定到 View 上。
   */
  fun openSlot(key: SmbAdSlotKey): SmbNativeAdSlotHandle {
    if (isCleared) {
      return SmbNativeAdSlotHandle(
        MutableStateFlow(SmbNativeAdSlotState.Idle),
        variant = resolveSmbNativeAdVariant(key.slotIndex),
        onShown = {},
        onReleased = {},
      )
    }
    val record = slots.getOrPut(key) { SlotRecord(key) }
    val state = record.state.value
    if (
      record.holdCount == 0 &&
      state is SmbNativeAdSlotState.Loaded &&
      now() - state.loadedAtMillis >= AD_EXPIRY_MS
    ) {
      record.destroyAd()
    }
    record.holdCount++
    record.lastUsedAt = now()
    // 被标记移除后又重新显示（例如列表缩短后又恢复），恢复为正常广告位
    record.removeWhenReleased = false
    return SmbNativeAdSlotHandle(
      state = record.state.asStateFlow(),
      variant = record.variant,
      onShown = { onSlotShown(record) },
      onReleased = { releaseSlot(record) },
    )
  }

  /** 列表刷新后广告位变少时，销毁序号超出范围的广告位上的广告。 */
  fun trimPage(page: SmbAdPageKey, slotCount: Int) {
    removeSlots { it.page == page && it.slotIndex >= slotCount }
  }

  /**
   * 页面退出：只保留 [alivePages]（仍在返回栈中的目录页）的广告，其余页面的广告全部销毁，
   * 进行中的请求也随之作废。按返回栈“对账”而不是只处理当前页，
   * 这样一次弹出多个页面时，不在组合中的页面的广告也不会遗漏。
   */
  fun retainPages(alivePages: Set<SmbAdPageKey>) {
    removeSlots { it.page !in alivePages }
  }

  override fun onCleared() {
    isCleared = true
    slots.values.forEach { it.destroyAd() }
    slots.clear()
    pendingSlots.clear()
    super.onCleared()
  }

  /** 广告位真正上屏：按需发起请求。 */
  private fun onSlotShown(record: SlotRecord) {
    // 记录已被移除（页面已退出）或 ViewModel 已清理时不再请求
    if (isCleared || slots[record.key] !== record) return
    when (val state = record.state.value) {
      SmbNativeAdSlotState.Idle -> enqueue(record)
      is SmbNativeAdSlotState.Failed -> {
        if (now() - state.failedAtMillis >= RETRY_BACKOFF_MS) enqueue(record)
      }
      SmbNativeAdSlotState.Loading, is SmbNativeAdSlotState.Loaded -> Unit
    }
    processQueue()
  }

  /** 列表项释放凭证。只减少引用计数，广告保留在缓存中，滑回来可以直接复用。 */
  private fun releaseSlot(record: SlotRecord) {
    record.holdCount = (record.holdCount - 1).coerceAtLeast(0)
    if (record.holdCount == 0 && record.removeWhenReleased) {
      // 已作废的广告位最后一个凭证释放：此时才真正销毁
      record.destroyAd()
      if (slots[record.key] === record) slots.remove(record.key)
    }
  }

  private fun removeSlots(predicate: (SmbAdSlotKey) -> Boolean) {
    pendingSlots.removeAll { predicate(it.key) }
    val iterator = slots.values.iterator()
    while (iterator.hasNext()) {
      val record = iterator.next()
      if (!predicate(record.key)) continue
      if (record.holdCount > 0) {
        // 仍被列表项持有（例如页面退出动画尚未结束、列表还没刷新掉这一行）：
        // 立即销毁会让正在显示的广告失效。先作废进行中的请求，等最后一个凭证释放时再销毁。
        record.activeRequestId = null
        if (record.state.value == SmbNativeAdSlotState.Loading) {
          record.state.value = SmbNativeAdSlotState.Idle
        }
        record.removeWhenReleased = true
      } else {
        record.destroyAd()
        // 记录被移除后，进行中的请求返回时找不到记录，广告会被直接销毁
        iterator.remove()
      }
    }
  }

  private fun enqueue(record: SlotRecord) {
    record.state.value = SmbNativeAdSlotState.Loading
    pendingSlots.addLast(record)
  }

  private fun processQueue() {
    while (!isCleared && inFlightCount < MAX_CONCURRENT_REQUESTS) {
      val record = pendingSlots.removeFirstOrNull() ?: return
      // 防御：只处理仍有效且处于“排队中”的记录，避免重复请求或为已移除的广告位请求
      if (slots[record.key] !== record) continue
      if (record.state.value != SmbNativeAdSlotState.Loading || record.activeRequestId != null) continue
      if (record.holdCount == 0) {
        // 排队期间已滑出屏幕：放弃本次请求，下次显示时再加载（快速滑动长列表时不会产生无效请求）
        record.state.value = SmbNativeAdSlotState.Idle
        continue
      }
      startRequest(record)
    }
  }

  private fun startRequest(record: SlotRecord) {
    val requestId = ++nextRequestId
    record.activeRequestId = requestId
    inFlightCount++
    viewModelScope.launch {
      adLoader.load { result -> onRequestFinished(record, requestId, result) }
    }
  }

  private fun onRequestFinished(record: SlotRecord, requestId: Long, result: SmbNativeAdLoadResult) {
    inFlightCount = (inFlightCount - 1).coerceAtLeast(0)
    val isStale = isCleared || slots[record.key] !== record || record.activeRequestId != requestId
    if (isStale) {
      // 页面已退出、广告位已作废或 ViewModel 已清理：晚到的广告没有展示机会，立即销毁防止泄漏
      if (result is SmbNativeAdLoadResult.Success) result.nativeAd.destroy()
    } else {
      record.activeRequestId = null
      when (result) {
        is SmbNativeAdLoadResult.Success -> {
          record.state.value = SmbNativeAdSlotState.Loaded(result.nativeAd, now())
          evictOverflow()
        }
        is SmbNativeAdLoadResult.Failure -> {
          Log.w(TAG, "Native ad failed to load: code=${result.code}, message=${result.message}")
          record.state.value = SmbNativeAdSlotState.Failed(result.code, now())
        }
      }
    }
    processQueue()
  }

  /** 控制内存占用：已加载的广告超出上限时，回收无人持有且最久未使用的广告。 */
  private fun evictOverflow() {
    val loaded = slots.values.filter { it.state.value is SmbNativeAdSlotState.Loaded }
    val overflow = loaded.size - MAX_CACHED_ADS
    if (overflow <= 0) return
    loaded
      .filter { it.holdCount == 0 }
      .sortedBy { it.lastUsedAt }
      .take(overflow)
      .forEach { it.destroyAd() }
  }

  private fun now(): Long = SystemClock.elapsedRealtime()

  private class SlotRecord(val key: SmbAdSlotKey) {
    val state = MutableStateFlow<SmbNativeAdSlotState>(SmbNativeAdSlotState.Idle)

    /** 该广告位的布局变体，记录创建时按序号解析一次后固定。 */
    val variant: SmbNativeAdVariant = resolveSmbNativeAdVariant(key.slotIndex)

    /** 持有该广告位凭证的列表项数量（从组合开始到离开组合），大于 0 时不得销毁其广告。 */
    var holdCount = 0
    var lastUsedAt = 0L

    /** 当前有效请求的 ID，用于识别已作废的回调。 */
    var activeRequestId: Long? = null

    /** 广告位已作废但仍被持有，等最后一个凭证释放时再销毁并移除。 */
    var removeWhenReleased = false

    /** 销毁已加载的广告并回到 Idle，同时让进行中的请求作废。 */
    fun destroyAd() {
      (state.value as? SmbNativeAdSlotState.Loaded)?.nativeAd?.destroy()
      state.value = SmbNativeAdSlotState.Idle
      activeRequestId = null
    }
  }

  private companion object {
    const val TAG = "SmbNativeAd"

    /** 同时进行中的广告请求上限。 */
    const val MAX_CONCURRENT_REQUESTS = 2

    /** 最多缓存的已加载广告数。 */
    const val MAX_CACHED_ADS = 8

    /** 广告有效期：AdMob 建议加载后 1 小时内展示，这里留出余量。 */
    const val AD_EXPIRY_MS = 55 * 60 * 1000L

    /** 加载失败后的重试冷却时间。 */
    const val RETRY_BACKOFF_MS = 60 * 1000L
  }
}
