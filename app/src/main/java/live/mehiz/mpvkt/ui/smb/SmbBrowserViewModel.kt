package live.mehiz.mpvkt.ui.smb

import androidx.lifecycle.ViewModel

/** A directory's position within the SMB browser list. */
data class SmbDirectoryScrollPosition(
  val index: Int,
  val offsetPx: Int,
  val anchorPath: String? = null,
)

/**
 * Shared by SMB browser entries in one activity so each directory can restore its own
 * position after its asynchronous listing has completed. One position per directory
 * (independent of the active sort order); actively changing the sort order resets it.
 * Bounded to [MAX_ENTRIES] (least-recently-used eviction) to keep long sessions lean.
 */
class SmbBrowserViewModel : ViewModel() {
  private val scrollPositions =
    object : LinkedHashMap<ScrollKey, SmbDirectoryScrollPosition>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<ScrollKey, SmbDirectoryScrollPosition>,
      ): Boolean = size > MAX_ENTRIES
    }

  fun saveScrollPosition(
    profileId: String,
    shareName: String,
    path: String,
    position: SmbDirectoryScrollPosition,
  ) {
    scrollPositions[key(profileId, shareName, path)] = position
  }

  fun scrollPosition(
    profileId: String,
    shareName: String,
    path: String,
  ): SmbDirectoryScrollPosition? =
    scrollPositions[key(profileId, shareName, path)]

  private fun key(profileId: String, shareName: String, path: String) =
    ScrollKey(profileId, shareName, path.trim('/'))

  private data class ScrollKey(
    val profileId: String,
    val shareName: String,
    val path: String,
  )

  private companion object {
    const val MAX_ENTRIES = 200
  }
}
