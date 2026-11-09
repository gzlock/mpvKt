package live.mehiz.mpvkt.ui.remote

import androidx.lifecycle.ViewModel

/** A directory's position within the remote browser list. */
data class RemoteDirectoryScrollPosition(
  val index: Int,
  val offsetPx: Int,
  val anchorPath: String? = null,
)

/**
 * Shared by remote browser entries in one activity so each directory can restore its own
 * position after its asynchronous listing has completed. One position per directory
 * (independent of the active sort order); actively changing the sort order resets it.
 * Bounded to [MAX_ENTRIES] (least-recently-used eviction) to keep long sessions lean.
 */
class RemoteBrowserViewModel : ViewModel() {
  private val scrollPositions =
    object : LinkedHashMap<ScrollKey, RemoteDirectoryScrollPosition>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<ScrollKey, RemoteDirectoryScrollPosition>,
      ): Boolean = size > MAX_ENTRIES
    }

  fun saveScrollPosition(
    profileId: String,
    shareName: String,
    path: String,
    position: RemoteDirectoryScrollPosition,
  ) {
    scrollPositions[key(profileId, shareName, path)] = position
  }

  fun scrollPosition(
    profileId: String,
    shareName: String,
    path: String,
  ): RemoteDirectoryScrollPosition? =
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
