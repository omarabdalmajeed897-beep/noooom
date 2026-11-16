package com.compressor.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compressor.app.pdf.*
import com.compressor.app.ui.theme.CompressorTheme
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: CompressorViewModel = viewModel()
            val state by vm.uiState.collectAsState()

            val baseContext = LocalContext.current
            val localizedContext = remember(state.language, baseContext) {
                val locale = Locale(state.language.tag)
                val config = Configuration(baseContext.resources.configuration).apply {
                    setLocale(locale)
                    setLayoutDirection(locale)
                }
                baseContext.createConfigurationContext(config)
            }

            val layoutDirection = if (state.language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalLayoutDirection provides layoutDirection
            ) {
                CompressorTheme(preference = state.themePreference) {
                    CompressorAppShell(viewModel = vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressorAppShell(viewModel: CompressorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val pickPdfsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addSelectedUris(uris)
        }
    }

    val saveToFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { targetUri ->
        val source = pendingSaveFile
        pendingSaveFile = null
        if (targetUri != null && source != null && source.exists()) {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            Toast.makeText(context, context.getString(R.string.saved_toast), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.toggleLanguage() }) {
                        Text(if (state.language == AppLanguage.ENGLISH) "العربية" else "EN")
                    }
                    IconButton(onClick = { viewModel.cycleTheme() }) {
                        Icon(
                            imageVector = when (state.themePreference) {
                                ThemePreference.DARK -> Icons.Outlined.DarkMode
                                ThemePreference.LIGHT -> Icons.Outlined.LightMode
                                ThemePreference.SYSTEM -> Icons.Outlined.BrightnessAuto
                            },
                            contentDescription = stringResource(R.string.theme_toggle)
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.activeTab == MainTab.COMPRESS,
                    onClick = { viewModel.setTab(MainTab.COMPRESS) },
                    icon = { Icon(Icons.Outlined.Compress, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_compress)) }
                )
                NavigationBarItem(
                    selected = state.activeTab == MainTab.HISTORY,
                    onClick = { viewModel.setTab(MainTab.HISTORY) },
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_history)) }
                )
            }
        }
    ) { padding ->
        if (state.activeTab == MainTab.COMPRESS) {
            CompressScreen(
                state = state,
                padding = padding,
                onPickPdfs = { pickPdfsLauncher.launch(arrayOf("application/pdf")) },
                onSelectLevel = viewModel::setCompressionLevel,
                onCompressAll = viewModel::compressAll,
                onRemoveItem = viewModel::removeQueueItem,
                onClearQueue = viewModel::clearQueue,
                onSaveAs = { file, name ->
                    pendingSaveFile = file
                    saveToFolderLauncher.launch(name)
                },
                onPreview = { name, file -> viewModel.openPreview(name, file) },
                onShare = { file -> shareCompressedPdf(context, file) },
                onOpen = { file -> openCompressedPdf(context, file) },
                onRequestRename = { id, current -> renameTarget = id to current }
            )
        } else {
            HistoryScreen(
                entries = state.history,
                padding = padding,
                onSaveAs = { file, name ->
                    pendingSaveFile = file
                    saveToFolderLauncher.launch(name)
                },
                onPreview = { name, file -> viewModel.openPreview(name, file) },
                onShare = { file -> shareCompressedPdf(context, file) },
                onRequestRename = { id, current -> renameTarget = id to current },
                onDelete = viewModel::deleteHistoryEntry,
                onClearAll = viewModel::clearHistory
            )
        }
    }

    renameTarget?.let { (id, currentName) ->
        RenameDialog(
            currentName = currentName,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameCompressedFile(id, newName)
                renameTarget = null
            }
        )
    }

    state.previewTarget?.let { target ->
        PdfPreviewDialog(
            title = target.title,
            file = target.file,
            renderPage = viewModel::renderPreviewPage,
            onDismiss = viewModel::closePreview
        )
    }
}

