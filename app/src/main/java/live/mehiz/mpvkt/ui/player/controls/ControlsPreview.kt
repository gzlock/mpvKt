package live.mehiz.mpvkt.ui.player.controls

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun ControlsPreviewSurface(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface(
      color = Color(0xFF101216),
      content = content,
    )
  }
}
