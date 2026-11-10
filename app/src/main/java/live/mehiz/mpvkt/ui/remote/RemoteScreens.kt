package live.mehiz.mpvkt.ui.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import live.mehiz.mpvkt.R
import live.mehiz.mpvkt.network.remote.CommandSnippet
import live.mehiz.mpvkt.network.remote.ProtocolType
import live.mehiz.mpvkt.network.remote.SmbAuthStage
import live.mehiz.mpvkt.network.remote.RemoteEntry
import live.mehiz.mpvkt.network.remote.RemoteBrowserPreferences
import live.mehiz.mpvkt.network.remote.MacDiscovery
import live.mehiz.mpvkt.network.remote.RemotePlaylist
import live.mehiz.mpvkt.network.remote.RemoteProfile
import live.mehiz.mpvkt.network.remote.RemoteProfileStore
import live.mehiz.mpvkt.network.remote.RemoteRuntime
import live.mehiz.mpvkt.network.remote.RemoteSortOrder
import live.mehiz.mpvkt.network.remote.WakeOnLan
import live.mehiz.mpvkt.network.remote.supportsCommands
import live.mehiz.mpvkt.presentation.Screen
import live.mehiz.mpvkt.presentation.components.ConfirmDialog
import live.mehiz.mpvkt.preferences.RemotePreferences
import live.mehiz.mpvkt.preferences.SubtitlesPreferences
import live.mehiz.mpvkt.preferences.preference.collectAsState as preferenceCollectAsState
import live.mehiz.mpvkt.ui.player.PlayerActivity
import live.mehiz.mpvkt.ui.player.subtitleExtensions
import live.mehiz.mpvkt.ui.player.videoExtensions
import live.mehiz.mpvkt.ui.remote.ads.RemoteAdPageKey
import live.mehiz.mpvkt.ui.remote.ads.RemoteAdSlotKey
import live.mehiz.mpvkt.ui.remote.ads.RemoteNativeAdSlot
import live.mehiz.mpvkt.ui.remote.ads.RemoteNativeAdViewModel
import live.mehiz.mpvkt.ui.remote.thumbnail.RemoteVideoThumbnail
import live.mehiz.mpvkt.ui.utils.LocalBackStack
import live.mehiz.mpvkt.ui.utils.NaturalSort
import org.koin.compose.koinInject

/** 列表行滑动操作面板的展开状态。 */
private enum class RowRevealState { Settled, Open }

/** 滑动操作面板中单个按钮的宽度（dp），两个按钮合计 152dp。 */
private const val ACTION_BUTTON_WIDTH = 76

/**
 * 滑动操作面板中的单个按钮：图标在上、文字在下，垂直居中，底色随动作语义区分。
 */
@Composable
private fun SwipeActionButton(
  onClick: () -> Unit,
  icon: ImageVector,
  label: String,
  containerColor: Color,
  contentColor: Color,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .background(containerColor)
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(icon, contentDescription = label, tint = contentColor)
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = contentColor,
      maxLines = 1,
    )
  }
}

