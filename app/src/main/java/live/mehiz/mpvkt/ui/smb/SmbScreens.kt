package live.mehiz.mpvkt.ui.smb

import android.content.Intent
import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import live.mehiz.mpvkt.R
import live.mehiz.mpvkt.network.smb.SmbEntry
import live.mehiz.mpvkt.network.smb.SmbBrowserPreferences
import live.mehiz.mpvkt.network.smb.SmbPlaybackItem
import live.mehiz.mpvkt.network.smb.SmbProfile
import live.mehiz.mpvkt.network.smb.SmbProfileStore
import live.mehiz.mpvkt.network.smb.SmbRuntime
import live.mehiz.mpvkt.network.smb.SmbSortOrder
import live.mehiz.mpvkt.presentation.Screen
import live.mehiz.mpvkt.ui.player.PlayerActivity
import live.mehiz.mpvkt.ui.player.videoExtensions
import live.mehiz.mpvkt.ui.utils.LocalBackStack
import org.koin.compose.koinInject

@Serializable
data class SmbProfilesScreen(
  val subtitlePicker: Boolean = false,
  val initialProfileId: String? = null,
  val initialPath: String? = null,
  val initialShare: String? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val store = koinInject<SmbProfileStore>()
    val runtime = koinInject<SmbRuntime>()
    val loadedProfiles = remember { runCatching { store.getProfiles() } }
    var profiles by remember { mutableStateOf(loadedProfiles.getOrDefault(emptyList())) }
    var editing by remember { mutableStateOf<SmbProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(loadedProfiles.exceptionOrNull()?.message) }
    var initialDirectoryOpened by remember { mutableStateOf(false) }
    LaunchedEffect(initialProfileId, initialPath, initialShare, profiles) {
      if (!initialDirectoryOpened && subtitlePicker && initialProfileId != null) {
        profiles.firstOrNull { it.id == initialProfileId }?.let { profile ->
          initialDirectoryOpened = true
          backstack.add(
            SmbBrowserScreen(
              profileId = profile.id,
              path = initialPath ?: profile.startPath,
              subtitlePicker = true,
              shareName = initialShare?.takeIf { it.isNotBlank() },
            ),
          )
        }
      }
    }
    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text(stringResource(R.string.smb_profiles_title)) },
          navigationIcon = {
            IconButton(onClick = {
              if (subtitlePicker) {
                (context as? Activity)?.apply {
                  setResult(Activity.RESULT_CANCELED)
                  finish()
                }
              } else {
                backstack.removeLastOrNull()
              }
            }) {
              Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            }
          },
          actions = {
            IconButton(onClick = { editing = null; showEditor = true }) {
              Icon(Icons.Default.Add, stringResource(R.string.smb_add_profile))
            }
          },
        )
      },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        if (profiles.isEmpty()) {
          Text(stringResource(R.string.smb_no_profiles), Modifier.padding(24.dp))
        } else {
          LazyColumn(Modifier.fillMaxWidth()) {
            items(profiles, key = { it.id }) { profile ->
              Row(
                Modifier.fillMaxWidth().clickable {
                  backstack.add(
                    SmbBrowserScreen(
                      profileId = profile.id,
                      path = profile.startPath,
                      subtitlePicker = subtitlePicker,
                    ),
                  )
                }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Icon(Icons.Default.Folder, null)
                Column(Modifier.weight(1f)) {
                  Text(profile.name)
                  Text("${profile.host}:${profile.port}")
                }
                IconButton(onClick = { editing = profile; showEditor = true }) {
                  Icon(Icons.Default.Edit, stringResource(R.string.smb_edit_profile))
                }
                IconButton(onClick = {
                  runCatching {
                    store.delete(profile.id)
                    runtime.forgetProfile(profile.id)
                    store.getProfiles()
                  }.onSuccess { profiles = it }.onFailure { message = it.message }
                }) {
                  Icon(Icons.Default.Delete, stringResource(R.string.smb_delete_profile))
                }
              }
            }
          }
        }
        if (message != null) Text(message!!, Modifier.padding(20.dp))
      }
    }
    if (showEditor) {
      SmbProfileEditor(
        initial = editing,
        onDismiss = { showEditor = false },
        onSave = { candidate ->
          runCatching {
            withContext(Dispatchers.IO) {
              runtime.authenticate(candidate)
              store.save(candidate)
              store.getProfiles()
            }
          }.onSuccess {
            runtime.forgetProfile(candidate.id)
            profiles = it
            showEditor = false
            message = null
          }.fold(
            onSuccess = { null },
            onFailure = { it.message ?: context.getString(R.string.smb_connection_failed) },
          )
        },
      )
    }
  }
}

