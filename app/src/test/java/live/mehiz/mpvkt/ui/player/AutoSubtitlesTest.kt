package live.mehiz.mpvkt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSubtitlesTest {

  @Test
  fun singleVideo_loadsAllSubtitlesRegardlessOfName() {
    val files = listOf("movie.mkv", "english.srt", "chinese.ass", "director_commentary.vtt")
    assertEquals(
      listOf("chinese.ass", "director_commentary.vtt", "english.srt"),
      resolveAutoSubtitles(files, "movie.mkv"),
    )
  }

  @Test
  fun singleVideo_subtitlesAreNaturalSorted() {
    val files = listOf("movie.mkv", "sub10.srt", "sub2.srt", "sub1.srt")
    assertEquals(
      listOf("sub1.srt", "sub2.srt", "sub10.srt"),
      resolveAutoSubtitles(files, "movie.mkv"),
    )
  }

  @Test
  fun singleVideo_noSubtitles_returnsEmpty() {
    assertEquals(emptyList<String>(), resolveAutoSubtitles(listOf("movie.mkv"), "movie.mkv"))
  }

  @Test
  fun singleVideo_unrelatedFilesAreIgnored() {
    val files = listOf("movie.mkv", "cover.jpg", "info.nfo", "notes.txt", "sub.srt")
    assertEquals(listOf("sub.srt"), resolveAutoSubtitles(files, "movie.mkv"))
  }

  @Test
  fun subExtensionIsSupported() {
    val files = listOf("movie.mkv", "legacy.sub")
    assertEquals(listOf("legacy.sub"), resolveAutoSubtitles(files, "movie.mkv"))
  }

  @Test
  fun multipleVideos_onlySamePrefixSubtitlesAreReturned() {
    val files = listOf(
      "movieA.mkv",
      "movieB.mkv",
      "movieA.srt",
      "movieA.ass",
      "movieB.srt",
    )
    assertEquals(
      listOf("movieA.ass", "movieA.srt"),
      resolveAutoSubtitles(files, "movieA.mkv"),
    )
  }

  @Test
  fun multipleVideos_noMatchingSubtitle_returnsEmpty() {
    val files = listOf("movieA.mkv", "movieB.mkv", "movieB.srt")
    assertEquals(emptyList<String>(), resolveAutoSubtitles(files, "movieA.mkv"))
  }

  @Test
  fun noVideo_returnsEmpty() {
    val files = listOf("notes.txt", "sub.srt")
    assertEquals(emptyList<String>(), resolveAutoSubtitles(files, "sub.srt"))
  }

  @Test
  fun extensionsAreCaseInsensitive() {
    val files = listOf("movie.MKV", "subtitle.SRT", "styled.Ass")
    assertEquals(
      listOf("styled.Ass", "subtitle.SRT"),
      resolveAutoSubtitles(files, "movie.MKV"),
    )
  }

  @Test
  fun hiddenFilesAreIgnored() {
    // 隐藏视频与隐藏字幕均不参与判定，只剩一个可见视频和一条可见字幕。
    val files = listOf(".secret.mkv", "video.mp4", ".hidden.srt", "video.srt")
    assertEquals(listOf("video.srt"), resolveAutoSubtitles(files, "video.mp4"))
  }

  @Test
  fun onlyHiddenVideo_returnsEmpty() {
    val files = listOf(".secret.mkv", "a.srt")
    assertEquals(emptyList<String>(), resolveAutoSubtitles(files, ".secret.mkv"))
  }

  @Test
  fun filesWithoutExtensionAreIgnored() {
    val files = listOf("movie.mkv", "LICENSE", "Makefile", "sub.srt")
    assertEquals(listOf("sub.srt"), resolveAutoSubtitles(files, "movie.mkv"))
  }
}
