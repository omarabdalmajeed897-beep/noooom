package com.compressor.app.pdf

import android.net.Uri
import java.io.File

enum class CompressionLevel(
    val maxImageDimension: Int,
    val jpegQuality: Int,
    val stripMetadata: Boolean
) {
    HIGH(maxImageDimension = 1000, jpegQuality = 46, stripMetadata = true),
    MEDIUM(maxImageDimension = 1450, jpegQuality = 70, stripMetadata = false),
    LOW(maxImageDimension = 2200, jpegQuality = 88, stripMetadata = false)
}

enum class ThemePreference { SYSTEM, LIGHT, DARK }

enum class AppLanguage(val tag: String, val isRtl: Boolean) {
    ENGLISH("en", false),
    ARABIC("ar", true)
}

enum class MainTab { COMPRESS, HISTORY }

data class PdfEstimateMap(
    val highBytes: Long,
    val mediumBytes: Long,
    val lowBytes: Long
) {
    fun forLevel(level: CompressionLevel): Long = when (level) {
        CompressionLevel.HIGH -> highBytes
        CompressionLevel.MEDIUM -> mediumBytes
        CompressionLevel.LOW -> lowBytes
    }
}

data class PdfAnalysisResult(
    val pageCount: Int,
    val originalBytes: Long,
    val imageCount: Int,
    val estimatedImageBytes: Long,
    val estimates: PdfEstimateMap
)

sealed interface PdfLoadError {
    data object Encrypted : PdfLoadError
    data object Corrupted : PdfLoadError
    data object Unsupported : PdfLoadError
    data object Empty : PdfLoadError
}

data class BatchPdfItem(
    val id: String,
    val sourceUri: Uri,
    val originalName: String,
    val originalBytes: Long,
    val analysis: PdfAnalysisResult? = null,
    val compressedFile: File? = null,
    val compressedName: String = "",
    val compressedBytes: Long? = null,
    val progress: Float = 0f,
    val isAnalyzing: Boolean = true,
    val isCompressing: Boolean = false,
    val error: PdfLoadError? = null
)

data class CompressionHistoryEntry(
    val id: String,
    val originalName: String,
    val compressedName: String,
    val originalBytes: Long,
    val compressedBytes: Long,
    val pageCount: Int,
    val level: CompressionLevel,
    val filePath: String,
    val createdAtMillis: Long
)

data class PreviewTarget(
    val title: String,
    val file: File
)
