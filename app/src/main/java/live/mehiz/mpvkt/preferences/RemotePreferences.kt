package live.mehiz.mpvkt.preferences

import live.mehiz.mpvkt.preferences.preference.PreferenceStore

/** 远程存储相关偏好。 */
class RemotePreferences(preferenceStore: PreferenceStore) {
  /**
   * 长按远程存储项时，命令菜单最多展示的命令条数。
   * 默认 3，可在 3、5 之间选择；存储为 Int，便于直接作为 take/filler 的上限。
   */
  val commandMenuLimit = preferenceStore.getInt("remote_command_menu_limit", 3)
}
