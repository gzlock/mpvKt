package live.mehiz.mpvkt.network.smb

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.math.BigInteger
import java.security.KeyStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.AlgorithmParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SmbProfileStore(
  context: Context,
  private val json: Json,
) {
  private val appContext = context.applicationContext
  private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  @Synchronized
  fun getProfiles(): List<SmbProfile> {
    val value = preferences.getString(KEY, null) ?: return emptyList()
    return try {
      val storedProfiles = json.decodeFromString(ListSerializer(SmbProfile.serializer()), decrypt(value))
      val profiles = storedProfiles.map { it.withoutShare() }
      // Older versions persisted the selected share. Rewrite those records once so the
      // obsolete value is discarded instead of being carried into a new connection.
      if (profiles != storedProfiles) write(profiles)
      profiles
    } catch (error: Exception) {
      throw SmbProfileStoreException(
        "Saved SMB settings cannot be decrypted. Existing data was preserved.",
        error,
      )
    }
  }

  @Synchronized
  fun save(profile: SmbProfile) {
    val profiles = getProfiles().toMutableList()
    val profileWithoutShare = profile.withoutShare()
    val index = profiles.indexOfFirst { it.id == profileWithoutShare.id }
    if (index >= 0) profiles[index] = profileWithoutShare else profiles += profileWithoutShare
    write(profiles)
  }

  @Synchronized
  fun delete(id: String) {
    write(getProfiles().filterNot { it.id == id })
  }

  private fun write(profiles: List<SmbProfile>) {
    val plain = json.encodeToString(
      ListSerializer(SmbProfile.serializer()),
      profiles.map { it.withoutShare() },
    )
    preferences.edit { putString(KEY, encrypt(plain)) }
  }

  private fun SmbProfile.withoutShare(): SmbProfile = if (share.isEmpty()) this else copy(share = "")

  private fun secretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
      init(
        android.security.keystore.KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
            android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
          .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
          .build(),
      )
    }.generateKey()
  }

  private fun encrypt(value: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val useDirectKey = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    val key = if (useDirectKey) secretKey() else generatePortableKey()
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val iv = cipher.iv
    val encrypted = cipher.doFinal(value.toByteArray())
    val wrappedKey = if (useDirectKey) byteArrayOf() else wrapKey(key.encoded)
    return Base64.encodeToString(
      ByteBuffer.allocate(1 + 1 + 4 + iv.size + 4 + wrappedKey.size + encrypted.size)
        .put(FORMAT_VERSION).put(if (useDirectKey) DIRECT_KEY else WRAPPED_KEY)
        .putInt(iv.size).put(iv).putInt(wrappedKey.size).put(wrappedKey).put(encrypted).array(),
      Base64.NO_WRAP,
    )
  }

  private fun decrypt(value: String): String {
    val bytes = Base64.decode(value, Base64.NO_WRAP)
    val buffer = ByteBuffer.wrap(bytes)
    require(buffer.remaining() >= 10) { "Invalid encrypted SMB profile data" }
    require(buffer.get() == FORMAT_VERSION) { "Unsupported encrypted SMB profile format" }
    val keyMode = buffer.get()
    val ivLength = buffer.int
    require(ivLength in 12..32 && buffer.remaining() >= ivLength + 4) { "Invalid encrypted SMB profile IV" }
    val iv = ByteArray(ivLength).also(buffer::get)
    val wrappedLength = buffer.int
    require(wrappedLength >= 0 && buffer.remaining() > wrappedLength) { "Invalid encrypted SMB key data" }
    val wrappedKey = ByteArray(wrappedLength).also(buffer::get)
    val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
    require(encrypted.isNotEmpty()) { "Invalid encrypted SMB profile payload" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val key = when (keyMode) {
      DIRECT_KEY -> existingSecretKey()
      WRAPPED_KEY -> SecretKeySpec(unwrapKey(wrappedKey), "AES")
      else -> error("Unknown SMB profile key mode")
    }
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
    return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
  }

  private fun existingSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
      ?: throw IllegalStateException("The Android Keystore key for SMB settings is missing")
  }

  private fun generatePortableKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  private fun wrapKey(encodedKey: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair(create = true).public)
    return cipher.doFinal(encodedKey)
  }

  private fun unwrapKey(wrappedKey: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, rsaKeyPair(create = false).private)
    return cipher.doFinal(wrappedKey)
  }

  @Suppress("DEPRECATION")
  private fun rsaKeyPair(create: Boolean): KeyPair {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val certificate = keyStore.getCertificate(LEGACY_KEY_ALIAS)
    val privateKey = keyStore.getKey(LEGACY_KEY_ALIAS, null) as? PrivateKey
    if (certificate != null && privateKey != null) return KeyPair(certificate.publicKey, privateKey)
    check(create) { "The Android Keystore key for SMB settings is missing" }
    val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
    val parameters: AlgorithmParameterSpec = android.security.KeyPairGeneratorSpec.Builder(appContext)
      .setAlias(LEGACY_KEY_ALIAS)
      .setSubject(X500Principal("CN=mpvKt SMB profile encryption"))
      .setSerialNumber(BigInteger.ONE)
      .setStartDate(Date())
      .setEndDate(Date(System.currentTimeMillis() + LEGACY_KEY_VALIDITY_MILLIS))
      .build()
    generator.initialize(parameters)
    return generator.generateKeyPair()
  }

  private companion object {
    const val PREFERENCES = "smb_profiles"
    const val KEY = "encrypted_profiles"
    const val KEY_ALIAS = "mpvkt_smb_profiles"
    const val LEGACY_KEY_ALIAS = "mpvkt_smb_profiles_rsa"
    const val ANDROID_KEYSTORE = "AndroidKeyStore"
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val RSA_TRANSFORMATION = "RSA/ECB/PKCS1Padding"
    const val LEGACY_KEY_VALIDITY_MILLIS = 30L * 365 * 24 * 60 * 60 * 1000
    const val FORMAT_VERSION: Byte = 1
    const val DIRECT_KEY: Byte = 1
    const val WRAPPED_KEY: Byte = 2
  }
}

class SmbProfileStoreException(message: String, cause: Throwable) : IllegalStateException(message, cause)
