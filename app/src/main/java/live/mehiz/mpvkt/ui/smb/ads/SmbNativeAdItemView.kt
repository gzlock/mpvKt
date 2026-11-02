package live.mehiz.mpvkt.ui.smb.ads

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import live.mehiz.mpvkt.R
import live.mehiz.mpvkt.network.smb.SmbEntry
import live.mehiz.mpvkt.ui.smb.SmbBrowserListItem
import live.mehiz.mpvkt.ui.smb.SmbBrowserListMode
import live.mehiz.mpvkt.ui.smb.SmbFileRow
import live.mehiz.mpvkt.ui.smb.buildSmbBrowserListItems
import kotlin.math.roundToInt

// ============================================================================
// 对外 Composable
// ============================================================================

/**
 * SMB 文件列表中的一条原生高级广告。
 *
 * AdMob 要求所有素材 View 都必须是 [NativeAdView] 的子孙 View，SDK 才能统计曝光/点击并绘制 AdChoices，
 * 因此广告本体使用传统 View 构建，再通过 [AndroidView] 嵌入 Compose；配色和字号取自当前 MaterialTheme。
 *
 * - 运行时：[nativeAd] 为空时不渲染任何内容。
 * - Compose Preview（[LocalInspectionMode] 为 true）：拿不到真实 NativeAd，而 AdMob 的 View 依赖
 *   Google Play 服务、不适合在 Layoutlib 中实例化，于是改用普通 View + [previewData] 的 Mock 素材渲染同一套布局。
 *
 * 本组件不会销毁 [nativeAd]（广告由 [SmbNativeAdViewModel] 统一持有）；
 * 离开组合时只销毁自己创建的 [NativeAdView]。
 */
@Composable
fun SmbNativeAdItemView(
  nativeAd: NativeAd?,
  modifier: Modifier = Modifier,
  previewData: SmbNativeAdPreviewData = SmbNativeAdPreviewData(),
) {
  val isPreview = LocalInspectionMode.current
  if (isPreview || nativeAd != null) {
    val style = rememberSmbNativeAdStyle()
    AndroidView(
      factory = { context -> SmbNativeAdContainer(context, isPreview) },
      modifier = modifier.fillMaxWidth(),
      update = { container ->
        container.applyStyle(style)
        if (isPreview) {
          container.bindPreview(previewData)
        } else if (nativeAd != null) {
          container.bind(nativeAd)
        }
      },
      // 未提供 onReset，View 不会被跨 item 复用；离开组合时释放 NativeAdView 的追踪/视频资源
      onRelease = { container -> container.release() },
    )
  }
}

/**
 * LazyColumn 中的一个广告位。
 *
 * 广告的请求与引用计数由调用方 remember 的 [SmbNativeAdSlotHandle] 负责，本组件只根据 [state] 渲染：
 * 广告未就绪（加载中/失败）时不占高度，加载成功后以动画展开，减少列表跳动。
 * 预览模式下直接展示 Mock 广告。
 */
@Composable
fun SmbNativeAdSlot(
  state: SmbNativeAdSlotState,
  modifier: Modifier = Modifier,
) {
  val nativeAd = (state as? SmbNativeAdSlotState.Loaded)?.nativeAd
  Box(
    modifier
      .fillMaxWidth()
      .animateContentSize(),
  ) {
    if (nativeAd != null || LocalInspectionMode.current) {
      SmbNativeAdItemView(
        nativeAd = nativeAd,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      )
    }
  }
}

/** Compose Preview 使用的静态 Mock 素材，仅在 [LocalInspectionMode] 为 true 时生效。 */
@Immutable
data class SmbNativeAdPreviewData(
  val headline: String = "示例应用 · 高清影音播放器",
  val body: String? = "这是一段用于 Compose Preview 的模拟广告描述，最多显示两行，超出的部分会以省略号结尾。",
  val callToAction: String? = "立即安装",
  val showIcon: Boolean = true,
  val mediaAspectRatio: Float = DEFAULT_MEDIA_ASPECT_RATIO,
)