@Composable
private fun SmbProfileEditor(
  initial: SmbProfile?,
  onDismiss: () -> Unit,
  onSave: suspend (SmbProfile) -> String?,
) {
  val scope = rememberCoroutineScope()
  var name by remember { mutableStateOf(initial?.name.orEmpty()) }
  var host by remember { mutableStateOf(initial?.host.orEmpty()) }
  var port by remember { mutableStateOf((initial?.port ?: 445).toString()) }
  var startPath by remember { mutableStateOf(initial?.startPath.orEmpty()) }
  var username by remember { mutableStateOf(initial?.username.orEmpty()) }
  var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
  var password by remember { mutableStateOf(initial?.password.orEmpty()) }
  var anonymous by remember { mutableStateOf(initial?.anonymous ?: false) }
  var busy by remember { mutableStateOf(false) }
  var status by remember(initial?.id) { mutableStateOf<String?>(null) }
  val connectionFailedText = stringResource(R.string.smb_connection_failed)
  fun profile() = SmbProfile(
    id = initial?.id ?: java.util.UUID.randomUUID().toString(), name = name.trim(), host = host.trim(),
    // A share is selected after the connection is opened and is never part of the saved profile.
    port = port.toIntOrNull() ?: 445, share = "", startPath = startPath.trim('/'),
    username = username, domain = domain, password = password, anonymous = anonymous,
  )
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(if (initial == null) R.string.smb_add_profile else R.string.smb_edit_profile)) },
    text = {
      Column(
        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.smb_name)) }, singleLine = true)
        OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.smb_host)) }, singleLine = true)
        OutlinedTextField(port, { port = it }, label = { Text(stringResource(R.string.smb_port)) }, singleLine = true)
        OutlinedTextField(startPath, { startPath = it }, label = { Text(stringResource(R.string.smb_start_path)) }, singleLine = true)
        OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.smb_username)) }, singleLine = true)
        OutlinedTextField(domain, { domain = it }, label = { Text(stringResource(R.string.smb_domain)) }, singleLine = true)
        OutlinedTextField(
          password,
          { password = it },
          label = { Text(stringResource(R.string.smb_password)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
          androidx.compose.material3.Checkbox(anonymous, { anonymous = it })
          Text(stringResource(R.string.smb_anonymous))
        }
        if (busy) CircularProgressIndicator()
        status?.let { Text(it) }
      }
    },
    confirmButton = {
      TextButton(
        enabled = !busy && name.isNotBlank() && host.isNotBlank() &&
          (port.toIntOrNull()?.let { it in 1..65535 } == true),
        onClick = {
          busy = true
          scope.launch {
            status = runCatching { onSave(profile()) }
              .fold({ it }, { it.message ?: connectionFailedText })
            busy = false
          }
        },
      ) { Text(stringResource(R.string.smb_save)) }
    },
    dismissButton = {
      TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
    },
  )
}

