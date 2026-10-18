package live.mehiz.mpvkt.network.smb

import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigestSpi

/**
 * jcifs-ng derives the NTLM password hash with MD4, but modern BouncyCastle no longer registers
 * the legacy "MD4" MessageDigest service, so `MessageDigest.getInstance("MD4", bcProvider)`
 * fails during share enumeration. Wrap BC's own [MD4Digest] in a JCE Spi, register it on a BC
 * provider, and hand that provider to jcifs before any enumeration runs.
 */
internal object SmbCrypto {
  @Volatile
  private var initialized = false

  fun ensureProvider() {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      // BouncyCastleProvider is final, so instantiate it directly and register MD4 on it.
      val provider = BouncyCastleProvider()
      provider.put("MessageDigest.MD4", Md4Spi::class.java.name)
      // initProvider throws if jcifs already cached another provider; ignore and fall back.
      runCatching { jcifs.util.Crypto.initProvider(provider) }
      initialized = true
    }
  }

  class Md4Spi : MessageDigestSpi() {
    private val digest = MD4Digest()

    override fun engineGetDigestLength(): Int = digest.getDigestSize()

    override fun engineUpdate(input: Byte) {
      digest.update(input)
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
      digest.update(input, offset, len)
    }

    override fun engineDigest(): ByteArray {
      val out = ByteArray(digest.getDigestSize())
      digest.doFinal(out, 0)
      return out
    }

    override fun engineReset() {
      digest.reset()
    }
  }
}