// ============================================================================
// 主题样式：把 Compose 的 MaterialTheme 转换成 View 可用的颜色 / 字号
// ============================================================================

@Immutable
private data class SmbNativeAdStyle(
  @ColorInt val containerColor: Int,
  @ColorInt val outlineColor: Int,
  @ColorInt val headlineColor: Int,
  @ColorInt val bodyColor: Int,
  @ColorInt val badgeContainerColor: Int,
  @ColorInt val badgeContentColor: Int,
  @ColorInt val mediaPlaceholderColor: Int,
  @ColorInt val iconPlaceholderColor: Int,
  @ColorInt val ctaContainerColor: Int,
  @ColorInt val ctaContentColor: Int,
  val headlineTextSizeSp: Float,
  val bodyTextSizeSp: Float,
  val badgeTextSizeSp: Float,
  val ctaTextSizeSp: Float,
  val badgeText: String,
)

@Composable
private fun rememberSmbNativeAdStyle(): SmbNativeAdStyle {
  val colors = MaterialTheme.colorScheme
  val typography = MaterialTheme.typography
  val badgeText = stringResource(R.string.smb_native_ad_badge)
  return remember(colors, typography, badgeText) {
    SmbNativeAdStyle(
      containerColor = colors.surfaceContainerLow.toArgb(),
      outlineColor = colors.outlineVariant.toArgb(),
      headlineColor = colors.onSurface.toArgb(),
      bodyColor = colors.onSurfaceVariant.toArgb(),
      badgeContainerColor = colors.tertiaryContainer.toArgb(),
      badgeContentColor = colors.onTertiaryContainer.toArgb(),
      mediaPlaceholderColor = colors.surfaceVariant.toArgb(),
      iconPlaceholderColor = colors.primaryContainer.toArgb(),
      ctaContainerColor = colors.primary.toArgb(),
      ctaContentColor = colors.onPrimary.toArgb(),
      headlineTextSizeSp = typography.titleMedium.fontSize.spOr(16f),
      bodyTextSizeSp = typography.bodySmall.fontSize.spOr(12f),
      badgeTextSizeSp = typography.labelSmall.fontSize.spOr(11f),
      ctaTextSizeSp = typography.labelLarge.fontSize.spOr(14f),
      badgeText = badgeText,
    )
  }
}

private fun TextUnit.spOr(fallback: Float): Float = if (type == TextUnitType.Sp) value else fallback

// ============================================================================
// View 层实现
// ============================================================================

/**
 * 原生广告的 View 布局：
 *
 * ```
 * ┌───────────────────────────────────────────┐
 * │ [图标]  [广告] 主标题 Headline       (AdChoices)│
 * │         描述 Body（最多两行）                 │
 * │ ┌───────────────────────────────────────┐ │
 * │ │         媒体 MediaView（图片/视频）        │ │
 * │ └───────────────────────────────────────┘ │
 * │                              [ CTA 按钮 ] │
 * └───────────────────────────────────────────┘
 * ```
 *
 * @param isPreview 为 true 时不创建任何 AdMob 类的实例（NativeAdView / MediaView），
 *   改用 FrameLayout / ImageView 占位，保证 Layoutlib 能正常渲染。
 */
