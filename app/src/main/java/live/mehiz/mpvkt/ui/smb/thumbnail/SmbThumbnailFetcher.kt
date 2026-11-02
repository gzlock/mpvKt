package live.mehiz.mpvkt.ui.smb.thumbnail

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import live.mehiz.mpvkt.network.smb.SmbRuntime
import okio.buffer

class SmbThumbnailFetcher(
  private val data: SmbVideoThumbnail,
  private val runtime: SmbRuntime,
  private val imageLoader: ImageLoader,
) : Fetcher {

  override suspend fun fetch(): FetchResult {
    val cacheKey = smbThumbnailCacheKey(
      profileId = data.profile.id,
      path = data.path,
      size = data.size,
      modifiedTime = data.modifiedTime,
    )
    val diskCache = imageLoader.diskCache

    // Serve from the disk cache when available. The snapshot is kept open until the
    // image source is closed, so the entry cannot be evicted mid-decode.
    diskCache?.openSnapshot(cacheKey)?.let { snapshot ->
      return SourceFetchResult(
        source = ImageSource(
          file = snapshot.data,
          fileSystem = diskCache.fileSystem,
          closeable = snapshot,
        ),
        mimeType = MIME_TYPE_JPEG,
        dataSource = DataSource.DISK,
      )
    }

    // Limit concurrent remote frame extraction to avoid overwhelming the SMB connection.
    return fetchSemaphore.withPermit {
      // Another request may have populated the cache while we waited for a permit.
      diskCache?.openSnapshot(cacheKey)?.let { snapshot ->
        return@withPermit SourceFetchResult(
          source = ImageSource(
            file = snapshot.data,
            fileSystem = diskCache.fileSystem,
            closeable = snapshot,
          ),
          mimeType = MIME_TYPE_JPEG,
          dataSource = DataSource.DISK,
        )
      }

      val proxyUrl = runtime.publishThumbnail(data.profile, data.path)
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(proxyUrl)
        val durationMs = retriever
          .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          ?.toLongOrNull()
          ?: 0L
        // Pick a frame a little into the video to avoid a black opening frame.
        val frameTimeUs = minOf(TARGET_FRAME_TIME_MS, durationMs / 10)
          .coerceAtLeast(0L) * 1000L
        val bitmap = retriever
          .getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          ?: error("Unable to decode a frame from ${data.path}")

        val scaled = bitmap.scaleDown(MAX_BITMAP_DIMENSION)
        val jpegBytes = scaled.toJpegBytes(JPEG_QUALITY)

        if (diskCache != null) {
          diskCache.openEditor(cacheKey)?.let { editor ->
            diskCache.fileSystem.sink(editor.data).buffer().use { sink ->
              sink.write(jpegBytes)
            }
            editor.commit()
          }
        }

        val snapshot = diskCache?.openSnapshot(cacheKey)
        if (snapshot != null) {
          SourceFetchResult(
            source = ImageSource(
              file = snapshot.data,
              fileSystem = diskCache.fileSystem,
              closeable = snapshot,
            ),
            mimeType = MIME_TYPE_JPEG,
            dataSource = DataSource.NETWORK,
          )
        } else {
          ImageFetchResult(
            image = scaled.asImage(),
            isSampled = true,
            dataSource = DataSource.NETWORK,
          )
        }
      } finally {
        retriever.release()
        runtime.unpublishThumbnail(proxyUrl)
      }
    }
  }

  class Factory(
    private val runtime: SmbRuntime,
  ) : Fetcher.Factory<SmbVideoThumbnail> {
    override fun create(
      data: SmbVideoThumbnail,
      options: Options,
      imageLoader: ImageLoader,
    ): Fetcher = SmbThumbnailFetcher(data, runtime, imageLoader)
  }

  private companion object {
    const val MIME_TYPE_JPEG = "image/jpeg"
    const val TARGET_FRAME_TIME_MS = 3_000L
    const val MAX_BITMAP_DIMENSION = 320
    const val JPEG_QUALITY = 85

    val fetchSemaphore = Semaphore(2)
  }
}

private fun Bitmap.scaleDown(maxDimension: Int): Bitmap {
  val largestSide = maxOf(width, height)
  if (largestSide <= maxDimension) return this
  val scale = maxDimension.toFloat() / largestSide
  return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
  val output = ByteArrayOutputStream()
  compress(Bitmap.CompressFormat.JPEG, quality, output)
  return output.toByteArray()
}
