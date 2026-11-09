package live.mehiz.mpvkt.ui.remote.thumbnail

import coil3.key.Keyer
import coil3.request.Options
import java.security.MessageDigest
import live.mehiz.mpvkt.network.remote.RemoteProfile

/**
 * Coil request model describing a remote video whose frame should be rendered
 * as a thumbnail. [size] and [modifiedTime] are part of the identity so a replaced
 * file invalidates the cache.
 */
data class RemoteVideoThumbnail(
  val profile: RemoteProfile,
  val path: String,
  val size: Long,
  val modifiedTime: Long,
)

/** Makes [RemoteVideoThumbnail] results memory- and disk-cacheable. */
class RemoteVideoThumbnailKeyer : Keyer<RemoteVideoThumbnail> {
  override fun key(data: RemoteVideoThumbnail, options: Options): String =
    remoteThumbnailCacheKey(data.profile.id, data.path, data.size, data.modifiedTime)
}

/** Stable, filesystem-safe cache key over the remote file's identity. */
internal fun remoteThumbnailCacheKey(
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

internal const val CACHE_KEY_PREFIX = "remote-thumb-"
