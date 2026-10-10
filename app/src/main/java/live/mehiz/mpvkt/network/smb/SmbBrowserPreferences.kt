package live.mehiz.mpvkt.network.smb

import android.content.Context

/** Persists non-sensitive SMB browser UI state on this device. */
class SmbBrowserPreferences(context: Context) {
  private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun getSortOrder(profileId: String, share: String, path: String): String {
    return preferences.getString(key(profileId, share, path), null)
      ?: preferences.getString(legacyKey(profileId, path), DEFAULT_SORT_ORDER)
      ?: DEFAULT_SORT_ORDER
  }

  fun setSortOrder(profileId: String, share: String, path: String, sortOrder: String) {
    preferences.edit().putString(key(profileId, share, path), sortOrder).apply()
  }

  fun getSortOrder(profileId: String, path: String): String = getSortOrder(profileId, "", path)

  fun setSortOrder(profileId: String, path: String, sortOrder: String) {
    setSortOrder(profileId, "", path, sortOrder)
  }

  private fun key(profileId: String, share: String, path: String): String {
    return "$SORT_PREFIX${profileId}_${share}_${path.trim('/')}"
  }

  private fun legacyKey(profileId: String, path: String): String {
    return "$SORT_PREFIX${profileId}_${path.trim('/')}"
  }

  private companion object {
    const val PREFERENCES = "smb_browser"
    const val SORT_PREFIX = "sort_"
    const val DEFAULT_SORT_ORDER = "NAME_ASC"
  }
}
