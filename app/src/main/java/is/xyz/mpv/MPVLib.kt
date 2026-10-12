package `is`.xyz.mpv

import android.graphics.Bitmap
import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * Compatibility facade for the pre-0.1.12 global API.
 * The underlying player is the MPV instance owned by BaseMPVView.
 */
@Suppress("unused")
object MPVLib {
  private var instance: MPV? = null
  private val observers = linkedSetOf<EventObserver>()

  private val current: MPV
    get() = instance ?: error("MPV has not been initialized")

  fun attachInstance(mpv: MPV) {
    synchronized(observers) {
      if (instance === mpv) return
      instance?.let { previous -> observers.forEach(previous::removeObserver) }
      instance = mpv
      observers.forEach(mpv::addObserver)
    }
  }

  fun create(appctx: android.content.Context) = current.create(appctx)
  fun init() = current.init()
  fun destroy() = current.destroy()
  fun attachSurface(surface: Surface) = current.attachSurface(surface)
  fun detachSurface() = current.detachSurface()
  fun command(vararg cmd: String) = current.command(*cmd)
  fun commandNode(vararg cmd: String): MPVNode? = current.commandNode(*cmd)
  fun setOptionString(name: String, value: String): Int = current.setOptionString(name, value)
  fun grabThumbnail(dimension: Int): Bitmap? = current.grabThumbnail(dimension)
  fun getPropertyInt(property: String): Int? = current.getPropertyInt(property)
  fun setPropertyInt(property: String, value: Int) = current.setPropertyInt(property, value)
  fun getPropertyDouble(property: String): Double? = current.getPropertyDouble(property)
  fun setPropertyDouble(property: String, value: Double) = current.setPropertyDouble(property, value)
  fun getPropertyBoolean(property: String): Boolean? = current.getPropertyBoolean(property)
  fun setPropertyBoolean(property: String, value: Boolean) = current.setPropertyBoolean(property, value)
  fun getPropertyString(property: String): String? = current.getPropertyString(property)
  fun setPropertyString(property: String, value: String) = current.setPropertyString(property, value)
  fun getPropertyNode(property: String): MPVNode? = current.getPropertyNode(property)
  fun setPropertyNode(property: String, node: MPVNode) = current.setPropertyNode(property, node)
  fun getPropertyFloat(property: String): Float? = current.getPropertyFloat(property)
  fun setPropertyFloat(property: String, value: Float) = current.setPropertyFloat(property, value)
  fun getPropertyLong(property: String): Long? = current.getPropertyLong(property)
  fun setPropertyLong(property: String, value: Long) = current.setPropertyLong(property, value)
  fun observeProperty(property: String, format: Int) = current.observeProperty(property, format)

  val propInt = PropertyAccessor { current.propFlow<Int>(it) }
  val propBoolean = PropertyAccessor { current.propFlow<Boolean>(it) }
  val propString = PropertyAccessor { current.propFlow<String>(it) }
  val propDouble = PropertyAccessor { current.propFlow<Double>(it) }
  val propNode = PropertyAccessor { current.propFlow<MPVNode>(it) }
  val propLong = PropertyAccessor { current.propFlow<Long>(it) }
  val propFloat = PropertyAccessor { current.propFlow<Float>(it) }

  class PropertyAccessor<T>(private val flow: (String) -> StateFlow<T?>) {
    operator fun get(property: String): StateFlow<T?> = flow(property)
  }

  fun addObserver(observer: EventObserver) {
    synchronized(observers) {
      if (observers.add(observer)) instance?.addObserver(observer)
    }
  }

  fun removeObserver(observer: EventObserver) {
    synchronized(observers) {
      if (observers.remove(observer)) instance?.removeObserver(observer)
    }
  }

  interface EventObserver : MPV.EventObserver

  object mpvFormat {
    const val MPV_FORMAT_NONE = 0
    const val MPV_FORMAT_STRING = 1
    const val MPV_FORMAT_OSD_STRING = 2
    const val MPV_FORMAT_FLAG = 3
    const val MPV_FORMAT_INT64 = 4
    const val MPV_FORMAT_DOUBLE = 5
    const val MPV_FORMAT_NODE = 6
    const val MPV_FORMAT_NODE_ARRAY = 7
    const val MPV_FORMAT_NODE_MAP = 8
    const val MPV_FORMAT_BYTE_ARRAY = 9
  }

  object mpvEventId {
    const val MPV_EVENT_NONE = 0
    const val MPV_EVENT_SHUTDOWN = 1
    const val MPV_EVENT_LOG_MESSAGE = 2
    const val MPV_EVENT_GET_PROPERTY_REPLY = 3
    const val MPV_EVENT_SET_PROPERTY_REPLY = 4
    const val MPV_EVENT_COMMAND_REPLY = 5
    const val MPV_EVENT_START_FILE = 6
    const val MPV_EVENT_END_FILE = 7
    const val MPV_EVENT_FILE_LOADED = 8
    const val MPV_EVENT_IDLE = 11
    const val MPV_EVENT_TICK = 14
    const val MPV_EVENT_CLIENT_MESSAGE = 16
    const val MPV_EVENT_VIDEO_RECONFIG = 17
    const val MPV_EVENT_AUDIO_RECONFIG = 18
    const val MPV_EVENT_SEEK = 20
    const val MPV_EVENT_PLAYBACK_RESTART = 21
    const val MPV_EVENT_PROPERTY_CHANGE = 22
    const val MPV_EVENT_QUEUE_OVERFLOW = 24
    const val MPV_EVENT_HOOK = 25
  }
}
