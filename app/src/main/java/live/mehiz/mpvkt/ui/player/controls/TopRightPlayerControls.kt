package live.mehiz.mpvkt.ui.player.controls

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import live.mehiz.mpvkt.ui.player.Decoder
import live.mehiz.mpvkt.ui.player.controls.components.ControlsButton

@Composable
fun TopRightPlayerControls(
  // decoder
  decoder: Decoder,
  onDecoderClick: () -> Unit,
  onDecoderLongClick: () -> Unit,
  // chapters
  isChaptersVisible: Boolean,
  onChaptersClick: () -> Unit,
  // subtitles
  onSubtitlesClick: () -> Unit,
  onSubtitlesLongClick: () -> Unit,
  // audio
  onAudioClick: () -> Unit,
  onAudioLongClick: () -> Unit,
  // more
  onMoreClick: () -> Unit,
  onMoreLongClick: () -> Unit,
  onPlaylistClick: () -> Unit,

  modifier: Modifier = Modifier
) {
  Row(
    modifier,
    verticalAlignment = Alignment.CenterVertically
  ) {
    ControlsButton(
      decoder.title,
      onClick = onDecoderClick,
      onLongClick = onDecoderLongClick,
    )
    if (isChaptersVisible) {
      ControlsButton(
        Icons.Default.Bookmarks,
        onClick = onChaptersClick,
      )
    }
    ControlsButton(
      Icons.Default.Subtitles,
      onClick = onSubtitlesClick,
      onLongClick = onSubtitlesLongClick,
    )
    ControlsButton(
      Icons.Default.Audiotrack,
      onClick = onAudioClick,
      onLongClick = onAudioLongClick,
    )
    ControlsButton(
      Icons.AutoMirrored.Filled.PlaylistPlay,
      onClick = onPlaylistClick,
    )
    ControlsButton(
      Icons.Default.MoreVert,
      onClick = onMoreClick,
      onLongClick = onMoreLongClick,
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun TopRightPlayerControlsPreview() {
  ControlsPreviewSurface {
    TopRightPlayerControls(
      decoder = Decoder.Auto,
      onDecoderClick = {},
      onDecoderLongClick = {},
      isChaptersVisible = true,
      onChaptersClick = {},
      onSubtitlesClick = {},
      onSubtitlesLongClick = {},
      onAudioClick = {},
      onAudioLongClick = {},
      onMoreClick = {},
      onMoreLongClick = {},
      onPlaylistClick = {},
    )
  }
}
