package live.mehiz.mpvkt.ui.remote

import androidx.compose.runtime.Immutable
import live.mehiz.mpvkt.network.remote.RemoteEntry

/** 默认每隔多少个文件 item 插入一个原生广告。 */
const val REMOTE_NATIVE_AD_DEFAULT_INTERVAL = 10

/**
 * 广告位 key 的前缀。':' 是 SMB/NTFS 文件名中的非法字符，
 * 所以广告位的 key 永远不会和任何文件路径（[RemoteEntry.path]）冲突。
 */
private const val NATIVE_AD_KEY_PREFIX = "remote-native-ad:"

/**
 * 远程列表的使用场景，决定该列表能否插入广告。
 *
 * [RemoteBrowserScreen] 同时承担“文件浏览”和“选择字幕”两种职责，这里把 `subtitlePicker`
 * 布尔值转成明确的模式，让“字幕列表不能有广告”这条业务规则只存在于一个地方。
 */
enum class RemoteBrowserListMode(val allowsNativeAds: Boolean) {
  /** 主远程文件浏览列表：允许插入原生广告。 */
  FILE_BROWSER(allowsNativeAds = true),

  /** 远程字幕文件列表（播放器内“添加远程字幕”打开的选择器）：禁止插入广告。 */
  SUBTITLE_PICKER(allowsNativeAds = false),
  ;

  companion object {
    fun of(subtitlePicker: Boolean): RemoteBrowserListMode =
      if (subtitlePicker) SUBTITLE_PICKER else FILE_BROWSER
  }
}

/** LazyColumn 的 contentType：文件行和广告行结构完全不同，不能互相复用。 */
enum class RemoteBrowserListItemType { FILE, NATIVE_AD }

/** 远程文件列表的混排数据项：文件 / 原生广告位。 */
@Immutable
sealed interface RemoteBrowserListItem {
  /** LazyColumn 的稳定 key。必须是 String 等可存入 Bundle 的类型，且在列表内唯一。 */
  val key: String

  val contentType: RemoteBrowserListItemType

  /** 一个文件或文件夹。 */
  @Immutable
  data class FileEntry(val entry: RemoteEntry) : RemoteBrowserListItem {
    // 继续使用 entry.path 作为 key，与已有的滚动锚点（RemoteDirectoryScrollPosition.anchorPath）保持兼容。
    override val key: String = entry.path
    override val contentType: RemoteBrowserListItemType = RemoteBrowserListItemType.FILE
  }

  /**
   * 广告占位符。这里只记录“第几个广告位”，不持有 NativeAd：
   * 广告对象有生命周期，由 RemoteNativeAdViewModel 统一管理，列表数据保持纯粹、可随意重建。
   */
  @Immutable
  data class NativeAdSlot(val slotIndex: Int) : RemoteBrowserListItem {
    override val key: String = "$NATIVE_AD_KEY_PREFIX$slotIndex"
    override val contentType: RemoteBrowserListItemType = RemoteBrowserListItemType.NATIVE_AD
  }
}

/**
 * 把文件列表组合成“文件 + 广告位”的混排列表。
 *
 * 规则：
 * - 每 [adInterval] 个文件之后插入一个广告位（文件和文件夹都计入，因为它们都是列表里的一行）。
 * - 常规广告位只出现在两个文件之间：不放在列表开头，也不追加在列表末尾。
 * - 文件数少于 [adInterval] 的非空短列表，常规规则下不会产生任何广告位，
 *   此时在列表末尾追加一个广告位，保证短列表也有广告曝光。
 * - [mode] 不允许广告（远程字幕列表）或 [adInterval] 不是正数时，只返回文件项。
 *
 * 广告位序号按位置递增，所以目录内容不变时广告位的 key 和位置也不变，
 * 从子目录返回后可以直接复用已缓存的广告（滚动位置以文件行为锚点恢复，不受广告高度影响）。
 */
fun buildRemoteBrowserListItems(
  entries: List<RemoteEntry>,
  mode: RemoteBrowserListMode,
  adInterval: Int = REMOTE_NATIVE_AD_DEFAULT_INTERVAL,
): List<RemoteBrowserListItem> {
  if (!mode.allowsNativeAds || adInterval <= 0) {
    return entries.map { RemoteBrowserListItem.FileEntry(it) }
  }
  return buildList<RemoteBrowserListItem>(capacity = entries.size + entries.size / adInterval + 1) {
    var nextSlotIndex = 0
    entries.forEachIndexed { index, entry ->
      add(RemoteBrowserListItem.FileEntry(entry))
      val fileCount = index + 1
      val isIntervalSlot = fileCount % adInterval == 0 && fileCount < entries.size
      // 短列表（文件数不足一个间隔）：在最后一个文件后补一个广告位
      val isTailSlot = entries.size < adInterval && fileCount == entries.size
      if (isIntervalSlot || isTailSlot) {
        add(RemoteBrowserListItem.NativeAdSlot(nextSlotIndex++))
      }
    }
  }
}
