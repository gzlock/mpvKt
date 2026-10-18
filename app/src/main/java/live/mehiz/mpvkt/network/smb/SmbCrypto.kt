package live.mehiz.mpvkt.network.smb

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.InvalidKeyException
import java.security.Key
import java.security.MessageDigestSpi
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.MacSpi

/**
 * jcifs-ng relies on a few legacy primitives that modern BouncyCastle no longer registers as JCE
 * services:
 *  - MD4 MessageDigest, used to derive the NTLM password hash;
 *  - AESCMAC Mac, used for SMB3 packet signing.
 *
 * Wrap BC's own implementations in JCE Spi classes, register them on a BC provider, and hand that
 * provider to jcifs before any enumeration runs.
 */
internal object SmbCrypto {
  @Volatile
  private var initialized = false

  fun ensureProvider() {
    if (initialized) return
    synchronized(this) {
      if (initialized) return
      // BouncyCastleProvider is final, so instantiate it directly and register the missing
      // services on it.
      val provider = BouncyCastleProvider()
      provider.put("MessageDigest.MD4", Md4Spi::class.java.name)
      provider.put("Mac.AESCMAC", AesCmacSpi::class.java.name)
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

  /** AES-CMAC (RFC 4493) built on BC's CMac + AESEngine, exposed under the "AESCMAC" name jcifs asks for. */
  class AesCmacSpi : MacSpi() {
    private val mac = CMac(AESEngine.newInstance())

    override fun engineGetMacLength(): Int = mac.getMacSize()

    override fun engineInit(key: Key, params: AlgorithmParameterSpec?) {
      val keyBytes = key.encoded ?: throw InvalidKeyException("AESCMAC requires raw key bytes")
      mac.init(KeyParameter(keyBytes))
    }

    override fun engineUpdate(input: Byte) {
      mac.update(input)
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
      mac.update(input, offset, len)
    }

    override fun engineDoFinal(): ByteArray {
      val out = ByteArray(mac.getMacSize())
      mac.doFinal(out, 0)
      return out
    }

    override fun engineReset() {
      mac.reset()
    }
  }
}
