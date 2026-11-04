package live.mehiz.mpvkt.ui.smb

import live.mehiz.mpvkt.network.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbBrowserListItemTest {

  private fun entries(count: Int) = List(count) { index ->
    SmbEntry(name = "video$index.mkv", path = "movies/video$index.mkv", directory = false)
  }

  /** 把混排结果转成易读的形式：文件 -> "F序号"，广告位 -> "A序号"。 */
  private fun layout(items: List<SmbBrowserListItem>): List<String> {
    var fileIndex = 0
    return items.map { item ->
      when (item) {
        is SmbBrowserListItem.FileEntry -> "F${fileIndex++}"
        is SmbBrowserListItem.NativeAdSlot -> "A${item.slotIndex}"
      }
    }
  }

  @Test
  fun insertsOneAdAfterEveryIntervalFiles() {
    val items = buildSmbBrowserListItems(entries(7), SmbBrowserListMode.FILE_BROWSER, adInterval = 3)
    assertEquals(listOf("F0", "F1", "F2", "A0", "F3", "F4", "F5", "A1", "F6"), layout(items))
  }

  @Test
  fun neverAppendsAnAdAfterTheLastFile() {
    val items = buildSmbBrowserListItems(entries(6), SmbBrowserListMode.FILE_BROWSER, adInterval = 3)
    assertEquals(listOf("F0", "F1", "F2", "A0", "F3", "F4", "F5"), layout(items))
  }

  @Test
  fun shortListGetsOneTailAdAndEmptyOrFullIntervalListDoesNot() {
    // 空列表：不插入任何广告
    assertEquals(0, buildSmbBrowserListItems(entries(0), SmbBrowserListMode.FILE_BROWSER, adInterval = 3).size)

    // 文件数不足一个间隔：末尾追加一个广告位
    listOf(1, 2).forEach { count ->
      val items = buildSmbBrowserListItems(entries(count), SmbBrowserListMode.FILE_BROWSER, adInterval = 3)
      assertEquals(List(count) { "F$it" } + "A0", layout(items))
    }

    // 恰好等于间隔：不触发短列表补位，常规规则下末尾也不插广告
    val full = buildSmbBrowserListItems(entries(3), SmbBrowserListMode.FILE_BROWSER, adInterval = 3)
    assertEquals(listOf("F0", "F1", "F2"), layout(full))
  }

  @Test
  fun shortListWithDefaultIntervalAppendsAdAfterLastFile() {
    // 默认间隔 10：不足 10 个文件时，广告位位于列表末尾
    val items = buildSmbBrowserListItems(entries(7), SmbBrowserListMode.FILE_BROWSER)
    assertEquals(8, items.size)
    assertTrue(items.last() is SmbBrowserListItem.NativeAdSlot)
    assertEquals(0, (items.last() as SmbBrowserListItem.NativeAdSlot).slotIndex)
  }

  @Test
  fun subtitlePickerListNeverContainsAds() {
    for (count in 0..50) {
      for (interval in 1..10) {
        val items = buildSmbBrowserListItems(entries(count), SmbBrowserListMode.SUBTITLE_PICKER, interval)
        assertEquals(count, items.size)
        assertFalse(items.any { it is SmbBrowserListItem.NativeAdSlot })
      }
    }
  }

  @Test
  fun subtitlePickerFlagMapsToAdFreeMode() {
    assertFalse(SmbBrowserListMode.of(subtitlePicker = true).allowsNativeAds)
    assertTrue(SmbBrowserListMode.of(subtitlePicker = false).allowsNativeAds)
  }

  @Test
  fun nonPositiveIntervalDisablesAds() {
    listOf(0, -1, Int.MIN_VALUE).forEach { interval ->
      val items = buildSmbBrowserListItems(entries(25), SmbBrowserListMode.FILE_BROWSER, interval)
      assertEquals(25, items.size)
      assertTrue(items.all { it is SmbBrowserListItem.FileEntry })
    }
  }

  @Test
  fun adCountAndFileOrderHoldForAllSizes() {
    for (count in 0..60) {
      for (interval in 1..12) {
        val source = entries(count)
        val items = buildSmbBrowserListItems(source, SmbBrowserListMode.FILE_BROWSER, interval)
        val ads = items.filterIsInstance<SmbBrowserListItem.NativeAdSlot>()

        val expectedAds = when {
          count == 0 -> 0
          count < interval -> 1 // 短列表：末尾补一个广告位
          else -> (count - 1) / interval
        }
        assertEquals("count=$count interval=$interval", expectedAds, ads.size)
        assertEquals((0 until expectedAds).toList(), ads.map { it.slotIndex })
        assertEquals(source, items.filterIsInstance<SmbBrowserListItem.FileEntry>().map { it.entry })

        if (items.isNotEmpty()) {
          assertTrue(items.first() is SmbBrowserListItem.FileEntry)
          if (count < interval) {
            // 短列表：末尾广告位排在最后
            assertTrue("count=$count interval=$interval", items.last() is SmbBrowserListItem.NativeAdSlot)
          } else {
            assertTrue("count=$count interval=$interval", items.last() is SmbBrowserListItem.FileEntry)
          }
        }
      }
    }
  }

  @Test
  fun keysAreUniqueAndFileKeysRemainEntryPaths() {
    val source = entries(45)
    val items = buildSmbBrowserListItems(source, SmbBrowserListMode.FILE_BROWSER, adInterval = 5)

    assertEquals(items.size, items.map { it.key }.toSet().size)
    assertEquals(
      source.map { it.path },
      items.filterIsInstance<SmbBrowserListItem.FileEntry>().map { it.key },
    )
  }

  @Test
  fun rebuildingSameEntriesKeepsAdKeysAndPositions() {
    val source = entries(23)
    val first = buildSmbBrowserListItems(source, SmbBrowserListMode.FILE_BROWSER, adInterval = 4)
    val second = buildSmbBrowserListItems(source, SmbBrowserListMode.FILE_BROWSER, adInterval = 4)
    assertEquals(first, second)
  }

  @Test
  fun contentTypesSeparateFilesFromAds() {
    val items = buildSmbBrowserListItems(entries(4), SmbBrowserListMode.FILE_BROWSER, adInterval = 2)
    assertEquals(
      listOf(
        SmbBrowserListItemType.FILE,
        SmbBrowserListItemType.FILE,
        SmbBrowserListItemType.NATIVE_AD,
        SmbBrowserListItemType.FILE,
        SmbBrowserListItemType.FILE,
      ),
      items.map { it.contentType },
    )
  }
}