@SuppressLint("ViewConstructor")
private class SmbNativeAdContainer(
  context: Context,
  private val isPreview: Boolean,
) : FrameLayout(context) {

  /** 广告根布局：运行时为 NativeAdView（负责曝光/点击统计和 AdChoices），预览时为普通 FrameLayout。 */
  private val adRoot: FrameLayout = if (isPreview) FrameLayout(context) else NativeAdView(context)
  private val card = LinearLayout(context)
  private val iconView = ImageView(context)
  private val badgeView = TextView(context)
  private val headlineView = TextView(context)
  private val bodyView = TextView(context)
  private val mediaFrame = AspectRatioFrameLayout(context)

  /** 媒体素材：运行时为 MediaView（主图或视频），预览时为 ImageView 占位图。 */
  private val mediaView: View = if (isPreview) ImageView(context) else MediaView(context)
  private val callToActionView = CallToActionView(context)

  private var boundAd: NativeAd? = null
  private var appliedStyle: SmbNativeAdStyle? = null

  init {
    val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    iconView.apply {
      scaleType = ImageView.ScaleType.FIT_CENTER
      outlineProvider = RoundedOutlineProvider(dpToPx(8).toFloat())
      clipToOutline = true
    }
    // “广告”角标：AdMob 政策要求清晰标注，且不小于 15px × 15px
    badgeView.apply {
      typeface = mediumTypeface
      includeFontPadding = false
      gravity = Gravity.CENTER
      maxLines = 1
      setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
    }
    headlineView.apply {
      typeface = mediumTypeface
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
    }
    bodyView.apply {
      maxLines = 2
      ellipsize = TextUtils.TruncateAt.END
    }
    callToActionView.apply {
      typeface = mediumTypeface
      gravity = Gravity.CENTER
      maxLines = 1
      ellipsize = TextUtils.TruncateAt.END
      minWidth = dpToPx(72)
      setPadding(dpToPx(16), 0, dpToPx(16), 0)
    }
    mediaFrame.apply {
      // 视频广告要求 MediaView 每个方向都不小于 120dp，否则不会投放视频素材
      minimumMediaHeightPx = dpToPx(MEDIA_MIN_SIZE_DP)
      outlineProvider = RoundedOutlineProvider(dpToPx(8).toFloat())
      clipToOutline = true
    }
    if (isPreview) {
      (mediaView as ImageView).scaleType = ImageView.ScaleType.FIT_CENTER
      mediaFrame.addView(mediaView, FrameLayout.LayoutParams(dpToPx(48), dpToPx(48), Gravity.CENTER))
    } else {
      // FIT_CENTER：等比缩放、不裁剪不拉伸素材
      (mediaView as MediaView).setImageScaleType(ImageView.ScaleType.FIT_CENTER)
      mediaFrame.addView(mediaView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // ---------- 组装布局 ----------
    val titleRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(badgeView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
      addView(
        headlineView,
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dpToPx(6) },
      )
    }
    val textColumn = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      addView(titleRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
      addView(
        bodyView,
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpToPx(4) },
      )
    }
    val header = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      // SDK 会把 AdChoices 图标叠加在 NativeAdView 的右上角（ADCHOICES_TOP_RIGHT 是绝对方向），
      // 这里在右侧留白避免遮挡标题；用绝对 right 而非 end，RTL 布局下同样有效
      setPadding(0, 0, dpToPx(ADCHOICES_RESERVED_DP), 0)
      addView(iconView, LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply { marginEnd = dpToPx(12) })
      addView(textColumn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }
    card.apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
      addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
      addView(
        mediaFrame,
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dpToPx(10) },
      )
      addView(
        callToActionView,
        LinearLayout.LayoutParams(WRAP_CONTENT, dpToPx(36)).apply {
          topMargin = dpToPx(10)
          gravity = Gravity.END
        },
      )
    }
    adRoot.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    addView(adRoot, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

    // ---------- 注册素材 View：必须在 setNativeAd() 之前完成 ----------
    if (!isPreview) {
      val nativeAdView = adRoot as NativeAdView
      nativeAdView.iconView = iconView
      nativeAdView.headlineView = headlineView
      nativeAdView.bodyView = bodyView
      nativeAdView.mediaView = mediaView as MediaView
      nativeAdView.callToActionView = callToActionView
    }
  }

  /** 应用主题配色与字号（只在样式变化时重新设置）。 */
  fun applyStyle(style: SmbNativeAdStyle) {
    if (style == appliedStyle) return
    appliedStyle = style

    card.background = roundedRect(style.containerColor, dpToPx(12)).apply {
      setStroke(dpToPx(1), style.outlineColor)
    }
    badgeView.apply {
      text = style.badgeText
      setTextColor(style.badgeContentColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, style.badgeTextSizeSp)
      background = roundedRect(style.badgeContainerColor, dpToPx(4))
    }
    headlineView.apply {
      setTextColor(style.headlineColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, style.headlineTextSizeSp)
    }
    bodyView.apply {
      setTextColor(style.bodyColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, style.bodyTextSizeSp)
    }
    mediaFrame.background = roundedRect(style.mediaPlaceholderColor, dpToPx(8))
    callToActionView.apply {
      setTextColor(style.ctaContentColor)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, style.ctaTextSizeSp)
      background = RippleDrawable(
        ColorStateList.valueOf(style.ctaContentColor.withAlpha(RIPPLE_ALPHA)),
        roundedRect(style.ctaContainerColor, dpToPx(18)),
        null,
      )
    }
    if (isPreview) {
      iconView.background = roundedRect(style.iconPlaceholderColor, dpToPx(8))
      (mediaView as ImageView).imageTintList = ColorStateList.valueOf(style.bodyColor)
    }
  }

  /** 绑定真实广告。同一个广告对象只绑定一次。 */
  fun bind(nativeAd: NativeAd) {
    if (nativeAd === boundAd) return
    boundAd = nativeAd

    // 主标题：AdMob 保证每条原生广告都有 headline
    headlineView.text = nativeAd.headline.orEmpty()
    // 描述、CTA、图标是可选素材：缺失时隐藏对应 View，避免留白
    bodyView.showOptionalText(nativeAd.body)
    callToActionView.showOptionalText(nativeAd.callToAction)
    val icon = nativeAd.icon?.drawable
    iconView.setImageDrawable(icon)
    iconView.visibility = if (icon != null) View.VISIBLE else View.GONE

    val mediaContent = nativeAd.mediaContent
    if (mediaContent != null) {
      mediaFrame.aspectRatio = mediaContent.aspectRatio.toMediaAspectRatio()
      (mediaView as MediaView).mediaContent = mediaContent
      mediaFrame.visibility = View.VISIBLE
    } else {
      mediaFrame.visibility = View.GONE
    }

    // 最后一步：把广告对象交给 NativeAdView，SDK 据此注册点击/曝光追踪并渲染 AdChoices
    (adRoot as NativeAdView).setNativeAd(nativeAd)
  }

  /** 预览模式：用 Mock 数据填充同一套布局。 */
  fun bindPreview(data: SmbNativeAdPreviewData) {
    headlineView.text = data.headline
    bodyView.showOptionalText(data.body)
    callToActionView.showOptionalText(data.callToAction)
    iconView.setImageResource(R.drawable.ic_launcher_foreground)
    iconView.visibility = if (data.showIcon) View.VISIBLE else View.GONE
    mediaFrame.aspectRatio = data.mediaAspectRatio.toMediaAspectRatio()
    (mediaView as ImageView).setImageResource(R.drawable.baseline_play_arrow_24)
  }

  /** AndroidView 的 onRelease 中调用：销毁 NativeAdView 自身（NativeAd 由 ViewModel 负责销毁）。 */
  fun release() {
    boundAd = null
    if (!isPreview) (adRoot as NativeAdView).destroy()
  }
}

