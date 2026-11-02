package live.mehiz.mpvkt.ui.player.controls

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import live.mehiz.mpvkt.ui.player.controls.components.ControlsButton

@Composable
fun BottomRightPlayerControls(
  isPipAvailable: Boolean,
  isSingleVideoLoop: Boolean,
  onLoopClick: () -> Unit,
  onAspectClick: () -> Unit,
  onPipClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier) {
    ControlsButton(
      if (isSingleVideoLoop) Icons.Default.RepeatOneOn else Icons.Default.Repeat,
      onClick = onLoopClick,
    )

    if (isPipAvailable) {
      ControlsButton(
        Icons.Default.PictureInPictureAlt,
        onClick = onPipClick,
      )
    }

    ControlsButton(
      Icons.Default.AspectRatio,
      onClick = onAspectClick,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun BottomRightPlayerControlsPreview() {
  ControlsPreviewSurface {
    BottomRightPlayerControls(
      isPipAvailable = true,
      isSingleVideoLoop = false,
      onLoopClick = {},
      onAspectClick = {},
      onPipClick = {},
    )
  }
}
