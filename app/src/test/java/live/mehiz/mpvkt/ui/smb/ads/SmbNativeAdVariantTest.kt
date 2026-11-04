package live.mehiz.mpvkt.ui.smb.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbNativeAdVariantTest {

  @Test
  fun firstNineSlotsOfEachCycleAreSmallAndTenthIsLarge() {
    val variants = (0 until 10).map { resolveSmbNativeAdVariant(it) }
    assertEquals(
      List(9) { SmbNativeAdVariant.SMALL } + SmbNativeAdVariant.LARGE,
      variants,
    )
  }

  @Test
  fun patternRepeatsAcrossCycles() {
    // 第二个周期（10..19）与第一个周期（0..9）的变体序列完全一致
    assertEquals(
      (0 until 10).map { resolveSmbNativeAdVariant(it) },
      (10 until 20).map { resolveSmbNativeAdVariant(it) },
    )
    assertEquals(SmbNativeAdVariant.LARGE, resolveSmbNativeAdVariant(20))
    assertEquals(SmbNativeAdVariant.SMALL, resolveSmbNativeAdVariant(21))
  }

  @Test
  fun configuredRatioIsValid() {
    // 配置合法性：小广告数量必须为正且小于周期大小，否则分配逻辑会失效
    assertTrue(SMB_SMALL_AD_SLOTS_PER_CYCLE >= 1)
    assertTrue(SMB_SMALL_AD_SLOTS_PER_CYCLE < SMB_AD_VARIANT_CYCLE_SIZE)
  }
}
