package live.mehiz.mpvkt.ui.player.controls.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import live.mehiz.mpvkt.R
import live.mehiz.mpvkt.ui.player.TrackNode
import live.mehiz.mpvkt.ui.theme.spacing
import me.zhanghai.compose.preference.FooterPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals

@Composable
fun SubtitlesSheet(
  tracks: ImmutableList<TrackNode>,
  onSelect: (Int) -> Unit,
  onAddSubtitle: () -> Unit,
  onPickSmbSubtitle: () -> Unit,
  onOpenSubtitleSettings: () -> Unit,
  onOpenSubtitleDelay: () -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  GenericTracksSheet(
    tracks,
    onDismissRequest = onDismissRequest,
    header = {
      AddTrackRow(
        stringResource(R.string.player_sheets_add_ext_sub),
        onAddSubtitle,
        actions = {
          IconButton(onClick = onOpenSubtitleSettings) {
            Icon(Icons.Default.Palette, null)
          }
          IconButton(onClick = onOpenSubtitleDelay) {
            Icon(Icons.Default.MoreTime, null)
          }
        },
      )
      AddTrackRow(
        stringResource(R.string.player_sheets_add_smb_sub),
        onPickSmbSubtitle,
      )
    },
    track = { track ->
      SubtitleTrackRow(
        title = getTrackTitle(track),
        selected = track.mainSelection?.toInt() ?: -1,
        onClick = { onSelect(track.id) },
      )
    },
    footer = {
      ProvidePreferenceLocals {
        FooterPreference(
          summary = {
            Text(stringResource(R.string.player_sheets_subtitles_footer_secondary_sid_no_styles))
          },
        )
      }
    },
    modifier = modifier,
  )
}

@Composable
fun SubtitleTrackRow(
  title: String,
  selected: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(start = MaterialTheme.spacing.smaller, end = MaterialTheme.spacing.medium),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
      selected > -1,
      onCheckedChange = { _ -> onClick() },
    )
    Text(
      title,
      fontStyle = if (selected > -1) FontStyle.Italic else FontStyle.Normal,
      fontWeight = if (selected > -1) FontWeight.ExtraBold else FontWeight.Normal,
    )
    Spacer(modifier = Modifier.weight(1f))
    if (selected != -1) {
      Text(
        "#${selected + 1}",
        fontStyle = if (selected > -1) FontStyle.Italic else FontStyle.Normal,
        fontWeight = if (selected > -1) FontWeight.ExtraBold else FontWeight.Normal,
      )
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun SubtitleTrackRowPreview() {
  SubtitleTrackRow(
    title = "English · Signs & Songs",
    selected = 0,
    onClick = {},
  )
}

@Preview(showBackground = true)
@Composable
private fun SubtitlesSheetPreview() {
  SubtitlesSheet(
    tracks = persistentListOf(
      TrackNode(id = 1, type = "sub", title = "English", lang = "en", selected = true, mainSelection = 0),
      TrackNode(id = 2, type = "sub", title = "Signs & Songs", lang = "ja"),
    ),
    onSelect = {},
    onAddSubtitle = {},
    onPickSmbSubtitle = {},
    onOpenSubtitleSettings = {},
    onOpenSubtitleDelay = {},
    onDismissRequest = {},
  )
}
