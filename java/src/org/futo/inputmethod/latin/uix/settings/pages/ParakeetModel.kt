package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.*
import org.futo.voiceinput.shared.provisioning.ParakeetStore

internal object ParakeetImportUi {
    data class State(val busy: Boolean = false, val bytes: Long = 0, val error: String? = null)
    val state = kotlinx.coroutines.flow.MutableStateFlow(State())
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private var job: kotlinx.coroutines.Job? = null
    @Synchronized fun cancel() { job?.cancel() }
    @Synchronized fun start(store: org.futo.voiceinput.shared.provisioning.ParakeetStore,
                            sources: List<() -> java.io.InputStream> = emptyList(), delete: Boolean = false) {
        if(state.value.busy) return
        state.value = State(busy = true)
        job = scope.launch {
            try {
                val owner = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]!!
                if(delete) store.delete() else store.importFiles(sources,
                    { bytes, _ -> state.value = State(true, bytes) }, { if(!owner.isActive) throw kotlinx.coroutines.CancellationException() })
                state.value = State()
            } catch(e: kotlinx.coroutines.CancellationException) { state.value = State() }
            catch(e: Exception) {
                val noSpace = generateSequence<Throwable>(e) { it.cause }.any {
                    it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC ||
                        it.message?.contains("ENOSPC") == true }
                state.value = State(error = if(noSpace) "Not enough storage. Free space and import again." else e.message ?: "Import or deletion failed")
            }
        }
    }
}

@Composable
fun ParakeetModelScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { ParakeetStore(context.applicationContext.filesDir) }
    DisposableEffect(Unit) { onDispose { ParakeetImportUi.cancel() } }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ParakeetImportUi.start(store, uris.distinct().map { uri ->
            { context.applicationContext.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Cannot open selected file") }
        })
    }
    val state by ParakeetImportUi.state.collectAsState()
    val ready = remember(state) { store.isReady() }
    ParakeetModelContent(navController, ready, state, store.totalBytes,
        store.artifacts.map { it.name to it.url },
        onImport = { picker.launch(arrayOf("*/*")) },
        onDelete = { ParakeetImportUi.start(store, delete = true) })
}

@Composable
private fun ParakeetModelContent(
    navController: NavHostController,
    ready: Boolean,
    state: ParakeetImportUi.State,
    totalBytes: Long,
    sources: List<Pair<String, String>>,
    onImport: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.parakeet_delete_title)) },
            text = { Text(stringResource(R.string.parakeet_delete_body)) },
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.parakeet_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    ScrollableList {
        ScreenTitle(stringResource(R.string.parakeet_model), showBack = true, navController)
        SettingItem(title = "Parakeet TDT 0.6B v2", subtitle = stringResource(
            R.string.parakeet_details, stringResource(
                if (state.busy) R.string.parakeet_importing else if (ready) R.string.parakeet_ready else R.string.parakeet_not_ready))) {}
        Spacer(Modifier.height(32.dp))
        ScreenTitle(stringResource(R.string.parakeet_actions))
        if (state.busy) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.parakeet_progress, state.bytes / 1000000, totalBytes / 1000000),
                    style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (state.bytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            }
        }
        SettingItem(title = stringResource(R.string.parakeet_download),
            subtitle = stringResource(R.string.parakeet_import_hint),
            disabled = state.busy || android.os.Build.VERSION.SDK_INT < 27, onClick = onImport) {}
        SettingItem(title = stringResource(R.string.parakeet_delete), disabled = state.busy || !ready,
            onClick = { confirmDelete = true }) {}
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
        Spacer(Modifier.height(32.dp))
        ScreenTitle(stringResource(R.string.parakeet_get_files))
        sources.forEach { (name, url) ->
            NavigationItem(title = name, style = NavigationItemStyle.Misc, navigate = {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            })
        }
        Text(stringResource(R.string.parakeet_tokens_tip), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp, 12.dp))
        SettingItem(title = stringResource(R.string.parakeet_licenses)) {}
    }
}

@Preview(showBackground = true)
@Composable
private fun ParakeetModelPreview() {
    ParakeetModelContent(rememberNavController(), false, ParakeetImportUi.State(), 661932380,
        listOf("encoder.int8.onnx" to "", "decoder.int8.onnx" to "", "joiner.int8.onnx" to "",
            "tokens.txt" to "", "silero_vad.onnx" to ""), {}, {})
}

@Preview(showBackground = true)
@Composable
private fun ParakeetImportPreview() {
    ParakeetModelContent(rememberNavController(), false,
        ParakeetImportUi.State(busy = true, bytes = 280000000), 661932380, emptyList(), {}, {})
}

@Preview(showBackground = true)
@Composable
private fun ParakeetReadyPreview() {
    ParakeetModelContent(rememberNavController(), true, ParakeetImportUi.State(),
        661932380, emptyList(), {}, {})
}

@Preview(showBackground = true)
@Composable
private fun ParakeetErrorPreview() {
    ParakeetModelContent(rememberNavController(), false,
        ParakeetImportUi.State(error = stringResource(R.string.parakeet_error)),
        661932380, emptyList(), {}, {})
}
