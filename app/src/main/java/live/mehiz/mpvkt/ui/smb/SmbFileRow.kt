package live.mehiz.mpvkt.ui.smb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import live.mehiz.mpvkt.network.smb.SmbEntry
import live.mehiz.mpvkt.network.smb.SmbProfile
import live.mehiz.mpvkt.ui.smb.thumbnail.SmbVideoThumbnail
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A single SMB browser row.
 *
 * The row is stateless: it renders [entry] and delegates all interaction to [onClick].
 * When [thumbnailModel] is non-null its video frame is shown as the leading image;
 * otherwise a generic movie icon is used. Directories always show a folder icon.
 */
@Composable
fun SmbFileRow(
  entry: SmbEntry,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  thumbnailModel: SmbVideoThumbnail? = null,
) {
  val moviePainter = rememberVectorPainter(Icons.Default.Movie)
  Row(
    modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    when {
      entry.directory -> {
        Box(
          Modifier.size(width = 48.dp, height = 54.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Default.Folder, null)
        }
      }
      thumbnailModel == null -> {
        Box(
          Modifier.size(96.dp, 54.dp),
          contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Default.Movie, null)
        }
      }
      else -> {
        AsyncImage(
          model = thumbnailModel,
          contentDescription = null,
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .size(96.dp, 54.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
          placeholder = moviePainter,
          error = moviePainter,
          fallback = moviePainter,
        )
      }
    }
    Column(Modifier.padding(start = 12.dp).weight(1f)) {
      Text(
        text = entry.name,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyLarge,
      )
      if (!entry.directory) {
        Text(
          text = buildFileMeta(entry.size, entry.modifiedTime),
          maxLines = 1,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun buildFileMeta(size: Long, modifiedTime: Long): String {
  return listOf(formatFileSize(size), formatDate(modifiedTime))
    .filter { it.isNotEmpty() }
    .joinToString(" · ")
}

private fun formatFileSize(bytes: Long): String {
  if (bytes <= 0L) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  var value = bytes.toDouble()
  var unitIndex = 0
  while (value >= 1024.0 && unitIndex < units.lastIndex) {
    value /= 1024.0
    unitIndex++
  }
  return if (unitIndex == 0) {
    "$bytes B"
  } else {
    String.format(Locale.US, "%.1f %s", value, units[unitIndex])
  }
}

private fun formatDate(timeMillis: Long): String {
  if (timeMillis <= 0L) return ""
  return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMillis))
}

@Preview(showBackground = true)
@Composable
private fun PreviewSmbFileRowCatalog() {
  MaterialTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
      Column {
        SmbFileRow(
          entry = SmbEntry(name = "纪录片", path = "/纪录片", directory = true),
          onClick = {},
        )
        SmbFileRow(
          entry = SmbEntry(
            name = "【01秒看新闻】蔡天凤案_01.mp4",
            path = "/video/01.mp4",
            directory = false,
            size = 134_217_728L,
            modifiedTime = 1_735_660_800_000L,
          ),
          onClick = {},
          thumbnailModel = SmbVideoThumbnail(
            profile = SmbProfile(name = "家庭NAS", host = "192.168.1.10", share = "download"),
            path = "/video/01.mp4",
            size = 134_217_728L,
            modifiedTime = 1_735_660_800_000L,
          ),
        )
        SmbFileRow(
          entry = SmbEntry(
            name = "说明文档.txt",
            path = "/note.txt",
            directory = false,
            size = 2_048L,
            modifiedTime = 1_700_000_000_000L,
          ),
          onClick = {},
        )
        SmbFileRow(
          entry = SmbEntry(
            name = "这是一个文件名非常非常长用来测试单行省略效果的视频文件示例.mp4",
            path = "/long.mp4",
            directory = false,
            size = 1_073_741_824L,
            modifiedTime = 1_730_000_000_000L,
          ),
          onClick = {},
          thumbnailModel = SmbVideoThumbnail(
            profile = SmbProfile(name = "家庭NAS", host = "192.168.2.2", share = "download"),
            path = "/long.mp4",
            size = 1_073_741_824L,
            modifiedTime = 1_730_000_000_000L,
          ),
        )
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSmbDirectoryRow() {
  MaterialTheme {
    SmbFileRow(
      entry = SmbEntry(name = "上级目录", path = "/..", directory = true),
      onClick = {},
    )
  }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSmbGenericFileRow() {
  MaterialTheme {
    SmbFileRow(
      entry = SmbEntry(
        name = "字幕文件.srt",
        path = "/sub.srt",
        directory = false,
        size = 40_960L,
        modifiedTime = 1_710_000_000_000L,
      ),
      onClick = {},
    )
  }
}