@Composable
private fun CompressScreen(
    state: CompressorUiState,
    padding: PaddingValues,
    onPickPdfs: () -> Unit,
    onSelectLevel: (CompressionLevel) -> Unit,
    onCompressAll: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onClearQueue: () -> Unit,
    onSaveAs: (File, String) -> Unit,
    onPreview: (String, File) -> Unit,
    onShare: (File) -> Unit,
    onOpen: (File) -> Unit,
    onRequestRename: (String, String) -> Unit
) {
    val hasCompressible = state.queue.any { !it.isAnalyzing && it.error == null && it.compressedFile == null }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.local_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.select_pdfs_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.select_pdfs_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onPickPdfs,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.queue.isEmpty()) stringResource(R.string.select_pdfs_btn)
                            else stringResource(R.string.add_more_btn)
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.compression_level),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (state.queue.isNotEmpty()) {
                    TextButton(onClick = onClearQueue) {
                        Text(stringResource(R.string.clear_queue))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                CompressionLevel.entries.forEachIndexed { index, level ->
                    SegmentedButton(
                        selected = state.selectedLevel == level,
                        onClick = { onSelectLevel(level) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = CompressionLevel.entries.size
                        )
                    ) {
                        Text(
                            when (level) {
                                CompressionLevel.HIGH -> stringResource(R.string.level_high)
                                CompressionLevel.MEDIUM -> stringResource(R.string.level_medium)
                                CompressionLevel.LOW -> stringResource(R.string.level_low)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (state.selectedLevel) {
                    CompressionLevel.HIGH -> stringResource(R.string.level_high_desc)
                    CompressionLevel.MEDIUM -> stringResource(R.string.level_medium_desc)
                    CompressionLevel.LOW -> stringResource(R.string.level_low_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.estimate_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(state.queue, key = { it.id }) { item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (item.compressedFile != null) item.compressedName else item.originalName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            item.analysis?.let { a ->
                                Text(
                                    text = "${formatBytes(a.originalBytes)} · ${a.pageCount} ${stringResource(R.string.pages_unit)} · ${a.imageCount} ${stringResource(R.string.images_unit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.isAnalyzing) {
                                Text(
                                    text = stringResource(R.string.analyzing_label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = { onRemoveItem(item.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        }
                    }

                    item.error?.let { err ->
                        val msg = when (err) {
                            PdfLoadError.Encrypted -> stringResource(R.string.err_encrypted)
                            PdfLoadError.Corrupted -> stringResource(R.string.err_corrupted)
                            PdfLoadError.Empty -> stringResource(R.string.err_empty)
                            PdfLoadError.Unsupported -> stringResource(R.string.err_unsupported)
                        }
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (!item.isAnalyzing && item.error == null && item.compressedFile == null) {
                        val est = item.analysis?.estimates?.forLevel(state.selectedLevel) ?: item.originalBytes
                        Text(
                            text = "${stringResource(R.string.est_output_label)}: ~${formatBytes(est)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    AnimatedVisibility(visible = item.isCompressing) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "${stringResource(R.string.compressing_label)} ${(item.progress * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            LinearProgressIndicator(
                                progress = { item.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item.compressedFile?.let { file ->
                        val outSize = item.compressedBytes ?: file.length()
                        val saved = (item.originalBytes - outSize).coerceAtLeast(0L)
                        val pct = if (item.originalBytes > 0) (saved * 100f / item.originalBytes).roundToInt() else 0

                        Text(
                            text = "${stringResource(R.string.original_label)}: ${formatBytes(item.originalBytes)} → ${stringResource(R.string.compressed_label)}: ${formatBytes(outSize)} (${stringResource(R.string.saved_label)} ${formatBytes(saved)}, -${pct}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { onSaveAs(file, item.compressedName) }) {
                                Text(stringResource(R.string.action_save))
                            }
                            OutlinedButton(onClick = { onPreview(item.compressedName, file) }) {
                                Text(stringResource(R.string.action_preview))
                            }
                            OutlinedButton(onClick = { onShare(file) }) {
                                Text(stringResource(R.string.action_share))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRequestRename(item.id, item.compressedName) }) {
                                Text(stringResource(R.string.action_rename))
                            }
                            TextButton(onClick = { onOpen(file) }) {
                                Text(stringResource(R.string.action_open))
                            }
                        }
                    }
                }
            }
        }

        if (hasCompressible) {
            item {
                Button(
                    onClick = onCompressAll,
                    enabled = !state.isBatchCompressing,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(stringResource(R.string.compress_all_btn))
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun HistoryScreen(
    entries: List<CompressionHistoryEntry>,
    padding: PaddingValues,
    onSaveAs: (File, String) -> Unit,
    onPreview: (String, File) -> Unit,
    onShare: (File) -> Unit,
    onRequestRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (entries.isEmpty()) {
            item {
                Spacer(Modifier.height(32.dp))
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.history_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.history_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onClearAll) {
                        Text(stringResource(R.string.clear_history))
                    }
                }
            }
            items(entries, key = { it.id }) { entry ->
                val file = File(entry.filePath)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.compressedName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${formatBytes(entry.originalBytes)} → ${formatBytes(entry.compressedBytes)} · ${entry.pageCount} ${stringResource(R.string.pages_unit)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onSaveAs(file, entry.compressedName) }) {
                                Text(stringResource(R.string.action_save))
                            }
                            OutlinedButton(onClick = { onPreview(entry.compressedName, file) }) {
                                Text(stringResource(R.string.action_preview))
                            }
                            OutlinedButton(onClick = { onShare(file) }) {
                                Text(stringResource(R.string.action_share))
                            }
                        }
                        TextButton(onClick = { onRequestRename(entry.id, entry.compressedName) }) {
                            Text(stringResource(R.string.action_rename))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.rename_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank()
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PdfPreviewDialog(
    title: String,
    file: File,
    renderPage: suspend (File, Int) -> Pair<Bitmap, Int>?,
    onDismiss: () -> Unit
) {
    var pageIndex by remember { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(1) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(file, pageIndex) {
        val result = renderPage(file, pageIndex)
        if (result != null) {
            bitmap = result.first
            totalPages = result.second
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (pageIndex > 0) pageIndex-- },
                        enabled = pageIndex > 0
                    ) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = null)
                    }
                    Text("${pageIndex + 1} / $totalPages")
                    IconButton(
                        onClick = { if (pageIndex < totalPages - 1) pageIndex++ },
                        enabled = pageIndex < totalPages - 1
                    ) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

private fun shareCompressedPdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}

private fun openCompressedPdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, file.name))
}
