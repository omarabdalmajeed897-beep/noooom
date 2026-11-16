package com.compressor.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.compressor.app.pdf.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class CompressorUiState(
    val activeTab: MainTab = MainTab.COMPRESS,
    val selectedLevel: CompressionLevel = CompressionLevel.MEDIUM,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val queue: List<BatchPdfItem> = emptyList(),
    val history: List<CompressionHistoryEntry> = emptyList(),
    val isBatchCompressing: Boolean = false,
    val previewTarget: PreviewTarget? = null
)

class CompressorViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = PdfCompressorEngine(application.applicationContext)
    private val historyRepo = HistoryRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(
        CompressorUiState(history = historyRepo.loadAll())
    )
    val uiState: StateFlow<CompressorUiState> = _uiState.asStateFlow()

    fun setTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setCompressionLevel(level: CompressionLevel) {
        _uiState.update { it.copy(selectedLevel = level) }
    }

    fun toggleLanguage() {
        _uiState.update { state ->
            val next = if (state.language == AppLanguage.ENGLISH) {
                AppLanguage.ARABIC
            } else {
                AppLanguage.ENGLISH
            }
            state.copy(language = next)
        }
    }

    fun cycleTheme() {
        _uiState.update { state ->
            val next = when (state.themePreference) {
                ThemePreference.SYSTEM -> ThemePreference.LIGHT
                ThemePreference.LIGHT -> ThemePreference.DARK
                ThemePreference.DARK -> ThemePreference.SYSTEM
            }
            state.copy(themePreference = next)
        }
    }

    fun addSelectedUris(uris: List<Uri>) {
        val newItems = uris.map { uri ->
            val baseName = uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
            BatchPdfItem(
                id = UUID.randomUUID().toString(),
                sourceUri = uri,
                originalName = baseName,
                originalBytes = 0L,
                isAnalyzing = true
            )
        }

        _uiState.update { it.copy(queue = it.queue + newItems, activeTab = MainTab.COMPRESS) }

        newItems.forEach { pending ->
            viewModelScope.launch {
                val res = engine.inspectPdf(pending.sourceUri)
                res.onSuccess { (resolvedName, analysis) ->
                    val defaultCompressed = buildCompressedName(resolvedName)
                    _uiState.update { state ->
                        state.copy(
                            queue = state.queue.map { item ->
                                if (item.id == pending.id) {
                                    item.copy(
                                        originalName = resolvedName,
                                        compressedName = defaultCompressed,
                                        originalBytes = analysis.originalBytes,
                                        analysis = analysis,
                                        isAnalyzing = false
                                    )
                                } else item
                            }
                        )
                    }
                }.onFailure { err ->
                    val loadErr = (err as? PdfEngineException)?.error ?: PdfLoadError.Corrupted
                    _uiState.update { state ->
                        state.copy(
                            queue = state.queue.map { item ->
                                if (item.id == pending.id) {
                                    item.copy(isAnalyzing = false, error = loadErr)
                                } else item
                            }
                        )
                    }
                }
            }
        }
    }

    fun removeQueueItem(id: String) {
        _uiState.update { state ->
            state.copy(queue = state.queue.filterNot { it.id == id })
        }
    }

    fun clearQueue() {
        _uiState.update { it.copy(queue = emptyList()) }
    }

    fun compressAll() {
        val current = _uiState.value
        val targets = current.queue.filter { !it.isAnalyzing && it.error == null && it.compressedFile == null }
        if (targets.isEmpty() || current.isBatchCompressing) return

        val level = current.selectedLevel
        _uiState.update { it.copy(isBatchCompressing = true) }

        viewModelScope.launch {
            for (target in targets) {
                _uiState.update { state ->
                    state.copy(
                        queue = state.queue.map {
                            if (it.id == target.id) it.copy(isCompressing = true, progress = 0.05f) else it
                        }
                    )
                }

                val result = engine.compressPdf(
                    uri = target.sourceUri,
                    originalName = target.originalName,
                    level = level,
                    onProgress = { pct ->
                        _uiState.update { state ->
                            state.copy(
                                queue = state.queue.map {
                                    if (it.id == target.id) it.copy(progress = pct) else it
                                }
                            )
                        }
                    }
                )

                result.onSuccess { outFile ->
                    val entry = CompressionHistoryEntry(
                        id = target.id,
                        originalName = target.originalName,
                        compressedName = target.compressedName,
                        originalBytes = target.originalBytes,
                        compressedBytes = outFile.length(),
                        pageCount = target.analysis?.pageCount ?: 1,
                        level = level,
                        filePath = outFile.absolutePath,
                        createdAtMillis = System.currentTimeMillis()
                    )

                    _uiState.update { state ->
                        val updatedHistory = listOf(entry) + state.history.filterNot { it.id == entry.id }
                        historyRepo.saveAll(updatedHistory)
                        state.copy(
                            history = updatedHistory,
                            queue = state.queue.map {
                                if (it.id == target.id) {
                                    it.copy(
                                        isCompressing = false,
                                        progress = 1f,
                                        compressedFile = outFile,
                                        compressedBytes = outFile.length()
                                    )
                                } else it
                            }
                        )
                    }
                }.onFailure { err ->
                    val loadErr = (err as? PdfEngineException)?.error ?: PdfLoadError.Corrupted
                    _uiState.update { state ->
                        state.copy(
                            queue = state.queue.map {
                                if (it.id == target.id) {
                                    it.copy(isCompressing = false, progress = 0f, error = loadErr)
                                } else it
                            }
                        )
                    }
                }
            }

            _uiState.update { it.copy(isBatchCompressing = false) }
        }
    }

    fun renameCompressedFile(id: String, rawNewName: String) {
        val sanitized = sanitizePdfFileName(rawNewName)
        _uiState.update { state ->
            val updatedQueue = state.queue.map { item ->
                if (item.id == id) {
                    val renamed = renameFileOnDisk(item.compressedFile, sanitized)
                    item.copy(compressedName = sanitized, compressedFile = renamed ?: item.compressedFile)
                } else item
            }
            val updatedHistory = state.history.map { entry ->
                if (entry.id == id) {
                    val currentFile = File(entry.filePath)
                    val renamed = renameFileOnDisk(currentFile, sanitized)
                    entry.copy(
                        compressedName = sanitized,
                        filePath = renamed?.absolutePath ?: entry.filePath
                    )
                } else entry
            }
            historyRepo.saveAll(updatedHistory)
            state.copy(queue = updatedQueue, history = updatedHistory)
        }
    }

    fun deleteHistoryEntry(id: String) {
        _uiState.update { state ->
            val target = state.history.find { it.id == id }
            target?.let { File(it.filePath).delete() }
            val updated = state.history.filterNot { it.id == id }
            historyRepo.saveAll(updated)
            state.copy(history = updated)
        }
    }

    fun clearHistory() {
        _uiState.update { state ->
            state.history.forEach { File(it.filePath).delete() }
            historyRepo.saveAll(emptyList())
            state.copy(history = emptyList())
        }
    }

    fun openPreview(title: String, file: File) {
        _uiState.update { it.copy(previewTarget = PreviewTarget(title, file)) }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewTarget = null) }
    }

    suspend fun renderPreviewPage(file: File, pageIndex: Int) = engine.renderPdfPage(file, pageIndex)

    private fun buildCompressedName(originalName: String): String {
        val base = originalName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "document" }
        return sanitizePdfFileName("${base}_compressed.pdf")
    }

    private fun sanitizePdfFileName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trimEnd('.')
        if (cleaned.isBlank()) return "document_compressed.pdf"
        return if (cleaned.endsWith(".pdf", ignoreCase = true)) cleaned else "$cleaned.pdf"
    }

    private fun renameFileOnDisk(file: File?, newName: String): File? {
        if (file == null || !file.exists()) return null
        val target = File(file.parentFile, newName)
        return if (file.renameTo(target)) target else file
    }
}