@Serializable
data class RemoteProfilesScreen(
  val subtitlePicker: Boolean = false,
  val initialProfileId: String? = null,
  val initialPath: String? = null,
  val initialShare: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val store = koinInject<RemoteProfileStore>()
    val runtime = koinInject<RemoteRuntime>()
    // 长按命令菜单的最大条数，来自设置（默认 3，可选 3/5）。
    val remotePreferences = koinInject<RemotePreferences>()
    val commandMenuLimit by remotePreferences.commandMenuLimit.preferenceCollectAsState()
    val loadedProfiles = remember { runCatching { store.getProfiles() } }
    var profiles by remember { mutableStateOf(loadedProfiles.getOrDefault(emptyList())) }
    var message by remember { mutableStateOf(loadedProfiles.exceptionOrNull()?.message) }
    var initialDirectoryOpened by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 命令执行反馈文案
    val cmdSuccessText = stringResource(R.string.remote_command_success)
    val cmdFailedText = stringResource(R.string.remote_command_failed)
    val cmdErrorText = stringResource(R.string.remote_command_error)
    // 网络唤醒反馈文案：已发送（提示等待）、未配置 MAC、发送异常。
    val wolSentText = stringResource(R.string.remote_wol_sent)
    val wolMacMissingText = stringResource(R.string.remote_wol_mac_missing)
    val wolErrorText = stringResource(R.string.remote_wol_error)
    LaunchedEffect(backstack.size) {
      if (backstack.lastOrNull() is RemoteProfilesScreen) {
        runCatching { store.getProfiles() }
          .onSuccess {
            profiles = it
            message = null
          }
          .onFailure { message = it.message }
      }
    }
    LaunchedEffect(initialProfileId, initialPath, initialShare, profiles) {
      if (!initialDirectoryOpened && subtitlePicker && initialProfileId != null) {
        profiles.firstOrNull { it.id == initialProfileId }?.let { profile ->
          initialDirectoryOpened = true
          backstack.add(
            RemoteBrowserScreen(
              profileId = profile.id,
              path = initialPath ?: profile.startPath,
              subtitlePicker = true,
              shareName = (initialShare ?: profile.share).takeIf { it.isNotBlank() },
            ),
          )
        }
      }
    }
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.remote_profiles_title)) },
          navigationIcon = {
            IconButton(
              onClick = {
                if (subtitlePicker) {
                  (context as? Activity)?.apply {
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                  }
                } else {
                  backstack.removeLastOrNull()
                }
              },
            ) {
              Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            }
          },
          actions = {
            IconButton(onClick = { backstack.add(RemoteProfileEditorScreen()) }) {
              Icon(Icons.Default.Add, stringResource(R.string.remote_add_profile))
            }
          },
        )
      },
    ) { padding ->
      Column(Modifier
        .fillMaxSize()
        .padding(padding)) {
        if (profiles.isEmpty()) {
          Text(stringResource(R.string.remote_no_profiles), Modifier.padding(24.dp))
        } else {
          LazyColumn(Modifier.fillMaxWidth()) {
            items(profiles, key = { it.id }) { profile ->
              // 长按命令菜单的展开状态与执行中状态，每个列表项独立持有。
              var menuExpanded by remember { mutableStateOf(false) }
              var executing by remember { mutableStateOf(false) }
              // 删除二次确认弹窗的显隐状态。
              var confirmDelete by remember { mutableStateOf(false) }
              // 长按触点相对前景 Box 的坐标（像素），用于让命令菜单跟随手指位置弹出。
              var pressOffset by remember { mutableStateOf(Offset.Zero) }
              val density = LocalDensity.current
              // 操作面板总宽度：两个按钮各 ACTION_BUTTON_WIDTH。
              val actionPanelWidthPx = with(density) {
                (ACTION_BUTTON_WIDTH * 2).dp.toPx()
              }
              // 自定义锚点拖拽：仅在 0（收起）与 -面板宽（展开）两个锚点之间吸附，
              // 滑动后停留在展开态，由用户手动点击编辑/删除，而非滑动即触发动作。
              val revealState = remember(profile.id, actionPanelWidthPx) {
                // 该 foundation 版本提供 (initialValue, anchors) 两参构造，
                // 位置阈值、速度阈值与动画规格均使用内置默认值，无需再手动传入。
                AnchoredDraggableState(
                  initialValue = RowRevealState.Settled,
                  anchors = DraggableAnchors {
                    RowRevealState.Settled at 0f
                    RowRevealState.Open at -actionPanelWidthPx
                  },
                )
              }
              Box(
                Modifier
                  .fillMaxWidth()
                  .anchoredDraggable(revealState, Orientation.Horizontal),
              ) {
                // 背景操作层：matchParentSize 不参与 Box 尺寸测量，只匹配前景确定后的尺寸。
                Row(
                  Modifier
                    .matchParentSize()
                    .align(Alignment.CenterEnd),
                  horizontalArrangement = Arrangement.End,
                ) {
                  SwipeActionButton(
                    onClick = {
                      // 先收起面板再跳转，避免从编辑页返回时仍处于展开态。
                      scope.launch { revealState.animateTo(RowRevealState.Settled) }
                      backstack.add(RemoteProfileEditorScreen(profile.id))
                    },
                    icon = Icons.Default.Edit,
                    label = stringResource(R.string.remote_action_edit),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                      .fillMaxHeight()
                      .width(ACTION_BUTTON_WIDTH.dp),
                  )
                  SwipeActionButton(
                    onClick = {
                      // 不直接删除，先弹出二次确认，避免误触滑动导致数据丢失。
                      confirmDelete = true
                    },
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.remote_action_delete),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                      .fillMaxHeight()
                      .width(ACTION_BUTTON_WIDTH.dp),
                  )
                }
                // 前景：随拖拽偏移平移，不透明背景保证收起时完全盖住操作层。
                Box(
                  Modifier
                    .offset { IntOffset(revealState.requireOffset().roundToInt(), 0) }
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    // 仅在 Initial 通道记录按下坐标、不消费事件，combinedClickable 仍正常处理点击/长按。
                    .pointerInput(profile.id) {
                      awaitPointerEventScope {
                        while (true) {
                          val event = awaitPointerEvent(PointerEventPass.Initial)
                          if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let { pressOffset = it.position }
                          }
                        }
                      }
                    },
                ) {
                  Row(
                    Modifier
                      .fillMaxWidth()
                      .combinedClickable(
                        onClick = {
                          // 面板展开时点击前景仅收起，不进入浏览器。
                          if (revealState.currentValue == RowRevealState.Open) {
                            scope.launch { revealState.animateTo(RowRevealState.Settled) }
                            return@combinedClickable
                          }
                          // 手动输入的共享名在打开浏览器后持久化；这里读取最新配置，
                          // 因为本页可能在浏览器更新存储值时仍留在回退栈中。
                          val latestProfile = runCatching {
                            store.getProfiles().firstOrNull { it.id == profile.id }
                          }.getOrNull() ?: profile
                          backstack.add(
                            RemoteBrowserScreen(
                              profileId = latestProfile.id,
                              path = latestProfile.startPath,
                              subtitlePicker = subtitlePicker,
                              // 非 SMB 协议无共享名，传 null，浏览器会跳过共享选择。
                              shareName = latestProfile.share.takeIf { it.isNotBlank() },
                            ),
                          )
                        },
                        // 长按：弹出快捷菜单（含网络唤醒；即使没有命令片段也可唤醒）。
                        onLongClick = {
                          menuExpanded = true
                        },
                      )
                      .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    Icon(Icons.Default.Folder, null)
                    Column(Modifier.weight(1f)) {
                      Text(profile.name)
                      Text(
                        "${profile.protocol.name} · ${profile.host}:${profile.port}",
                        style = MaterialTheme.typography.bodySmall,
                      )
                    }
                    if (executing) {
                      CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                  }
                  // 长按弹出的服务器命令菜单。
                  // 把长按触点坐标作为 offset，让菜单相对锚点（本 Box 左上角）偏移到手指位置。
                  val menuOffset = with(density) {
                    DpOffset(pressOffset.x.toDp(), pressOffset.y.toDp())
                  }
                  // 最多显示 N 条（N 取自设置，默认 3）：已执行的按最近执行时间倒序优先展示；
                  // 不足 N 条时，用最后添加的未执行命令（保持原序）补齐。
                  val menuCommands = remember(profile.commandSnippets, commandMenuLimit) {
                    val executed = profile.commandSnippets
                      .filter { it.lastExecutedAt != null }
                      .sortedByDescending { it.lastExecutedAt }
                    val notExecuted = profile.commandSnippets.filter { it.lastExecutedAt == null }
                    // 需补齐的条数：已执行不足 N 时，取最后添加的若干条未执行命令。
                    val fillerCount = (commandMenuLimit - executed.size).coerceAtLeast(0)
                    (executed + notExecuted.takeLast(fillerCount)).take(commandMenuLimit)
                  }
                  DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    offset = menuOffset,
                  ) {
                    // 首项：网络唤醒。点击后发送魔术包并 Toast 提示等待，不自动连接。
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.remote_wake_on_lan)) },
                      leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null) },
                      onClick = {
                        menuExpanded = false
                        // 未配置 MAC 时无法构造魔术包，提示先去编辑页填写。
                        if (profile.macAddress.isBlank()) {
                          Toast.makeText(context, wolMacMissingText, Toast.LENGTH_LONG).show()
                          return@DropdownMenuItem
                        }
                        scope.launch {
                          val result = withContext(Dispatchers.IO) {
                            runCatching {
                              WakeOnLan.send(
                                mac = profile.macAddress,
                                broadcastAddress = profile.wolBroadcastAddress,
                                port = profile.wolPort,
                              )
                            }
                          }
                          val toastText = result.fold(
                            onSuccess = { wolSentText },
                            onFailure = { wolErrorText.format(it.message.orEmpty()) },
                          )
                          Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
                        }
                      },
                    )
                    // 网络唤醒与常用命令之间的分隔线；无命令时不显示，避免出现悬空分隔线。
                    if (menuCommands.isNotEmpty()) HorizontalDivider()
                    menuCommands.forEach { snippet ->
                      DropdownMenuItem(
                        text = { Text(snippet.name) },
                        onClick = {
                          menuExpanded = false
                          executing = true
                          scope.launch {
                            // 在 IO 线程执行命令；成功执行（无异常）后回写最近执行时间并持久化。
                            val feedback = withContext(Dispatchers.IO) {
                              runCatching {
                                val result = runtime.executeCommand(profile, snippet)
                                val stamped = snippet.copy(
                                  lastExecutedAt = System.currentTimeMillis(),
                                )
                                val latest = store.getProfiles()
                                  .firstOrNull { it.id == profile.id } ?: profile
                                val newSnippets = latest.commandSnippets.map {
                                  if (it.id == snippet.id) stamped else it
                                }
                                store.save(latest.copy(commandSnippets = newSnippets))
                                result
                              }
                            }
                            executing = false
                            // 同步内存列表，让后续长按菜单的排序立即反映最新执行时间。
                            feedback.onSuccess {
                              profiles = profiles.map { p ->
                                if (p.id == profile.id) {
                                  p.copy(
                                    commandSnippets = p.commandSnippets.map {
                                      if (it.id == snippet.id) {
                                        it.copy(lastExecutedAt = System.currentTimeMillis())
                                      } else {
                                        it
                                      }
                                    },
                                  )
                                } else {
                                  p
                                }
                              }.toMutableList()
                            }
                            val toastText = feedback.fold(
                              onSuccess = { result ->
                                if (result.success) {
                                  cmdSuccessText
                                } else {
                                  cmdFailedText.format(result.exitCode)
                                }
                              },
                              onFailure = { cmdErrorText.format(it.message.orEmpty()) },
                            )
                            Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
                          }
                        },
                      )
                    }
                  }
                }
                // 删除二次确认弹窗：确认后才真正执行删除，取消或点击弹窗外则关闭。
                if (confirmDelete) {
                  AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(stringResource(R.string.remote_delete_profile)) },
                    text = { Text(stringResource(R.string.remote_delete_confirm_message)) },
                    confirmButton = {
                      TextButton(
                        onClick = {
                          confirmDelete = false
                          runCatching {
                            store.delete(profile.id)
                            runtime.forgetProfile(profile.id)
                            store.getProfiles()
                          }.onSuccess { profiles = it }.onFailure { message = it.message }
                        },
                      ) { Text(stringResource(R.string.generic_confirm)) }
                    },
                    dismissButton = {
                      TextButton(onClick = { confirmDelete = false }) {
                        Text(stringResource(android.R.string.cancel))
                      }
                    },
                  )
                }
              }
            }
          }
        }
        if (message != null) Text(message!!, Modifier.padding(20.dp))
      }
    }
  }
}

