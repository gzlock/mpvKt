package live.mehiz.mpvkt.network.smb

import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

/** Enumerates server shares; SMBJ is used separately to verify each candidate is a browsable disk share. */
internal class SmbShareEnumerator {
  fun enumerate(profile: SmbProfile): List<String> {
    val properties = Properties().apply {
      setProperty("jcifs.smb.client.minVersion", "SMB202")
      setProperty("jcifs.smb.client.maxVersion", "SMB311")
      setProperty("jcifs.smb.client.connTimeout", TIMEOUT_MILLIS.toString())
      setProperty("jcifs.smb.client.soTimeout", TIMEOUT_MILLIS.toString())
    }
    val credentials = if (profile.anonymous) {
      NtlmPasswordAuthenticator()
    } else {
      NtlmPasswordAuthenticator(profile.domain, profile.username, profile.password)
    }
    val context = BaseContext(PropertyConfiguration(properties)).withCredentials(credentials)
    val host = profile.host.let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }

    return try {
      SmbFile("smb://$host:${profile.port}/", context)
        .listFiles()
        .asSequence()
        .map { it.name.trimEnd('/') }
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .toList()
    } finally {
      context.close()
    }
  }

  private companion object {
    const val TIMEOUT_MILLIS = 5_000
  }
}
