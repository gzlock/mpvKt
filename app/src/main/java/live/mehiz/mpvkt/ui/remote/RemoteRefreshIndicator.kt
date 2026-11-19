package live.mehiz.mpvkt.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import live.mehiz.mpvkt.R

/*
 * 下拉刷新的手感参数，集中放在这里方便调整。
 */

/** 触发刷新的下拉距离：比 Material 默认的 80dp 更远，列表行较高时不易误触发。 */
internal val RemoteRefreshThreshold = 96.dp

/** 越过阈值（fraction > 1.0）即进入“松开立即刷新”；回落到该比例以下才解除，避免阈值附近抖动。 */
internal const val RemoteRefreshDisarmFraction = 0.85f

/** “正在刷新”至少可见的时长：SMB 列目录往往几十毫秒就返回，不补足的话转圈会一闪而过。 */
internal const val RemoteRefreshMinVisibleMillis = 700L

/** 刷新成功后“已刷新”确认提示的停留时长。 */
internal const val RemoteRefreshedBadgeMillis = 1100L

/** 刷新结束后继续保持“刷新中”外观的时长，覆盖指示器缩回的动画，避免文案闪回“下拉刷新”。 */
internal const val RemoteRefreshSettlingMillis = 240L

/**
 * 跟踪“是否已越过刷新阈值”，并在越过的一瞬间给出一次震动反馈。
 *
 * 用 [derivedStateOf] 把浮动的下拉进度收敛成两个布尔值，只有发生翻转时才重组/触发副作用，
 * 既不会因为拖动而每帧重组，也避免了直接对 [PullToRefreshState.distanceFraction] 做快照流的额外依赖。
 * 滞回区间（1.0 触发 / [RemoteRefreshDisarmFraction] 解除）用于防止手指在阈值附近轻微抖动时反复触发震动。
 */
