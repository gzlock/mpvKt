package live.mehiz.mpvkt.ui.smb.thumbnail

import coil3.key.Keyer
import coil3.request.Options
import java.security.MessageDigest
import live.mehiz.mpvkt.network.smb.SmbProfile

/**
 * Coil request model describing a remote SMB video whose frame should be rendered
 * as a thumbnail. [size] and [modifiedTime] are part of the identity so a replaced
 * file invalidates the cache.
 */
data class SmbVideoThumbnail(
  val profile: SmbProfile,
  val path: String,
  val size: Long,
  val modifiedTime: Long,
)

/** Makes [SmbVideoThumbnail] results memory- and disk-cacheable. */
class SmbVideoThumbnailKeyer : Keyer<SmbVideoThumbnail> {
  override fun key(data: SmbVideoThumbnail, options: Options): String =
    smbThumbnailCacheKey(data.profile.id, data.path, data.size, data.modifiedTime)
}

/** Stable, filesystem-safe cache key over the remote file's identity. */
internal fun smbThumbnailCacheKey(
  profileId: String,
  path: String,
  size: Long,
  modifiedTime: Long,
): String {
  val raw = "$profileId|$path|$size|$modifiedTime"
  val digest = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8))
  return CACHE_KEY_PREFIX + digest.joinToString("") { "%02x".format(it) }
}

internal const val CACHE_KEY_PREFIX = "smb-thumb-"