@Serializable
data class RemoteProfileEditorScreen(
  val profileId: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val store = koinInject<RemoteProfileStore>()
    val runtime = koinInject<RemoteRuntime>()
    val connectionFailedText = stringResource(R.string.remote_connection_failed)
    val profile = remember(profileId) {
      runCatching {
        profileId?.let { id -> store.getProfiles().firstOrNull { it.id == id } }
      }.getOrNull()
    }
    RemoteProfileEditor(
      initial = profile,
      onDismiss = { backstack.removeLastOrNull() },
      onSave = { candidate, reportStep ->
        runCatching {
          withContext(Dispatchers.IO) {
            reportStep(RemoteSaveStep.CONNECT)
            runtime.authenticate(candidate) { stage ->
              if (stage == SmbAuthStage.AUTHENTICATING) reportStep(RemoteSaveStep.AUTHENTICATE)
            }
            reportStep(RemoteSaveStep.SAVE)
            store.save(candidate)
          }
        }.fold(
          onSuccess = {
            runtime.forgetProfile(candidate.id)
            backstack.removeLastOrNull()
            null
          },
          onFailure = { it.message ?: connectionFailedText },
        )
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteProfileEditor(
  initial: RemoteProfile?,
  onDismiss: () -> Unit,
  onSave: suspend (RemoteProfile, reportStep: (RemoteSaveStep) -> Unit) -> String?,
) {
  val scope = rememberCoroutineScope()
  // 命令片段需要在新增/编辑/删除后立即落地，直接注入 store 做局部持久化。
  val store = koinInject<RemoteProfileStore>()
  // 单击命令行执行需要通过 runtime 下发 SSH 命令。
  val runtime = koinInject<RemoteRuntime>()
  var name by remember { mutableStateOf(initial?.name.orEmpty()) }
  var host by remember { mutableStateOf(initial?.host.orEmpty()) }
  // 协议类型：默认 SMB，兼容旧数据（旧配置反序列化时 protocol 取默认值 SMB）。
  var protocol by remember { mutableStateOf(initial?.protocol ?: ProtocolType.SMB) }
  var port by remember {
    mutableStateOf((initial?.port ?: protocol.defaultPort).toString())
  }
  var startPath by remember { mutableStateOf(initial?.startPath.orEmpty()) }
  var username by remember { mutableStateOf(initial?.username.orEmpty()) }
  var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
  var password by remember { mutableStateOf(initial?.password.orEmpty()) }
  var anonymous by remember { mutableStateOf(initial?.anonymous ?: false) }
  // SMB 经同主机 SSH 执行命令时使用的端口（文本，空则回退 22）。
  var commandPort by remember { mutableStateOf(initial?.commandPort?.toString().orEmpty()) }
  // 网络唤醒配置：MAC（可自动发现或手填）、广播地址（空=全网广播）、UDP 端口（默认 9）。
  var macAddress by remember { mutableStateOf(initial?.macAddress.orEmpty()) }
  var wolBroadcast by remember { mutableStateOf(initial?.wolBroadcastAddress.orEmpty()) }
  var wolPort by remember { mutableStateOf(initial?.wolPort?.toString() ?: "9") }
  // MAC 自动发现进行中标志，用于按钮 loading。
  var discoveringMac by remember { mutableStateOf(false) }
  // 命令片段列表，可增删改。
  val commandSnippets = remember {
    mutableStateListOf<CommandSnippet>().apply { addAll(initial?.commandSnippets.orEmpty()) }
  }
  // 当前选中的 Tab：0 连接信息，1 命令片段。
  var selectedTab by remember { mutableIntStateOf(0) }
  // 命令片段编辑整页：commandEditorShown 控制显隐，editingSnippet 为 null 表示新增，否则编辑该条。
  var commandEditorShown by remember { mutableStateOf(false) }
  var editingSnippet by remember { mutableStateOf<CommandSnippet?>(null) }
  var busy by remember { mutableStateOf(false) }
  var status by remember(initial?.id) { mutableStateOf<String?>(null) }
  var activeStep by remember { mutableStateOf<RemoteSaveStep?>(null) }
  var completedSteps by remember { mutableStateOf(setOf<RemoteSaveStep>()) }
  val connectionFailedText = stringResource(R.string.remote_connection_failed)
  val context = LocalContext.current
  // 命令执行三态 Toast 文案。
  val cmdSuccessText = stringResource(R.string.remote_command_success)
  val cmdFailedText = stringResource(R.string.remote_command_failed)
  val cmdErrorText = stringResource(R.string.remote_command_error)
  // MAC 自动发现失败时的提示文案。
  val wolDiscoverFailedText = stringResource(R.string.remote_wol_discover_failed)
  // 该协议是否支持命令片段（仅 SFTP/SMB）。
  val supportsCommands = protocol.supportsCommands()
  // 新建（initial == null）时只显示连接表单；仅在编辑已有存储且协议支持时才显示 Tab 容器。
  val showTabs = supportsCommands && initial != null
  fun profile() = RemoteProfile(
    id = initial?.id ?: java.util.UUID.randomUUID().toString(),
    name = name.trim(),
    host = host.trim(),
    protocol = protocol,
    // 保留浏览器中先前手动输入的共享名（仅 SMB 有意义）。
    port = port.toIntOrNull() ?: protocol.defaultPort,
    share = if (protocol == ProtocolType.SMB) initial?.share.orEmpty() else "",
    startPath = startPath.trim('/'),
    username = username,
    domain = if (protocol == ProtocolType.SMB) domain else "",
    password = password,
    anonymous = anonymous,
    commandSnippets = commandSnippets.toList(),
    commandPort = commandPort.toIntOrNull(),
    macAddress = macAddress.trim(),
    wolBroadcastAddress = wolBroadcast.trim(),
    // 端口非法时回退默认 9，保证保存的数据始终可用。
    wolPort = wolPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 9,
  )

  /**
   * 立即持久化命令片段：基于 store 中该 profile 的最新数据，仅替换 commandSnippets，
   * 不覆盖连接表单里尚未保存的字段；加密读写放在 IO 线程，失败写入 status 提示。
   */
  fun persistSnippets(updated: List<CommandSnippet>) {
    val id = initial?.id ?: return
    scope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          val stored = store.getProfiles().firstOrNull { it.id == id } ?: initial
          requireNotNull(stored)
          store.save(stored.copy(commandSnippets = updated))
        }
      }
      result.onFailure { status = it.message }
    }
  }

  /**
   * 执行一条命令并记录“最近执行时间”：
   * - 用 store 中最新的该 profile 执行，避免受未保存的表单改动影响；
   * - 只要命令真正执行（无异常，无论退出码），就回写 lastExecutedAt 并持久化；
   * - 同步更新内存中的 commandSnippets，最后给出三态 Toast。
   */
  fun executeSnippet(snippet: CommandSnippet) {
    val id = initial?.id ?: return
    scope.launch {
      val feedback = withContext(Dispatchers.IO) {
        runCatching {
          val targetProfile = store.getProfiles().firstOrNull { it.id == id } ?: initial
          requireNotNull(targetProfile)
          val result = runtime.executeCommand(targetProfile, snippet)
          val stamped = snippet.copy(lastExecutedAt = System.currentTimeMillis())
          val newSnippets = targetProfile.commandSnippets.map {
            if (it.id == snippet.id) stamped else it
          }
          store.save(targetProfile.copy(commandSnippets = newSnippets))
          result
        }
      }
      // 成功执行后同步内存列表的时间戳，保证本页 UI 与 store 一致。
      feedback.onSuccess {
        val index = commandSnippets.indexOfFirst { it.id == snippet.id }
        if (index >= 0) {
          commandSnippets[index] = commandSnippets[index].copy(
            lastExecutedAt = System.currentTimeMillis(),
          )
        }
      }
      val toastText = feedback.fold(
        onSuccess = { result ->
          if (result.success) cmdSuccessText else cmdFailedText.format(result.exitCode)
        },
        onFailure = { cmdErrorText.format(it.message.orEmpty()) },
      )
      Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
    }
  }

  /**
   * 根据当前填写的主机地址自动发现 MAC：读取系统 ARP 表，成功则回填输入框，
   * 失败（主机睡眠/无记录）时 Toast 提示用户手动填写。
   */
  fun discoverMac() {
    if (host.isBlank() || discoveringMac) return
    scope.launch {
      discoveringMac = true
      // 传入当前填写的端口（若合法），优先尝试该端口强制 ARP。
      val found = MacDiscovery.discover(host, port.toIntOrNull())
      discoveringMac = false
      if (found != null) {
        macAddress = found
      } else {
        Toast.makeText(context, wolDiscoverFailedText, Toast.LENGTH_LONG).show()
      }
    }
  }

  // 命令片段的新增/编辑以整页形式展示（替代原先的对话框），直接复用本页的 commandSnippets 状态。
  if (commandEditorShown) {
    CommandSnippetEditorPage(
      initial = editingSnippet,
      onDismiss = { commandEditorShown = false },
      onSave = { savedName, savedCommand ->
        val snippet = CommandSnippet(
          id = editingSnippet?.id ?: java.util.UUID.randomUUID().toString(),
          name = savedName,
          command = savedCommand,
        )
        val index = commandSnippets.indexOfFirst { it.id == snippet.id }
        if (index >= 0) commandSnippets[index] = snippet else commandSnippets.add(snippet)
        commandEditorShown = false
        // 确认后立即落地，无需等待外层“保存”按钮。
        persistSnippets(commandSnippets.toList())
      },
    )
    return
  }
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(stringResource(if (initial == null) R.string.remote_add_profile else R.string.remote_edit_profile))
        },
        navigationIcon = {
          IconButton(enabled = !busy, onClick = onDismiss) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
          }
        },
      )
    },
    bottomBar = {
      // 取消/保存按钮归属于“连接信息”tab：仅在连接表单（或新建无 tab）时显示；
      // 命令片段 tab 下隐藏，因为命令已即时落地，不再共用这两个按钮。
      if (!showTabs || selectedTab == 0) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            // 工作状态（保存中）底部栏会被步骤行/状态文字撑高，需避让系统导航栏 safe area。
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalAlignment = Alignment.End,
        ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(enabled = !busy, onClick = onDismiss) {
            Text(stringResource(android.R.string.cancel))
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (busy) {
              CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
              )
            }
            TextButton(
              enabled = !busy && name.isNotBlank() && host.isNotBlank() &&
                (port.toIntOrNull()?.let { it in 1..65535 } == true),
              onClick = {
                busy = true
                status = null
                activeStep = RemoteSaveStep.CONNECT
                completedSteps = emptySet()
                scope.launch {
                  // 保存前若 MAC 为空，尝试自动发现一次：发现到则回填并随本次保存写入，
                  // 发现不到（主机睡眠/无 ARP 记录）则静默跳过，不阻塞保存流程。
                  if (macAddress.isBlank() && host.isNotBlank()) {
                    MacDiscovery.discover(host, port.toIntOrNull())?.let { macAddress = it }
                  }
                  val failure = runCatching {
                    onSave(profile()) { step ->
                      // Every step before the reported one has finished.
                      completedSteps = RemoteSaveStep.entries.takeWhile { it != step }.toSet()
                      activeStep = step
                    }
                  }.fold({ it }, { it.message ?: connectionFailedText })
                  status = failure
                  busy = false
                  if (failure == null) {
                    activeStep = null
                    completedSteps = emptySet()
                  }
                }
              },
            ) { Text(stringResource(R.string.remote_save)) }
          }
        }
        if (activeStep != null || completedSteps.isNotEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.Start,
          ) {
            RemoteSaveStep.entries.forEach { step ->
              val state = when {
                step in completedSteps -> SaveStepState.DONE
                step == activeStep && busy -> SaveStepState.IN_PROGRESS
                step == activeStep -> SaveStepState.FAILED
                else -> SaveStepState.PENDING
              }
              SaveStepRow(step = step, state = state)
            }
          }
        }
        status?.let {
          Text(
            text = it,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 4.dp),
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      // 仅在编辑已有存储、且协议支持命令（SFTP/SMB）时显示 Tab 容器；新建时不显示。
      if (showTabs) {
        TabRow(selectedTabIndex = selectedTab) {
          Tab(
            selected = selectedTab == 0,
            onClick = { selectedTab = 0 },
            text = { Text(stringResource(R.string.remote_tab_connection)) },
          )
          Tab(
            selected = selectedTab == 1,
            onClick = { selectedTab = 1 },
            text = { Text(stringResource(R.string.remote_tab_commands)) },
          )
        }
      }
      if (!showTabs || selectedTab == 0) {
        // 连接信息表单（新建时直接显示；编辑时为第一个 Tab，可滚动）。
        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // 协议：新建时可通过 FilterChip 选择；编辑已有连接时协议不可更改，仅只读显示协议名称。
          Text(
            stringResource(R.string.remote_protocol),
            style = MaterialTheme.typography.titleSmall,
          )
          if (initial == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              ProtocolType.entries.forEach { type ->
                FilterChip(
                  selected = protocol == type,
                  onClick = {
                    if (protocol != type) {
                      protocol = type
                      // 切换协议时把端口重置为该协议默认端口。
                      port = type.defaultPort.toString()
                    }
                  },
                  label = { Text(stringResource(type.label)) },
                )
              }
            }
          } else {
            // 编辑态：以普通文本展示当前协议名称，不提供任何切换入口。
            Text(
              stringResource(protocol.label),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          OutlinedTextField(
            name,
            { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_name)) },
            singleLine = true,
          )
          OutlinedTextField(
            host,
            { host = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_host)) },
            singleLine = true,
          )
          OutlinedTextField(
            port,
            { port = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_port)) },
            singleLine = true,
          )
          OutlinedTextField(
            startPath,
            { startPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_start_path)) },
            singleLine = true,
          )
          OutlinedTextField(
            username,
            { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_username)) },
            singleLine = true,
          )
          // 域与 SSH 命令端口仅 SMB 协议显示。
          if (protocol == ProtocolType.SMB) {
            OutlinedTextField(
              domain,
              { domain = it },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.remote_domain)) },
              singleLine = true,
            )
            OutlinedTextField(
              commandPort,
              { commandPort = it },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.remote_command_port)) },
              singleLine = true,
            )
          }
          OutlinedTextField(
            password,
            { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(anonymous, { anonymous = it })
            Text(stringResource(R.string.remote_anonymous))
          }

          // 网络唤醒（Wake-on-LAN）配置区，四协议通用。
          Text(
            stringResource(R.string.remote_wol_section),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
          )
          // MAC 地址：可手动填写，也可点右侧按钮根据主机 IP 自动发现。
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            OutlinedTextField(
              macAddress,
              { macAddress = it },
              modifier = Modifier.weight(1f),
              label = { Text(stringResource(R.string.remote_wol_mac)) },
              singleLine = true,
              placeholder = { Text("AA:BB:CC:DD:EE:FF") },
            )
            // 自动发现中显示转圈，否则显示刷新图标。
            IconButton(onClick = { discoverMac() }, enabled = !discoveringMac && host.isNotBlank()) {
              if (discoveringMac) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
              } else {
                Icon(Icons.Default.Refresh, stringResource(R.string.remote_wol_mac_discover))
              }
            }
          }
          OutlinedTextField(
            wolBroadcast,
            { wolBroadcast = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_wol_broadcast)) },
            singleLine = true,
            // 留空提示用户默认使用全网广播。
            placeholder = { Text("255.255.255.255") },
          )
          OutlinedTextField(
            wolPort,
            { wolPort = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.remote_wol_port)) },
            singleLine = true,
          )
        }
      } else {
        // 第二个 Tab：服务器命令片段列表页。
        CommandSnippetList(
          snippets = commandSnippets,
          onAdd = {
            editingSnippet = null
            commandEditorShown = true
          },
          onEdit = {
            editingSnippet = it
            commandEditorShown = true
          },
          onDelete = {
            commandSnippets.remove(it)
            // 删除后立即落地，无需等待外层“保存”按钮。
            persistSnippets(commandSnippets.toList())
          },
          // 单击命令行并在确认弹窗确认后执行一次。
          onExecute = { executeSnippet(it) },
        )
      }
    }
  }
}