@Composable
internal fun rememberRemoteRefreshArmed(
  state: PullToRefreshState,
  isRefreshing: Boolean,
): Boolean {
  var armed by remember { mutableStateOf(false) }
  val atThreshold by remember(state) { derivedStateOf { state.distanceFraction > 1f } }
  val belowHysteresis by remember(state) {
    derivedStateOf { state.distanceFraction < RemoteRefreshDisarmFraction }
  }
  val haptics = LocalHapticFeedback.current
  // 副作用不随 isRefreshing 重启（见下方注释），这里用 rememberUpdatedState 保证读到最新值
  val refreshing by rememberUpdatedState(isRefreshing)

  // 只以“是否越过阈值”为 key：刷新结束后 fraction 还会从 1.0 回落，
  // 若把 isRefreshing 也放进 key，会在刷新结束的瞬间重跑并误触发一次震动
  LaunchedEffect(atThreshold) {
    if (atThreshold && !refreshing && !armed) {
      armed = true
      haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
  }
  // 真正开始刷新后立即解除就绪态：避免刷新结束、指示器缩回的过程中闪回主色
  LaunchedEffect(isRefreshing) {
    if (isRefreshing && armed) armed = false
  }
  // 回退到解除区即恢复未就绪状态；不额外震动，让“震动”只代表“这次一定会刷新”
  LaunchedEffect(belowHysteresis) {
    if (belowHysteresis && armed) armed = false
  }
  return armed
}

/**
 * 自定义下拉刷新指示器。
 *
 * 与 Material 默认指示器相比，这里把“是否能触发刷新”做成了明确的视觉切换：
 * - 未到阈值：圆环随下拉进度填充，文案「下拉刷新」，颜色为次级文字色；
 * - 越过阈值：[armed] 为 true，整块指示器换成主色底 + 反白文案「松开立即刷新」，箭头转向上；
 * - 刷新中：固定停在阈值位置，转圈 + 「正在刷新…」。
 *
 * 位置与默认实现一致：把指示器底部对齐到下拉距离（[PullToRefreshState.distanceFraction] × 阈值），
 * 让它从内容区顶部滑出，而不是凭空淡入。
 */
@Composable
internal fun RemoteRefreshIndicator(
  state: PullToRefreshState,
  isRefreshing: Boolean,
  armed: Boolean,
  modifier: Modifier = Modifier,
) {
  // 刷新结束后 fraction 还会回落一小段，先保持“刷新中”的外观，避免缩回时闪回“下拉刷新”
  var refreshingVisual by remember { mutableStateOf(isRefreshing) }
  LaunchedEffect(isRefreshing) {
    if (isRefreshing) {
      refreshingVisual = true
    } else {
      delay(RemoteRefreshSettlingMillis)
      refreshingVisual = false
    }
  }

  // 刷新过程中不回到 armed 的主色
  val armedOnly = armed && !refreshingVisual
  val colors = MaterialTheme.colorScheme

  val containerColor by animateColorAsState(
    targetValue = if (armedOnly) colors.primary else colors.surfaceContainerHigh,
    animationSpec = tween(160),
    label = "RemoteRefreshContainerColor",
  )
  val contentColor by animateColorAsState(
    targetValue = when {
      armedOnly -> colors.onPrimary
      refreshingVisual -> colors.primary
      else -> colors.onSurfaceVariant
    },
    animationSpec = tween(160),
    label = "RemoteRefreshContentColor",
  )
  val label = when {
    refreshingVisual -> stringResource(R.string.remote_refresh_refreshing)
    armed -> stringResource(R.string.remote_refresh_release)
    else -> stringResource(R.string.remote_refresh_pull)
  }
  val arrowRotation by animateFloatAsState(
    targetValue = if (armed) 180f else 0f,
    animationSpec = tween(180),
    label = "RemoteRefreshArrow",
  )
  // 只有真正下拉或刷新时才渲染内容：避免完全隐藏的指示器仍留在无障碍语义树里
  val pulled by remember(state) { derivedStateOf { state.distanceFraction > 0f } }
  val visible = isRefreshing || pulled

  Surface(
    modifier = modifier
      // 下拉进度只在绘制/布局阶段读取，避免拖动时每帧触发组合重组
      .graphicsLayer {
        val fraction = state.distanceFraction
        translationY = fraction * RemoteRefreshThreshold.toPx() - size.height
      },
    shape = RoundedCornerShape(percent = 50),
    color = containerColor,
    contentColor = contentColor,
    shadowElevation = 3.dp,
  ) {
    if (visible) {
      Row(
        modifier = Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
          if (refreshingVisual) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              color = contentColor,
              strokeWidth = 2.dp,
            )
          } else {
            // 环形进度：0 到阈值画满一圈，配合箭头方向表达“还差多远”
            Canvas(Modifier.fillMaxSize()) {
              val strokeWidth = 2.dp.toPx()
              drawArc(
                color = contentColor,
                startAngle = -90f,
                sweepAngle = 360f * state.distanceFraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
              )
            }
            Icon(
              imageVector = Icons.Default.ArrowDownward,
              contentDescription = null,
              modifier = Modifier
                .size(14.dp)
                .rotate(arrowRotation),
            )
          }
        }
        Text(
          text = label,
          style = MaterialTheme.typography.labelLarge,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          // 部分语种（如俄语）文案较长，窄屏或大字号时允许收缩并省略，避免撑破胶囊
          modifier = Modifier.weight(1f, fill = false),
        )
      }
    }
  }
}

/**
 * 刷新成功后的短暂确认胶囊。
 *
 * 远程目录刷新完成后列表内容常常一模一样，没有这条提示时用户无法确认“到底刷新了没有”。
 * 单独做成浮层而不是复用下拉指示器：指示器在刷新结束时会回缩隐藏，无法稳定承载完成态。
 */
@Composable
internal fun RemoteRefreshedBadge(
  visible: Boolean,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible,
    modifier = modifier,
    // 延迟入场，等下拉指示器完全缩回后再出现，避免两者在顶部重叠
    enter = fadeIn(tween(180, delayMillis = 260)) +
      slideInVertically(tween(180, delayMillis = 260)) { -it / 3 },
    exit = fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 },
  ) {
    Surface(
      shape = RoundedCornerShape(percent = 50),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      shadowElevation = 3.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Default.Check,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = stringResource(R.string.remote_refreshed),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}