/** 按宽高比确定高度的容器，用于承载 MediaView。 */
private class AspectRatioFrameLayout(context: Context) : FrameLayout(context) {
  /** 宽 / 高。 */
  var aspectRatio: Float = DEFAULT_MEDIA_ASPECT_RATIO
    set(value) {
      if (field != value) {
        field = value
        requestLayout()
      }
    }

  var minimumMediaHeightPx: Int = 0

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val width = View.MeasureSpec.getSize(widthMeasureSpec)
    val height = maxOf((width / aspectRatio).roundToInt(), minimumMediaHeightPx)
    super.onMeasure(
      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
    )
  }
}

/** CTA 按钮：用 TextView 实现以完全控制样式，无障碍服务中按“按钮”朗读。 */
@SuppressLint("AppCompatCustomView")
private class CallToActionView(context: Context) : TextView(context) {
  override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

private class RoundedOutlineProvider(private val radiusPx: Float) : ViewOutlineProvider() {
  override fun getOutline(view: View, outline: Outline) {
    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
  }
}

private fun TextView.showOptionalText(value: String?) {
  text = value.orEmpty()
  visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
}

private fun roundedRect(@ColorInt color: Int, radiusPx: Int) = GradientDrawable().apply {
  shape = GradientDrawable.RECTANGLE
  cornerRadius = radiusPx.toFloat()
  setColor(color)
}

private fun View.dpToPx(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

private fun Int.withAlpha(alpha: Int): Int = (this and 0x00FFFFFF) or (alpha shl 24)

/** 素材宽高比可能缺失或异常：回退到 16:9，并限制在 [1, 2] 之间，避免列表项过高或过扁。 */
private fun Float.toMediaAspectRatio(): Float =
  if (isNaN() || this <= 0f) DEFAULT_MEDIA_ASPECT_RATIO else coerceIn(MIN_MEDIA_ASPECT_RATIO, MAX_MEDIA_ASPECT_RATIO)

private const val DEFAULT_MEDIA_ASPECT_RATIO = 16f / 9f
private const val MIN_MEDIA_ASPECT_RATIO = 1f
private const val MAX_MEDIA_ASPECT_RATIO = 2f
private const val MEDIA_MIN_SIZE_DP = 120
private const val ADCHOICES_RESERVED_DP = 20
private const val RIPPLE_ALPHA = 0x3D

// ============================================================================
// Compose Preview
// ============================================================================

@Preview(name = "原生广告 - 浅色", showBackground = true, widthDp = 360)
@Composable
private fun PreviewSmbNativeAdItemView() {
  MaterialTheme {
    Surface {
      SmbNativeAdItemView(
        nativeAd = null,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      )
    }
  }
}

@Preview(
  name = "原生广告 - 深色",
  showBackground = true,
  widthDp = 360,
  uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewSmbNativeAdItemViewDark() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface {
      SmbNativeAdItemView(
        nativeAd = null,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      )
    }
  }
}

@Preview(name = "原生广告 - 缺少可选素材 / 超长标题", showBackground = true, widthDp = 360)
@Composable
private fun PreviewSmbNativeAdItemViewMinimal() {
  MaterialTheme {
    Surface {
      Column {
        SmbNativeAdItemView(
          nativeAd = null,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          previewData = SmbNativeAdPreviewData(
            headline = "一个非常非常长的广告主标题，用来检查单行省略号与 AdChoices 预留空间是否正常",
            body = null,
            callToAction = null,
            showIcon = false,
            mediaAspectRatio = 1.91f,
          ),
        )
        SmbNativeAdItemView(
          nativeAd = null,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          previewData = SmbNativeAdPreviewData(callToAction = "Learn more", mediaAspectRatio = 1f),
        )
      }
    }
  }
}

/** 混排效果：每 3 个文件插入 1 个广告位（与 SmbBrowserScreen 中 LazyColumn 的渲染方式一致）。 */
@Preview(name = "SMB 列表混排", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun PreviewSmbBrowserListWithNativeAds() {
  fun folder(name: String) = SmbEntry(name = name, path = name, directory = true)
  fun video(name: String) = SmbEntry(
    name = name,
    path = name,
    directory = false,
    size = 734_003_200L,
    modifiedTime = 1_735_660_800_000L,
  )
  val entries = listOf(
    folder("纪录片"),
    folder("电影"),
    video("第01集.mkv"),
    video("第02集.mkv"),
    video("第03集.mkv"),
    video("第04集.mkv"),
    video("第05集.mkv"),
  )
  val listItems = buildSmbBrowserListItems(entries, SmbBrowserListMode.FILE_BROWSER, adInterval = 3)
  MaterialTheme {
    Surface {
      LazyColumn {
        items(items = listItems, key = { it.key }, contentType = { it.contentType }) { item ->
          when (item) {
            is SmbBrowserListItem.FileEntry -> SmbFileRow(entry = item.entry, onClick = {})
            // 预览模式下广告位直接渲染 Mock 广告
            is SmbBrowserListItem.NativeAdSlot -> SmbNativeAdSlot(state = SmbNativeAdSlotState.Idle)
          }
        }
      }
    }
  }
}