/** 协议类型对应的标签字符串。 */
private val ProtocolType.label: Int
  get() = when (this) {
    ProtocolType.FTP -> R.string.remote_protocol_ftp
    ProtocolType.SFTP -> R.string.remote_protocol_sftp
    ProtocolType.SMB -> R.string.remote_protocol_smb
    ProtocolType.WEBDAV -> R.string.remote_protocol_webdav
  }

/**
 * 服务器命令片段列表页：
 * - 顶部“+”添加新命令；
 * - 左滑露出编辑/删除按钮（与远程存储列表一致的滑动操作）；
 * - 单击命令行弹出执行确认，确认后执行一次命令。
 */
@Composable
private fun CommandSnippetList(
  snippets: androidx.compose.runtime.snapshots.SnapshotStateList<CommandSnippet>,
  onAdd: () -> Unit,
  onEdit: (CommandSnippet) -> Unit,
  onDelete: (CommandSnippet) -> Unit,
  onExecute: (CommandSnippet) -> Unit,
) {
  // 待执行的命令：非 null 时弹出确认弹窗。
  var pendingExecute by remember { mutableStateOf<CommandSnippet?>(null) }
  // 待删除的命令：非 null 时弹出删除确认弹窗，复用通用 ConfirmDialog。
  var pendingDelete by remember { mutableStateOf<CommandSnippet?>(null) }
  val density = LocalDensity.current
  val rowScope = rememberCoroutineScope()
  Column(Modifier.fillMaxSize()) {
    // 顶部操作行：右侧“+”添加新命令。
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.End,
    ) {
      IconButton(onClick = onAdd) {
        Icon(Icons.Default.Add, stringResource(R.string.remote_add_command))
      }
    }
    if (snippets.isEmpty()) {
      Text(stringResource(R.string.remote_commands_empty), Modifier.padding(24.dp))
    } else {
      LazyColumn(Modifier.fillMaxSize()) {
        items(snippets, key = { it.id }) { snippet ->
          val panelWidthPx = with(density) { (ACTION_BUTTON_WIDTH * 2).dp.toPx() }
          val revealState = remember(snippet.id, panelWidthPx) {
            AnchoredDraggableState(
              initialValue = RowRevealState.Settled,
              anchors = DraggableAnchors {
                RowRevealState.Settled at 0f
                RowRevealState.Open at -panelWidthPx
              },
            )
          }
          Box(
            Modifier
              .fillMaxWidth()
              .anchoredDraggable(revealState, Orientation.Horizontal),
          ) {
            // 背景操作层：matchParentSize 不参与尺寸测量，只匹配前景确定后的尺寸。
            Row(
              Modifier
                .matchParentSize()
                .align(Alignment.CenterEnd),
              horizontalArrangement = Arrangement.End,
            ) {
              SwipeActionButton(
                onClick = {
                  rowScope.launch { revealState.animateTo(RowRevealState.Settled) }
                  onEdit(snippet)
                },
                icon = Icons.Default.Edit,
                label = stringResource(R.string.remote_action_edit),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                  .fillMaxHeight()
                  .width(ACTION_BUTTON_WIDTH.dp),
              )
              SwipeActionButton(
                onClick = {
                  rowScope.launch { revealState.animateTo(RowRevealState.Settled) }
                  // 不直接删除，先弹确认，避免误触。
                  pendingDelete = snippet
                },
                icon = Icons.Default.Delete,
                label = stringResource(R.string.remote_action_delete),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                  .fillMaxHeight()
                  .width(ACTION_BUTTON_WIDTH.dp),
              )
            }
            // 前景：点击弹出执行确认，不透明背景保证收起时盖住操作层。
            Box(
              Modifier
                .offset { IntOffset(revealState.requireOffset().roundToInt(), 0) }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable { pendingExecute = snippet },
            ) {
              Column(
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 10.dp),
              ) {
                Text(snippet.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                  snippet.command,
                  style = MaterialTheme.typography.bodySmall,
                  maxLines = 2,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
  // 执行确认弹窗：确认后调用 onExecute，取消或点外部则关闭。
  pendingExecute?.let { target ->
    AlertDialog(
      onDismissRequest = { pendingExecute = null },
      title = { Text(stringResource(R.string.remote_execute_confirm_title)) },
      text = { Text(stringResource(R.string.remote_execute_confirm_message, target.name)) },
      confirmButton = {
        TextButton(
          onClick = {
            pendingExecute = null
            onExecute(target)
          },
        ) { Text(stringResource(R.string.generic_confirm)) }
      },
      dismissButton = {
        TextButton(onClick = { pendingExecute = null }) {
          Text(stringResource(android.R.string.cancel))
        }
      },
    )
  }
  // 删除确认弹窗：复用通用 ConfirmDialog，确认后才真正调用 onDelete。
  pendingDelete?.let { target ->
    ConfirmDialog(
      title = stringResource(R.string.remote_delete_command),
      subtitle = stringResource(R.string.remote_delete_command_confirm_message),
      onConfirm = {
        pendingDelete = null
        onDelete(target)
      },
      onCancel = { pendingDelete = null },
    )
  }
}

/**
 * 命令片段编辑整页：名称输入框 + 多行执行命令文本框，顶部带操作系统类型提示。
 *
 * 由 [RemoteProfileEditor] 在新增/编辑命令时整页展示，替代原先的对话框；
 * 表单状态在离开本页时通过 [onSave] 回传，命令片段数据仍由外层统一持有。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSnippetEditorPage(
  initial: CommandSnippet?,
  onDismiss: () -> Unit,
  onSave: (name: String, command: String) -> Unit,
) {
  var snippetName by remember { mutableStateOf(initial?.name.orEmpty()) }
  var snippetCommand by remember { mutableStateOf(initial?.command.orEmpty()) }
  val canSave = snippetName.isNotBlank() && snippetCommand.isNotBlank()

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(
              if (initial == null) R.string.remote_add_command else R.string.remote_edit_command,
            ),
          )
        },
        navigationIcon = {
          IconButton(onClick = onDismiss) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
          }
        },
        actions = {
          TextButton(
            enabled = canSave,
            onClick = { onSave(snippetName.trim(), snippetCommand) },
          ) {
            // 这里只把当前命令确认进列表，不持久化整个配置，故用“确认”而非“保存”，
            // 与外层 bottomBar 中保存整个远程存储的“保存”按钮区分开。
            Text(stringResource(R.string.generic_confirm))
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // 操作系统类型提示：浅色容器 + 信息图标，提醒按 Windows / Linux / macOS 区分命令语法。
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.secondaryContainer)
          .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Icon(
          Icons.Outlined.Info,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
          stringResource(R.string.remote_command_os_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
      OutlinedTextField(
        value = snippetName,
        onValueChange = { snippetName = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.remote_command_name)) },
        singleLine = true,
      )
      OutlinedTextField(
        value = snippetCommand,
        onValueChange = { snippetCommand = it },
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 200.dp),
        label = { Text(stringResource(R.string.remote_command_text)) },
        // 多行命令文本框，不设 singleLine。
      )
    }
  }
}

@Serializable
data class RemoteBrowserScreen(
  val profileId: String,
  val path: String = "",
  val subtitlePicker: Boolean = false,
  val shareName: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val runtime = koinInject<RemoteRuntime>()
    val profileStore = koinInject<RemoteProfileStore>()
    val browserPreferences = koinInject<RemoteBrowserPreferences>()
    val subtitlesPreferences = koinInject<SubtitlesPreferences>()
    val browserViewModel = remember(context) {
      val owner = context.findViewModelStoreOwner()
        ?: error("Remote browser must be hosted by a ViewModelStoreOwner")
      ViewModelProvider(owner)[RemoteBrowserViewModel::class.java]
    }
    // 字幕选择器打开的是“远程字幕文件列表”，不允许出现广告
    val listMode = RemoteBrowserListMode.of(subtitlePicker)
    // 字幕列表模式下根本不创建广告 ViewModel，从源头杜绝广告请求
    val adViewModel = remember(context, listMode) {
      if (listMode.allowsNativeAds) {
        val owner = context.findViewModelStoreOwner()
          ?: error("Remote browser must be hosted by a ViewModelStoreOwner")
        ViewModelProvider(owner)[RemoteNativeAdViewModel::class.java]
      } else {
        null
      }
    }
    val adPageKey = remember(profileId, shareName, path) { toAdPageKey() }
    val profile = runtime.profiles().firstOrNull { it.id == profileId }
    // 仅 SMB 协议需要先选择共享；FTP/SFTP/WebDAV 没有共享概念，直接进入目录列表。
    val selectingShare = profile?.protocol == ProtocolType.SMB && shareName.isNullOrBlank()
    var shares by remember(profileId) { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retryKey by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var manualShareDialogShown by remember { mutableStateOf(false) }
    var manualShareName by remember(profileId, shareName) { mutableStateOf(shareName.orEmpty()) }
    var sortOrder by remember(profileId, shareName, path) {
      mutableStateOf(
        runCatching {
          RemoteSortOrder.valueOf(browserPreferences.getSortOrder(profileId, shareName.orEmpty(), path))
        }
          .getOrDefault(RemoteSortOrder.NAME_ASC),
      )
    }
    val profileNotFoundText = stringResource(R.string.remote_profile_not_found)
    val unableAccessText = stringResource(R.string.remote_unable_access)
    val unableOpenText = stringResource(R.string.remote_unable_open)
    val noSharesText = stringResource(R.string.remote_no_shares)
    val retryText = stringResource(R.string.remote_retry)
    val enterShareText = stringResource(R.string.remote_enter_share_manually)
    val shareNameText = stringResource(R.string.remote_share_name_hint)
    val openText = stringResource(R.string.remote_open_share)
    val currentSortText = stringResource(sortOrder.label)
    var sortMenuShown by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun saveListPosition() {
      browserViewModel.saveScrollPosition(
        profileId = profileId,
        shareName = shareName.orEmpty(),
        path = path,
        position = listState.toRemoteDirectoryScrollPosition(),
      )
    }

    DisposableEffect(profileId, shareName, path, listState) {
      onDispose { saveListPosition() }
    }

    LaunchedEffect(sortOrder) {
      listState.scrollToItem(0)
    }
    // Shared loader used by both the initial/button-retry load and pull-to-refresh.
    // A refresh keeps the current list visible and only reports failures via snackbar.
    suspend fun loadContent(isRefresh: Boolean) {
      if (isRefresh) {
        isRefreshing = true
      } else {
        loading = true
        error = null
        shares = emptyList()
        entries = emptyList()
      }
      var failureMessage: String? = null
      if (profile == null) {
        failureMessage = profileNotFoundText
      } else if (selectingShare) {
        runCatching { withContext(Dispatchers.IO) { runtime.browsableShares(profile) } }
          .onSuccess { shares = it }
          .onFailure { failureMessage = it.message ?: unableAccessText }
      } else {
        val selectedProfile = profile.copy(share = shareName.orEmpty())
        runCatching { withContext(Dispatchers.IO) { runtime.list(selectedProfile, path) } }
          .onSuccess {
            entries = it.asSequence()
              .filter { entry ->
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                entry.directory || videoExtensions.contains(extension) ||
                  (subtitlePicker && subtitleExtensions.contains(extension))
              }
              .toList()
          }
          .onFailure { failureMessage = it.message ?: unableAccessText }
      }
      if (isRefresh) {
        isRefreshing = false
        if (failureMessage != null) {
          // Keep the currently visible content (list or error page) and report the
          // failure transiently instead of replacing the screen.
          snackbarHostState.showSnackbar(failureMessage)
        } else {
          // A successful refresh must clear a previously shown error page.
          error = null
        }
      } else {
        error = failureMessage
        loading = false
      }
    }

    LaunchedEffect(profileId, shareName, path, retryKey) {
      loadContent(isRefresh = false)
    }
    val sortedEntries = remember(entries, sortOrder) {
      val itemComparator = when (sortOrder) {
        RemoteSortOrder.NAME_ASC -> Comparator<RemoteEntry> { left, right -> NaturalSort.compare(left.name, right.name) }
        RemoteSortOrder.NAME_DESC -> Comparator<RemoteEntry> { left, right -> NaturalSort.compare(right.name, left.name) }
        RemoteSortOrder.MODIFIED_ASC -> compareBy<RemoteEntry> { it.modifiedTime }.thenBy(NaturalSort.comparator) { it.name }
        RemoteSortOrder.MODIFIED_DESC -> compareByDescending<RemoteEntry> { it.modifiedTime }.thenByDescending(NaturalSort.comparator) { it.name }
      }
      entries.sortedWith(
        Comparator { left, right ->
          val directoryOrder = (!left.directory).compareTo(!right.directory)
          if (directoryOrder != 0) directoryOrder else itemComparator.compare(left, right)
        },
      )
    }
    // 文件 + 广告位的混排数据（字幕列表模式下只有文件）
    val browserItems = remember(sortedEntries, listMode) {
      buildRemoteBrowserListItems(sortedEntries, listMode)
    }

    if (adViewModel != null) {
      val adSlotCount = browserItems.count { it is RemoteBrowserListItem.NativeAdSlot }
      // 列表刷新后变短：销毁已不存在的广告位上的广告。
      // 加载中或出错时列表是空的，不能据此清理，否则从子目录返回时缓存的广告会被误删。
      LaunchedEffect(adViewModel, adPageKey, adSlotCount, loading, error) {
        if (!loading && error == null) adViewModel.trimPage(adPageKey, adSlotCount)
      }
      // 页面退出：销毁已不在返回栈中的目录页的广告（含本页）。
      // 被子目录覆盖或屏幕旋转时本页仍在返回栈中，保留缓存以便恢复时复用；
      // Activity 结束时由 RemoteNativeAdViewModel.onCleared 统一销毁。
      DisposableEffect(adViewModel, adPageKey) {
        onDispose {
          val alivePages = backstack.filterIsInstance<RemoteBrowserScreen>().mapTo(HashSet()) { it.toAdPageKey() }
          adViewModel.retainPages(alivePages)
        }
      }
    }

    LaunchedEffect(profileId, shareName, path, retryKey, loading, error, browserItems, shares) {
      if (loading || error != null) return@LaunchedEffect

      // The list is composed with the newly loaded entries before applying the saved position.
      withFrameNanos { }

      val savedPosition = browserViewModel.scrollPosition(
        profileId = profileId,
        shareName = shareName.orEmpty(),
        path = path,
      ) ?: RemoteDirectoryScrollPosition(index = 0, offsetPx = 0)

      // Both the share list and the directory list restore their own saved position.
      // 目录列表必须按混排后的 browserItems 计算索引：广告位同样占用 LazyColumn 的 index。
      val restoreItems = if (selectingShare) shares else browserItems
      if (restoreItems.isNotEmpty()) {
        val anchorIndex = savedPosition.anchorPath
          ?.let { anchor ->
            if (selectingShare) shares.indexOf(anchor).takeIf { it >= 0 }
            else browserItems.indexOfFirst { it.key == anchor }.takeIf { it >= 0 }
          }

        listState.scrollToItem(
          index = (anchorIndex ?: savedPosition.index).coerceIn(0, restoreItems.lastIndex),
          // 命中文件锚点时保留原偏移：锚点前有部分可见的广告时它是负数，LazyList 会补齐锚点之前的内容，
          // 因此无论广告是否仍在缓存中，锚点文件都会回到原来的位置；仅按索引恢复时保持非负
          scrollOffset = if (anchorIndex != null) {
            savedPosition.offsetPx
          } else {
            savedPosition.offsetPx.coerceAtLeast(0)
          },
        )
      }
    }

    Scaffold(
      snackbarHost = { SnackbarHost(snackbarHostState) },
      topBar = {
        TopAppBar(
          title = {
            Text(
              profile?.let {
                if (selectingShare) it.name else "${it.name} / $shareName"
              } ?: stringResource(R.string.remote_browser_title),
            )
          },
          navigationIcon = {
            IconButton(
              onClick = {
                saveListPosition()
                backstack.removeLastOrNull()
              },
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
          },
          actions = {
            if (!selectingShare) {
              IconButton(onClick = { sortMenuShown = true }) {
                Icon(
                  Icons.AutoMirrored.Filled.Sort,
                  stringResource(R.string.remote_sort_current, currentSortText),
                )
              }
              DropdownMenu(expanded = sortMenuShown, onDismissRequest = { sortMenuShown = false }) {
                RemoteSortOrder.entries.forEach { order ->
                  DropdownMenuItem(
                    modifier = Modifier.background(
                      if (order == sortOrder) MaterialTheme.colorScheme.secondaryContainer
                      else MaterialTheme.colorScheme.surface,
                    ),
                    text = {
                      Text(
                        stringResource(order.label),
                        color = if (order == sortOrder) {
                          MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                          MaterialTheme.colorScheme.onSurface
                        },
                      )
                    },
                    onClick = {
                      if (order != sortOrder) {
                        browserViewModel.saveScrollPosition(
                          profileId = profileId,
                          shareName = shareName.orEmpty(),
                          path = path,
                          position = RemoteDirectoryScrollPosition(index = 0, offsetPx = 0),
                        )
                      }
                      sortOrder = order
                      browserPreferences.setSortOrder(profileId, shareName.orEmpty(), path, order.name)
                      sortMenuShown = false
                    },
                  )
                }
              }
            }
          },
        )
      },
    ) { padding ->
      Column(Modifier
        .fillMaxSize()
        .padding(padding)) {
        if (loading) {
          CircularProgressIndicator(Modifier.padding(24.dp))
        }
        PullToRefreshBox(
          isRefreshing = isRefreshing,
          onRefresh = { scope.launch { loadContent(isRefresh = true) } },
          modifier = Modifier.fillMaxSize(),
        ) {
          if (!loading) {
            when {
              // Show the real enumeration/authentication error first; the server may deny
              // share listing, but a manually entered share can still be usable.
              error != null -> {
                // PullToRefreshBox 是 Box，直接放多个子元素会重叠；用 Column 让文字与按钮纵向排列。
                Column(Modifier.fillMaxWidth()) {
                  Text(error!!, Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                  ) {
                    TextButton(onClick = { retryKey++ }) { Text(retryText) }
                    TextButton(onClick = { manualShareDialogShown = true }) { Text(enterShareText) }
                  }
                }
              }

              selectingShare && shares.isEmpty() -> {
                // 同样用 Column 避免空共享提示与按钮重叠。
                Column(Modifier.fillMaxWidth()) {
                  Text(noSharesText, Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
                  Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                  ) {
                    TextButton(onClick = { retryKey++ }) { Text(retryText) }
                    TextButton(onClick = { manualShareDialogShown = true }) { Text(enterShareText) }
                  }
                }
              }

              selectingShare -> {
                LazyColumn(state = listState) {
                  items(shares, key = { it }) { share ->
                    Row(
                      Modifier
                        .fillMaxWidth()
                        .clickable {
                          backstack.add(RemoteBrowserScreen(profileId, path, subtitlePicker, share))
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                      Icon(Icons.Default.Folder, null)
                      Text(share)
                    }
                  }
                  item {
                    TextButton(
                      onClick = { manualShareDialogShown = true },
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { Text(enterShareText) }
                  }
                }
              }

              else -> {
                Column(Modifier.fillMaxSize()) {
                  LazyColumn(state = listState) {
                    items(
                      items = browserItems,
                      key = { it.key },
                      contentType = { it.contentType },
                    ) { item ->
                      when (item) {
                        is RemoteBrowserListItem.FileEntry -> {
                          val entry = item.entry
                          val videoProfile = profile?.copy(share = shareName.orEmpty())
                          val isVideo = videoExtensions.contains(
                            entry.name.substringAfterLast('.', "").lowercase(),
                          )
                          val thumbnailModel = if (videoProfile != null && isVideo) {
                            RemoteVideoThumbnail(
                              profile = videoProfile,
                              path = entry.path,
                              size = entry.size,
                              modifiedTime = entry.modifiedTime,
                            )
                          } else {
                            null
                          }
                          RemoteFileRow(
                            entry = entry,
                            thumbnailModel = thumbnailModel,
                            onClick = {
                              saveListPosition()
                              if (entry.directory) {
                                backstack.add(RemoteBrowserScreen(profileId, entry.path, subtitlePicker, shareName))
                              } else {
                                scope.launch {
                                  runCatching {
                                    val selectedProfile = profile!!.copy(share = shareName.orEmpty())
                                    if (subtitlePicker) {
                                      val url = withContext(Dispatchers.IO) {
                                        runtime.stream(selectedProfile, entry.path)
                                      }
                                      (context as? Activity)?.apply {
                                        setResult(Activity.RESULT_OK, Intent().setData(Uri.parse(url)))
                                        finish()
                                      }
                                    } else {
                                      val autoLoadSubtitles = subtitlesPreferences.autoLoadExternal.get()
                                      val playlist = withContext(Dispatchers.IO) {
                                        runtime.playlist(
                                          selectedProfile,
                                          entry.path,
                                          sortOrder,
                                          autoLoadSubtitles,
                                        )
                                      }
                                      openRemotePlaylist(
                                        context,
                                        profileId,
                                        shareName.orEmpty(),
                                        path,
                                        playlist,
                                        entry.path,
                                      )
                                    }
                                  }.onFailure { error = it.message ?: unableOpenText }
                                }
                              }
                            },
                          )
                        }

                        is RemoteBrowserListItem.NativeAdSlot -> {
                          // 只有允许广告的列表才会生成广告位，这里 adViewModel 必然非空
                          if (adViewModel != null) {
                            // 凭证与列表项的组合生命周期绑定（RememberObserver），离开组合时自动释放
                            val slotHandle = remember(adViewModel, adPageKey, item.slotIndex) {
                              adViewModel.openSlot(RemoteAdSlotKey(adPageKey, item.slotIndex))
                            }
                            val slotState by slotHandle.state.collectAsState()
                            RemoteNativeAdSlot(state = slotState, variant = slotHandle.variant)
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    if (manualShareDialogShown) {
      AlertDialog(
        onDismissRequest = { manualShareDialogShown = false },
        title = { Text(enterShareText) },
        text = {
          OutlinedTextField(
            value = manualShareName,
            onValueChange = { manualShareName = it },
            label = { Text(shareNameText) },
            singleLine = true,
          )
        },
        confirmButton = {
          TextButton(
            enabled = manualShareName.trim().isNotBlank(),
            onClick = {
              val enteredShare = manualShareName.trim()
              runCatching {
                profile?.let {
                  profileStore.save(it.copy(share = enteredShare))
                  runtime.forgetProfile(it.id)
                }
              }.onFailure {
                error = it.message ?: unableAccessText
                return@TextButton
              }
              manualShareDialogShown = false
              backstack.add(RemoteBrowserScreen(profileId, path, subtitlePicker, enteredShare))
              manualShareName = ""
            },
          ) { Text(openText) }
        },
        dismissButton = {
          TextButton(onClick = { manualShareDialogShown = false }) {
            Text(stringResource(android.R.string.cancel))
          }
        },
      )
    }
  }
}

/** 目录页对应的广告缓存 key。 */
private fun RemoteBrowserScreen.toAdPageKey(): RemoteAdPageKey =
  RemoteAdPageKey.of(profileId, shareName.orEmpty(), path)

/** Steps shown under the button bar while the editor saves a connection. */
private enum class RemoteSaveStep { CONNECT, AUTHENTICATE, SAVE }

private enum class SaveStepState { PENDING, IN_PROGRESS, DONE, FAILED }

@Composable
private fun SaveStepRow(step: RemoteSaveStep, state: SaveStepState) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
      when (state) {
        SaveStepState.IN_PROGRESS -> CircularProgressIndicator(
          modifier = Modifier.size(16.dp),
          strokeWidth = 2.dp,
        )

        SaveStepState.DONE -> Icon(
          Icons.Default.Check,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.primary,
        )

        SaveStepState.FAILED -> Icon(
          Icons.Default.Close,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.error,
        )

        SaveStepState.PENDING -> Box(
          Modifier
            .size(6.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), CircleShape),
        )
      }
    }
    Text(
      text = step.label(),
      style = MaterialTheme.typography.bodyMedium,
      color = when (state) {
        SaveStepState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
        SaveStepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        SaveStepState.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
        SaveStepState.FAILED -> MaterialTheme.colorScheme.error
      },
    )
  }
}

@Composable
private fun RemoteSaveStep.label(): String = when (this) {
  RemoteSaveStep.CONNECT -> stringResource(R.string.remote_save_step_connect)
  RemoteSaveStep.AUTHENTICATE -> stringResource(R.string.remote_save_step_authenticate)
  RemoteSaveStep.SAVE -> stringResource(R.string.remote_save_step_save)
}

private fun LazyListState.toRemoteDirectoryScrollPosition(): RemoteDirectoryScrollPosition {
  val index = firstVisibleItemIndex
  // 以第一个可见的“文件行”为锚点，跳过广告位：广告位高度不固定（未加载/已回收时为 0），
  // 若以它为锚点，返回时广告已被回收，列表就会整体错位一个广告的高度。
  val anchorItem = layoutInfo.visibleItemsInfo.firstOrNull {
    it.index >= index && it.contentType != RemoteBrowserListItemType.NATIVE_AD
  } ?: return RemoteDirectoryScrollPosition(index = index, offsetPx = firstVisibleItemScrollOffset)
  return RemoteDirectoryScrollPosition(
    index = anchorItem.index,
    // 锚点就是第一个可见项时，等于 firstVisibleItemScrollOffset；
    // 锚点上方有部分可见的广告时为负数，恢复时 LazyList 会补齐锚点之前的内容
    offsetPx = -anchorItem.offset,
    anchorPath = anchorItem.key.toString(),
  )
}

private tailrec fun Context.findViewModelStoreOwner(): ViewModelStoreOwner? = when (this) {
  is ViewModelStoreOwner -> this
  is ContextWrapper -> baseContext.findViewModelStoreOwner()
  else -> null
}

private val RemoteSortOrder.label: Int
  get() = when (this) {
    RemoteSortOrder.NAME_ASC -> R.string.remote_sort_name_asc
    RemoteSortOrder.NAME_DESC -> R.string.remote_sort_name_desc
    RemoteSortOrder.MODIFIED_ASC -> R.string.remote_sort_modified_asc
    RemoteSortOrder.MODIFIED_DESC -> R.string.remote_sort_modified_desc
  }

private fun openRemotePlaylist(
  context: android.content.Context,
  profileId: String,
  shareName: String,
  directoryPath: String,
  playlist: RemotePlaylist,
  selectedPath: String,
) {
  val items = playlist.items
  if (items.isEmpty()) return
  val selectedIndex = items.indexOfFirst { it.remotePath == selectedPath }.takeIf { it >= 0 } ?: 0
  val intent = Intent(Intent.ACTION_VIEW).setClass(context, PlayerActivity::class.java)
    .setData(android.net.Uri.parse(items[selectedIndex].streamUrl))
    .putStringArrayListExtra("playlist_urls", ArrayList(items.map { it.streamUrl }))
    .putStringArrayListExtra("playlist_titles", ArrayList(items.map { it.name }))
    .putExtra("playlist_start_index", selectedIndex)
    // 以下 Intent extra 字符串值保持原样，与 PlayerActivity 中的读取逻辑匹配
    .putExtra("smb_profile_id", profileId)
    .putExtra("smb_share", shareName)
    .putExtra("smb_directory_path", directoryPath)
  if (playlist.subtitles.isNotEmpty()) {
    // 经现有 subs/subs.enable 机制传递：首条自动选中，其余在字幕轨列表中可选。
    val subtitleUris = playlist.subtitles.map { Uri.parse(it.streamUrl) }.toTypedArray()
    intent.putExtra("subs", subtitleUris)
    intent.putExtra("subs.enable", arrayOf(subtitleUris.first()))
  }
  context.startActivity(intent)
}
