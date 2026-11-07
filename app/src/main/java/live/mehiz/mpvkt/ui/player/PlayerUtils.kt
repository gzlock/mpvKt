package live.mehiz.mpvkt.ui.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import live.mehiz.mpvkt.ui.utils.NaturalSort

internal fun Uri.openContentFd(context: Context): String? {
  return context.contentResolver.openFileDescriptor(this, "r")?.detachFd()?.let {
    Utils.findRealPath(it)?.also { _ ->
      ParcelFileDescriptor.adoptFd(it).close()
    } ?: "fd://$it"
  }
}

internal fun Uri.resolveUri(context: Context): String? {
  val filepath = when (scheme) {
    "file" -> path
    "content" -> openContentFd(context)
    "data" -> "data://$schemeSpecificPart"
    in Utils.PROTOCOLS -> toString()
    else -> null
  }

  if (filepath == null) Log.e(TAG, "unknown scheme: $scheme")
  return filepath
}

internal val videoExtensions = listOf(
  "264", "265", "3g2", "3ga", "3gp", "3gp2", "3gpp", "3gpp2", "3iv", "amr", "asf",
  "asx", "av1", "avc", "avf", "avi", "bdm", "bdmv", "clpi", "cpi", "divx", "dv", "evo",
  "evob", "f4v", "flc", "fli", "flic", "flv", "gxf", "h264", "h265", "hdmov", "hdv",
  "hevc", "lrv", "m1u", "m1v", "m2t", "m2ts", "m2v", "m4u", "m4v", "mkv", "mod", "moov",
  "mov", "mp2", "mp2v", "mp4", "mp4v", "mpe", "mpeg", "mpeg2", "mpeg4", "mpg", "mpg4",
  "mpl", "mpls", "mpv", "mpv2", "mts", "mtv", "mxf", "mxu", "nsv", "nut", "ogg", "ogm",
  "ogv", "ogx", "qt", "qtvr", "rm", "rmj", "rmm", "rms", "rmvb", "rmx", "rv", "rvx",
  "sdp", "tod", "trp", "ts", "tsa", "tsv", "tts", "vc1", "vfw", "vob", "vro", "webm",
  "wm", "wmv", "wmx", "x264", "x265", "xvid", "y4m", "yuv",
)

internal val audioExtensions = listOf(
  "3ga", "3ga2", "a52", "aac", "ac3", "adt", "adts", "aif", "aifc", "aiff", "alac",
  "amr", "ape", "au", "awb", "dsf", "dts", "dts-hd", "dtshd", "eac3", "f4a", "flac",
  "lpcm", "m1a", "m2a", "m4a", "mk3d", "mka", "mlp", "mp+", "mp1", "mp2", "mp3", "mpa",
  "mpc", "mpga", "mpp", "oga", "ogg", "opus", "pcm", "ra", "ram", "rax", "shn", "snd",
  "spx", "tak", "thd", "thd+ac3", "true-hd", "truehd", "tta", "wav", "weba", "wma", "wv",
  "wvp",
)

internal val imageExtensions = listOf(
  "apng", "bmp", "exr", "gif", "j2c", "j2k", "jfif", "jp2", "jpc", "jpe", "jpeg", "jpg",
  "jpg2", "png", "tga", "tif", "tiff", "webp",
)

/** 外部字幕文件扩展名，本地文件浏览器与 SMB 浏览器共用。 */
internal val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa", "sub")

/** 取文件名的小写扩展名；无扩展名时返回空字符串。 */
private fun String.lowercaseExtension(): String = substringAfterLast('.', "").lowercase()

/**
 * 计算同级目录中应随当前视频自动加载的字幕文件名。
 *
 * 规则：
 * - 目录中恰好只有一个视频时，返回全部字幕，不要求文件名与视频匹配；
 * - 存在多个视频时，仅返回与 [currentVideoName] 同名前缀的字幕，沿用历史匹配规则；
 * - 以“.”开头的隐藏文件始终忽略，扩展名大小写不敏感；
 * - 结果按自然排序（[NaturalSort]）排列，便于第一条作为默认选中项。
 *
 * 纯字符串逻辑、不依赖文件系统，本地与 SMB 两条链路共用，便于单元测试。
 */
internal fun resolveAutoSubtitles(
  fileNames: Collection<String>,
  currentVideoName: String,
): List<String> {
  val visibleNames = fileNames.filterNot { it.startsWith('.') }
  val videoCount = visibleNames.count { it.lowercaseExtension() in videoExtensions }
  if (videoCount == 0) return emptyList()
  val subtitles = visibleNames.filter { it.lowercaseExtension() in subtitleExtensions }
  if (subtitles.isEmpty()) return emptyList()

  if (videoCount == 1) return subtitles.sortedWith(NaturalSort.comparator)

  // 多视频目录：只加载与当前视频同名前缀的字幕；当前名为空时不做模糊匹配，避免误加载全部字幕。
  val videoNameWithoutExt = currentVideoName.substringBeforeLast('.')
  if (videoNameWithoutExt.isBlank()) return emptyList()
  return subtitles
    .filter { it.substringBeforeLast('.').startsWith(videoNameWithoutExt) }
    .sortedWith(NaturalSort.comparator)
}

/**
 * Derives a display name from a media source string.
 *
 * Drops the query/fragment, decodes percent-encoded segments and keeps the last path segment, so
 * `http://host/a/c.mp4?token=1` resolves to `c.mp4` and `/sdcard/a/c.mkv` to `c.mkv`.
 * Returns null when nothing usable can be derived.
 */
internal fun mediaDisplayName(source: String?): String? {
  if (source.isNullOrBlank()) return null
  val path = source.substringBefore('?').substringBefore('#').trimEnd('/')
  val segment = path.substringAfterLast('/')
  if (segment.isBlank()) return null
  return decodeUrlSegment(segment).ifBlank { null }
}

private fun decodeUrlSegment(segment: String): String {
  if ('%' !in segment) return segment
  // URLDecoder maps "+" to a space, but a literal "+" in a path is not form-encoded data.
  return runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
}

inline fun <reified T> MPVNode.toObject(json: Json): T = json.decodeFromString<T>(toJson())
