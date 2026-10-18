package live.mehiz.mpvkt.network.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.util.EnumSet

data class SmbEntry(
  val name: String,
  val path: String,
  val directory: Boolean,
  val size: Long = 0L,
  val modifiedTime: Long = 0L,
)

/** Stages reported by [SmbConnection.authenticate] so callers can show progress steps. */
enum class SmbAuthStage {
  /** Opening the TCP connection and performing the SMB protocol negotiation. */
  CONNECTING,

  /** Sending the credentials and setting up the authenticated session. */
  AUTHENTICATING,
}

class SmbConnection(private val profile: SmbProfile) : Closeable {
  private val client = SMBClient()
  private val connection = client.connect(profile.host, profile.port)
  private val session = connection.authenticate(profile.authenticationContext())
  private val share = session.connectShare(profile.share) as DiskShare

  fun list(path: String): List<SmbEntry> {
    val normalized = path.trim('/')
    return share.list(normalized).mapNotNull { item ->
      val name = item.fileName ?: return@mapNotNull null
      if (name == "." || name == "..") return@mapNotNull null
      SmbEntry(
        name = name,
        path = listOf(normalized, name).filter { it.isNotEmpty() }.joinToString("/"),
        directory = item.fileAttributes and DIRECTORY_ATTRIBUTE != 0L,
        size = item.endOfFile,
        modifiedTime = item.lastWriteTime.toEpochMillis(),
      )
    }
  }

  fun open(path: String): SmbRemoteFile {
    val file = share.openFile(
      path.trim('/'),
      EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
      null,
      EnumSet.allOf(SMB2ShareAccess::class.java),
      SMB2CreateDisposition.FILE_OPEN,
      EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
    )
    return SmbRemoteFile(file)
  }

  override fun close() {
    runCatching { share.close() }
    runCatching { session.close() }
    runCatching { connection.close() }
    runCatching { client.close() }
  }

  companion object {
    /** Connects and authenticates without requiring a disk share name. */
    fun authenticate(profile: SmbProfile, onStage: (SmbAuthStage) -> Unit = {}) {
      SMBClient().use { client ->
        onStage(SmbAuthStage.CONNECTING)
        client.connect(profile.host, profile.port).use { connection ->
          onStage(SmbAuthStage.AUTHENTICATING)
          connection.authenticate(profile.authenticationContext()).use { }
        }
      }
    }
  }
}

class SmbRemoteFile(private val file: File) : Closeable {
  val size: Long
    get() = file.getFileInformation(FileStandardInformation::class.java).endOfFile

  @Synchronized
  fun read(offset: Long, target: ByteArray, length: Int): Int {
    return file.read(target, offset, 0, length)
  }

  override fun close() {
    file.close()
  }
}

private const val DIRECTORY_ATTRIBUTE = 0x10L

private fun SmbProfile.authenticationContext(): AuthenticationContext = if (anonymous) {
  AuthenticationContext.anonymous()
} else {
  AuthenticationContext(username, password.toCharArray(), domain.ifBlank { null })
}
