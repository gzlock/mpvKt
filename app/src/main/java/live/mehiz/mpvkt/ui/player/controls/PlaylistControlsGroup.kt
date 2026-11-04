package live.mehiz.mpvkt.ui.player.controls

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import live.mehiz.mpvkt.ui.player.controls.components.ControlsButton

@Composable
fun PlaylistControlsGroup(
  playlistSize: Int,
  playlistPosition: Int,
  isSingleVideoLoop: Boolean,
  onPrevClick: () -> Unit,
  onNextClick: () -> Unit,
  onLoopClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val canGoPrev = playlistPosition > 0
  val canGoNext = playlistPosition < playlistSize - 1

  Row(modifier) {
    ControlsButton(
      icon = Icons.Default.SkipPrevious,
      onClick = { if (canGoPrev) onPrevClick() },
      modifier = Modifier.alpha(if (canGoPrev) 1f else 0.3f),
    )

    ControlsButton(
      icon = if (isSingleVideoLoop) Icons.Default.RepeatOneOn else Icons.Default.Repeat,
      onClick = onLoopClick,
    )

    ControlsButton(
      icon = Icons.Default.SkipNext,
      onClick = { if (canGoNext) onNextClick() },
      modifier = Modifier.alpha(if (canGoNext) 1f else 0.3f),
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun PlaylistControlsGroupPreview() {
  ControlsPreviewSurface {
    PlaylistControlsGroup(
      playlistSize = 10,
      playlistPosition = 3,
      isSingleVideoLoop = false,
      onPrevClick = {},
      onNextClick = {},
      onLoopClick = {},
    )
  }
}
