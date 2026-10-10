package live.mehiz.mpvkt.network.smb

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SmbProfile(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val host: String,
  val port: Int = 445,
  val share: String = "",
  val startPath: String = "",
  val username: String = "",
  val domain: String = "",
  val password: String = "",
  val anonymous: Boolean = false,
)

fun SmbProfile.displayAddress(): String = if (share.isBlank()) {
  "//$host:$port"
} else {
  "//$host/$share"
}

fun SmbProfile.remotePath(path: String): String {
  return listOf(startPath.trim('/'), path.trim('/'))
    .filter { it.isNotEmpty() }
    .joinToString("/")
}
