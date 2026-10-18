package live.mehiz.mpvkt.network.smb

import android.util.Log
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

/** Enumerates server shares; SMBJ is used separately to verify each candidate is a browsable disk share. */
internal class SmbShareEnumerator {
  fun enumerate(profile: SmbProfile): List<String> {
    SmbCrypto.ensureProvider()
    val properties = Properties().apply {
      setProperty("jcifs.smb.client.minVersion", "SMB202")
      setProperty("jcifs.smb.client.maxVersion", "SMB311")
      setProperty("jcifs.smb.client.connTimeout", TIMEOUT_MILLIS.toString())
      setProperty("jcifs.smb.client.soTimeout", TIMEOUT_MILLIS.toString())
    }
    val credentials = if (profile.anonymous) {
      NtlmPasswordAuthenticator()
    } else {
      // Match the SMBJ side: a blank domain must be null (no domain), not an empty string,
      // otherwise the server may reject the session setup during share enumeration.
      NtlmPasswordAuthenticator(profile.domain.ifBlank { null }, profile.username, profile.password)
    }
    val context = BaseContext(PropertyConfiguration(properties)).withCredentials(credentials)
    val host = profile.host.let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }

    return try {
      val entries = SmbFile("smb://$host:${profile.port}/", context).listFiles() ?: emptyArray<SmbFile>()
      entries
        .asSequence()
        .map { it.name.trim().trimEnd('/') }
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .toList()
        .also { Log.i(TAG, "Enumerated ${it.size} share(s) on ${profile.host}:${profile.port}: $it") }
    } catch (t: Throwable) {
      Log.w(TAG, "Share enumeration failed on ${profile.host}:${profile.port}", t)
      throw t
    } finally {
      context.close()
    }
  }

  private companion object {
    const val TAG = "mpvkt-smb"
    const val TIMEOUT_MILLIS = 5_000
  }
}