@Serializable
data class SmbBrowserScreen(
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
    val runtime = koinInject<SmbRuntime>()
    val browserPreferences = koinInject<SmbBrowserPreferences>()
    val profile = runtime.profiles().firstOrNull { it.id == profileId }
    val selectingShare = shareName.isNullOrBlank()
    var shares by remember(profileId) { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retryKey by remember { mutableStateOf(0) }
    var manualShareDialogShown by remember { mutableStateOf(false) }
    var manualShareName by remember { mutableStateOf("") }
    var sortOrder by remember(profileId, shareName, path) {
      mutableStateOf(
        runCatching {
          SmbSortOrder.valueOf(browserPreferences.getSortOrder(profileId, shareName.orEmpty(), path))
        }
          .getOrDefault(SmbSortOrder.NAME_ASC),
      )
    }
    val profileNotFoundText = stringResource(R.string.smb_profile_not_found)
    val unableAccessText = stringResource(R.string.smb_unable_access)
    val unableOpenText = stringResource(R.string.smb_unable_open)
    val noSharesText = stringResource(R.string.smb_no_shares)
    val retryText = stringResource(R.string.smb_retry)
    val enterShareText = stringResource(R.string.smb_enter_share_manually)
    val shareNameText = stringResource(R.string.smb_share_name_hint)
    val openText = stringResource(R.string.smb_open_share)
    val currentSortText = stringResource(sortOrder.label)
    var sortMenuShown by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(sortOrder) {
      listState.scrollToItem(0)
    }
    LaunchedEffect(profileId, shareName, path, retryKey) {
      loading = true
      error = null
      shares = emptyList()
      entries = emptyList()
      if (profile == null) error = profileNotFoundText
      else if (selectingShare) {
        runCatching { withContext(Dispatchers.IO) { runtime.browsableShares(profile) } }
          .onSuccess { shares = it }
          .onFailure { error = it.message ?: unableAccessText }
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
          .onFailure { error = it.message ?: unableAccessText }
      }
      loading = false
    }
    val sortedEntries = remember(entries, sortOrder) {
      val itemComparator = when (sortOrder) {
        SmbSortOrder.NAME_ASC -> compareBy<SmbEntry> { it.name.lowercase() }.thenBy { it.name }
        SmbSortOrder.NAME_DESC -> compareByDescending<SmbEntry> { it.name.lowercase() }.thenByDescending { it.name }
        SmbSortOrder.MODIFIED_ASC -> compareBy<SmbEntry> { it.modifiedTime }.thenBy { it.name.lowercase() }
        SmbSortOrder.MODIFIED_DESC -> compareByDescending<SmbEntry> { it.modifiedTime }.thenBy { it.name.lowercase() }
      }
      entries.sortedWith(Comparator { left, right ->
        val directoryOrder = (!left.directory).compareTo(!right.directory)
        if (directoryOrder != 0) directoryOrder else itemComparator.compare(left, right)
      })
    }
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              profile?.let {
                if (selectingShare) it.name else "${it.name} / $shareName"
              } ?: stringResource(R.string.smb_browser_title),
            )
          },
          navigationIcon = { IconButton(onClick = backstack::removeLastOrNull) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) } },
          actions = {
            if (!selectingShare) {
              IconButton(onClick = { sortMenuShown = true }) {
                Icon(
                  Icons.AutoMirrored.Filled.Sort,
                  stringResource(R.string.smb_sort_current, currentSortText),
                )
              }
              DropdownMenu(expanded = sortMenuShown, onDismissRequest = { sortMenuShown = false }) {
                SmbSortOrder.entries.forEach { order ->
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
      Column(Modifier.fillMaxSize().padding(padding)) {
        if (loading) CircularProgressIndicator(Modifier.padding(24.dp))
        if (!loading) {
          when {
            // Keep enumeration failures user-readable: the server may deny share listing,
            // but a manually entered share can still be usable.
            selectingShare && profile != null && (error != null || shares.isEmpty()) -> {
              Text(noSharesText, Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { retryKey++ }) { Text(retryText) }
                TextButton(onClick = { manualShareDialogShown = true }) { Text(enterShareText) }
              }
            }
            error != null -> {
              Text(error!!, Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { retryKey++ }) { Text(retryText) }
                TextButton(onClick = { manualShareDialogShown = true }) { Text(enterShareText) }
              }
            }
            selectingShare -> {
              LazyColumn(state = listState) {
                items(shares, key = { it }) { share ->
                  Row(
                    Modifier.fillMaxWidth().clickable {
                      backstack.add(SmbBrowserScreen(profileId, path, subtitlePicker, share))
                    }.padding(horizontal = 20.dp, vertical = 14.dp),
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
              if (path.isNotBlank()) {
                Text(stringResource(R.string.smb_parent_directory), Modifier.fillMaxWidth().clickable {
                  val parent = path.trim('/').substringBeforeLast('/', "")
                  backstack.add(SmbBrowserScreen(profileId, parent, subtitlePicker, shareName))
                }.padding(20.dp))
              }
              LazyColumn(state = listState) {
                items(sortedEntries, key = { it.path }) { entry ->
                  Row(Modifier.fillMaxWidth().clickable {
                    if (entry.directory) {
                      backstack.add(SmbBrowserScreen(profileId, entry.path, subtitlePicker, shareName))
                    } else {
                      scope.launch {
                        runCatching {
                          val selectedProfile = profile!!.copy(share = shareName.orEmpty())
                          if (subtitlePicker) {
                            val url = withContext(Dispatchers.IO) { runtime.stream(selectedProfile, entry.path) }
                            (context as? Activity)?.apply {
                              setResult(Activity.RESULT_OK, Intent().setData(Uri.parse(url)))
                              finish()
                            }
                          } else {
                            val items = withContext(Dispatchers.IO) {
                              runtime.playlist(selectedProfile, entry.path, sortOrder)
                            }
                            openSmbPlaylist(context, profileId, shareName.orEmpty(), path, items, entry.path)
                          }
                        }.onFailure { error = it.message ?: unableOpenText }
                      }
                    }
                  }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.Movie, null)
                    Text(entry.name, Modifier.padding(start = 12.dp))
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
            enabled = manualShareName.isNotBlank(),
            onClick = {
              manualShareDialogShown = false
              backstack.add(SmbBrowserScreen(profileId, path, subtitlePicker, manualShareName.trim()))
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

private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub")

private val SmbSortOrder.label: Int
  get() = when (this) {
    SmbSortOrder.NAME_ASC -> R.string.smb_sort_name_asc
    SmbSortOrder.NAME_DESC -> R.string.smb_sort_name_desc
    SmbSortOrder.MODIFIED_ASC -> R.string.smb_sort_modified_asc
    SmbSortOrder.MODIFIED_DESC -> R.string.smb_sort_modified_desc
  }

private fun openSmbPlaylist(
  context: android.content.Context,
  profileId: String,
  shareName: String,
  directoryPath: String,
  items: List<SmbPlaybackItem>,
  selectedPath: String,
) {
  if (items.isEmpty()) return
  val selectedIndex = items.indexOfFirst { it.remotePath == selectedPath }.takeIf { it >= 0 } ?: 0
  val intent = Intent(Intent.ACTION_VIEW).setClass(context, PlayerActivity::class.java)
    .setData(android.net.Uri.parse(items[selectedIndex].streamUrl))
    .putStringArrayListExtra("playlist_urls", ArrayList(items.map { it.streamUrl }))
    .putStringArrayListExtra("playlist_titles", ArrayList(items.map { it.name }))
    .putExtra("playlist_start_index", selectedIndex)
    .putExtra("smb_profile_id", profileId)
    .putExtra("smb_share", shareName)
    .putExtra("smb_directory_path", directoryPath)
  context.startActivity(intent)
}
