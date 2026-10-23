package live.mehiz.mpvkt.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalSortTest {

  @Test
  fun numberedFilesSortNumerically() {
    val names = listOf("1 (10).MP4", "1 (2).MP4", "2.mp4", "1 (1).MP4")
    val sorted = names.sortedWith(NaturalSort.comparator)
    assertEquals(listOf("1 (1).MP4", "1 (2).MP4", "1 (10).MP4", "2.mp4"), sorted)
  }

  @Test
  fun episodeNumbersSortNumerically() {
    val names = listOf("episode 10", "episode 2", "episode 1", "episode 100")
    val sorted = names.sortedWith(NaturalSort.comparator)
    assertEquals(listOf("episode 1", "episode 2", "episode 10", "episode 100"), sorted)
  }

  @Test
  fun mixedCaseIsComparedCaseInsensitively() {
    val sorted = listOf("banana.mp4", "Apple.mp4").sortedWith(NaturalSort.comparator)
    assertEquals(listOf("Apple.mp4", "banana.mp4"), sorted)
    assertEquals(0, NaturalSort.compare("Apple.mp4", "apple.mp4"))
  }

  @Test
  fun descendingReversesNaturalOrder() {
    val sorted = listOf("1 (1).MP4", "1 (10).MP4", "1 (2).MP4")
      .sortedWith(NaturalSort.comparator.reversed())
    assertEquals(listOf("1 (10).MP4", "1 (2).MP4", "1 (1).MP4"), sorted)
  }

  @Test
  fun arbitrarilyLongNumbersDoNotOverflow() {
    val big = "a" + "9".repeat(100)
    val bigger = "a" + "1" + "0".repeat(100)
    assertTrue(NaturalSort.compare(big, bigger) < 0)
  }

  @Test
  fun leadingZerosAreIgnored() {
    assertEquals(0, NaturalSort.compare("a007.mp4", "a7.mp4"))
  }

  @Test
  fun shorterPrefixComesFirst() {
    val sorted = listOf("file1", "file", "file2").sortedWith(NaturalSort.comparator)
    assertEquals(listOf("file", "file1", "file2"), sorted)
  }

  @Test
  fun equalStringsCompareEqual() {
    assertEquals(0, NaturalSort.compare("same name.mp4", "same name.mp4"))
  }
}
