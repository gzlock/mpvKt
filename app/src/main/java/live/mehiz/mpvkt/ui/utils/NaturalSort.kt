package live.mehiz.mpvkt.ui.utils

/**
 * Natural (human-friendly) string ordering for file names.
 *
 * Numeric runs are compared by their numeric value instead of character by character,
 * so "1 (2).MP4" sorts before "1 (10).MP4". Non-numeric runs keep the previous
 * case-insensitive alphabetical behaviour used by the SMB browser.
 */
object NaturalSort {

  private val chunkRegex = Regex("(\\d+)|(\\D+)")

  /** A reusable [Comparator] applying [compare] to [String]s. */
  val comparator: Comparator<String> = Comparator { str1, str2 -> compare(str1, str2) }

  /** Compares two display names with natural ordering. */
  fun compare(str1: String, str2: String): Int {
    val chunks1 = chunkRegex.findAll(str1).map { it.value }.toList()
    val chunks2 = chunkRegex.findAll(str2).map { it.value }.toList()
    for (i in 0 until minOf(chunks1.size, chunks2.size)) {
      val chunk1 = chunks1[i]
      val chunk2 = chunks2[i]
      val result = if (chunk1.first().isDigit() && chunk2.first().isDigit()) {
        compareNumericChunks(chunk1, chunk2)
      } else {
        chunk1.compareTo(chunk2, ignoreCase = true)
      }
      if (result != 0) return result
    }
    return chunks1.size.compareTo(chunks2.size)
  }

  /**
   * Compares two all-digit chunks by numeric value without parsing them, so arbitrarily
   * long numbers cannot overflow or throw NumberFormatException. Leading zeros are
   * ignored, i.e. "007" compares equal to "7".
   */
  private fun compareNumericChunks(chunk1: String, chunk2: String): Int {
    val digits1 = chunk1.trimStart('0')
    val digits2 = chunk2.trimStart('0')
    return when {
      digits1.length != digits2.length -> digits1.length.compareTo(digits2.length)
      else -> digits1.compareTo(digits2)
    }
  }
}
