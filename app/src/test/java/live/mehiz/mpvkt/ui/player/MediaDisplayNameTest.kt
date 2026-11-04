package live.mehiz.mpvkt.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDisplayNameTest {

  @Test
  fun urlNameIsTheLastPathSegmentWithItsExtension() {
    assertEquals("c.mp4", mediaDisplayName("http://a/b/c.mp4"))
  }

  @Test
  fun queryAndFragmentAreDropped() {
    assertEquals("c.mp4", mediaDisplayName("http://a/b/c.mp4?token=1#t=3"))
  }

  @Test
  fun percentEncodedSegmentsAreDecoded() {
    assertEquals("测试 视频.mp4", mediaDisplayName("http://a/b/%E6%B5%8B%E8%AF%95%20%E8%A7%86%E9%A2%91.mp4"))
  }

  @Test
  fun plusInPathIsNotTreatedAsSpace() {
    assertEquals("c+d.mp4", mediaDisplayName("/sdcard/movie/c+d.mp4"))
  }

  @Test
  fun plainFilePathWorksTheSame() {
    assertEquals("c.mkv", mediaDisplayName("/sdcard/movie/c.mkv"))
  }

  @Test
  fun dottedNamesAreKeptWhole() {
    assertEquals("Show.S01E02.1080p.mkv", mediaDisplayName("http://a/b/Show.S01E02.1080p.mkv"))
  }

  @Test
  fun blankSourcesYieldNull() {
    assertNull(mediaDisplayName(null))
    assertNull(mediaDisplayName("  "))
  }
}
