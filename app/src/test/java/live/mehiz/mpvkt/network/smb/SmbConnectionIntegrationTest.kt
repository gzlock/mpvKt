package live.mehiz.mpvkt.network.smb

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Properties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Opt-in test against a real SMB server. Configure SMB_TEST_HOST, SMB_TEST_USERNAME, and
 * SMB_TEST_PASSWORD in the test process environment. SMB_TEST_PORT defaults to 445;
 * SMB_TEST_DOMAIN is optional. Set SMB_TEST_ANONYMOUS=true for anonymous access.
 */
class SmbConnectionIntegrationTest {
  @Test
  fun authenticatesWithConfiguredServer() {
    val host = System.getenv("SMB_TEST_HOST")
    val username = System.getenv("SMB_TEST_USERNAME")
    val password = System.getenv("SMB_TEST_PASSWORD")
    val anonymous = System.getenv("SMB_TEST_ANONYMOUS").equals("true", ignoreCase = true)

    assumeTrue("Set SMB_TEST_HOST to run the SMB integration test", !host.isNullOrBlank())
    assumeTrue(
      "Set SMB_TEST_USERNAME and SMB_TEST_PASSWORD, or SMB_TEST_ANONYMOUS=true",
      anonymous || (username != null && password != null),
    )

    val port = System.getenv("SMB_TEST_PORT")?.toIntOrNull() ?: 445
    check(port in 1..65535) { "SMB_TEST_PORT must be between 1 and 65535" }

    SmbConnection.authenticate(
      SmbProfile(
        name = "SMB integration test",
        host = host!!,
        port = port,
        share = "",
        username = username.orEmpty(),
        domain = System.getenv("SMB_TEST_DOMAIN").orEmpty(),
        password = password.orEmpty(),
        anonymous = anonymous,
      ),
    )
  }

  @Test
  fun enumeratesSharesOnConfiguredServer() {
    val host = System.getenv("SMB_TEST_HOST")
    val username = System.getenv("SMB_TEST_USERNAME")
    val password = System.getenv("SMB_TEST_PASSWORD")
    val anonymous = System.getenv("SMB_TEST_ANONYMOUS").equals("true", ignoreCase = true)

    assumeTrue("Set SMB_TEST_HOST to run the SMB integration test", !host.isNullOrBlank())
    assumeTrue(
      "Set SMB_TEST_USERNAME and SMB_TEST_PASSWORD, or SMB_TEST_ANONYMOUS=true",
      anonymous || (username != null && password != null),
    )

    val port = System.getenv("SMB_TEST_PORT")?.toIntOrNull() ?: 445
    check(port in 1..65535) { "SMB_TEST_PORT must be between 1 and 65535" }

    val properties = Properties().apply {
      // Force SMB2+ and bound time spent waiting on a server which doesn't expose srvsvc.
      setProperty("jcifs.smb.client.minVersion", "SMB202")
      setProperty("jcifs.smb.client.maxVersion", "SMB311")
      setProperty("jcifs.smb.client.connTimeout", "5000")
      setProperty("jcifs.smb.client.soTimeout", "5000")
    }
    val credentials = if (anonymous) {
      NtlmPasswordAuthenticator()
    } else {
      // Match production: a blank domain means no domain, not an empty string.
      val domain = System.getenv("SMB_TEST_DOMAIN")?.takeIf { it.isNotBlank() }
      NtlmPasswordAuthenticator(domain, username, password)
    }
    val context: CIFSContext = BaseContext(PropertyConfiguration(properties))
      .withCredentials(credentials)

    val shares = try {
      SmbFile("smb://$host:$port/", context)
        .listFiles()
        .map { it.name.trimEnd('/') }
        .filter(String::isNotBlank)
    } finally {
      context.close()
    }

    assertFalse("Server returned no shares; verify account permissions", shares.isEmpty())
    assertTrue("Share enumeration returned a blank name", shares.none(String::isBlank))
    val autoBrowsableShares = shares.filterNot {
      it.endsWith("$", ignoreCase = true) || it.equals("IPC$", ignoreCase = true)
    }
    println(
      "SMB share enumeration succeeded: ${shares.size} entries, " +
        "${autoBrowsableShares.size} ordinary shares, " +
        "${shares.size - autoBrowsableShares.size} hidden or non-disk shares",
    )
  }

  companion object {
    // Modern BC no longer registers MD4/AESCMAC; jcifs needs both for NTLM and SMB3 signing.
    @JvmStatic
    @BeforeClass
    fun initCrypto() = SmbCrypto.ensureProvider()
  }
}
