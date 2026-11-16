package com.compressor.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class PdfCompressorEngine(private val context: Context) {

    suspend fun inspectPdf(uri: Uri): Result<Pair<String, PdfAnalysisResult>> =
        withContext(Dispatchers.IO) {
            val fileName = queryFileName(uri) ?: "document.pdf"
            val fileSize = queryFileSize(uri)
            if (fileSize == 0L) {
                return@withContext Result.failure(PdfEngineException(PdfLoadError.Empty))
            }

            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    PDDocument.load(stream).use { doc ->
                        var imageCount = 0
                        var estimatedImageBytes = 0L

                        for (page in doc.pages) {
                            val resources = page.resources ?: continue
                            for (name in resources.xObjectNames) {
                                val xObj = runCatching { resources.getXObject(name) }.getOrNull()
                                if (xObj is PDImageXObject) {
                                    imageCount++
                                    estimatedImageBytes += xObj.cosObject.length.coerceAtLeast(0L)
                                }
                            }
                        }

                        val total = max(1024L, fileSize)
                        val clampedImg = min(total, estimatedImageBytes)
                        val other = (total - clampedImg).coerceAtLeast(0L)
                        val imgRatio = clampedImg.toDouble() / total.toDouble()

                        val highFactor = if (imgRatio > 0.15) 0.34 else 0.55
                        val medFactor = if (imgRatio > 0.15) 0.56 else 0.72
                        val lowFactor = if (imgRatio > 0.15) 0.82 else 0.90

                        val lowEst = (clampedImg * lowFactor + other * 0.94).roundToLong().coerceIn(1024L, total)
                        val medEst = (clampedImg * medFactor + other * 0.89).roundToLong().coerceIn(1024L, lowEst)
                        val highEst = (clampedImg * highFactor + other * 0.84).roundToLong().coerceIn(1024L, medEst)

                        Result.success(
                            fileName to PdfAnalysisResult(
                                pageCount = doc.numberOfPages,
                                originalBytes = total,
                                imageCount = imageCount,
                                estimatedImageBytes = clampedImg,
                                estimates = PdfEstimateMap(highEst, medEst, lowEst)
                            )
                        )
                    }
                } ?: Result.failure(PdfEngineException(PdfLoadError.Corrupted))
            } catch (_: InvalidPasswordException) {
                Result.failure(PdfEngineException(PdfLoadError.Encrypted))
            } catch (_: IOException) {
                Result.failure(PdfEngineException(PdfLoadError.Corrupted))
            } catch (_: Throwable) {
                Result.failure(PdfEngineException(PdfLoadError.Unsupported))
            }
        }

    suspend fun compressPdf(
        uri: Uri,
        originalName: String,
        level: CompressionLevel,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val outDir = File(context.filesDir, "compressed").apply { mkdirs() }
        val baseName = originalName.removeSuffix(".pdf").removeSuffix(".PDF")
        val targetFile = File(outDir, "${baseName}_compressed_${System.currentTimeMillis()}.pdf")

        try {
            onProgress(0.08f)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    if (level.stripMetadata) {
                        doc.documentCatalog.metadata = null
                    }
                    val totalPages = doc.numberOfPages.coerceAtLeast(1)
                    onProgress(0.18f)

                    doc.pages.forEachIndexed { pageIdx, page ->
                        val resources = page.resources
                        if (resources != null) {
                            for (name in resources.xObjectNames.toList()) {
                                val xObj = runCatching { resources.getXObject(name) }.getOrNull()
                                if (xObj is PDImageXObject && !xObj.isStencil && xObj.softMask == null) {
                                    val originalLength = xObj.cosObject.length
                                    if (originalLength >= 6144) {
                                        val reencoded = downsampleImage(xObj, doc, level)
                                        if (reencoded != null && reencoded.cosObject.length < originalLength * 0.96) {
                                            resources.put(COSName.getPDFName(name.name), reencoded)
                                        }
                                    }
                                }
                            }
                        }
                        onProgress(0.18f + 0.72f * ((pageIdx + 1).toFloat() / totalPages))
                    }

                    doc.save(targetFile)
                    onProgress(1.0f)
                }
            } ?: return@withContext Result.failure(PdfEngineException(PdfLoadError.Corrupted))

            Result.success(targetFile)
        } catch (_: InvalidPasswordException) {
            targetFile.delete()
            Result.failure(PdfEngineException(PdfLoadError.Encrypted))
        } catch (_: Throwable) {
            targetFile.delete()
            Result.failure(PdfEngineException(PdfLoadError.Corrupted))
        }
    }

    suspend fun renderPdfPage(file: File, pageIndex: Int): Pair<Bitmap, Int>? =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                val count = renderer.pageCount
                if (count <= 0) return@withContext null
                val safeIdx = pageIndex.coerceIn(0, count - 1)
                renderer.openPage(safeIdx).use { page ->
                    val targetWidth = 1080
                    val ratio = targetWidth.toFloat() / page.width.coerceAtLeast(1).toFloat()
                    val targetHeight = (page.height * ratio).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap to count
                }
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { renderer?.close() }
                runCatching { pfd?.close() }
            }
        }

    private fun downsampleImage(
        image: PDImageXObject,
        doc: PDDocument,
        level: CompressionLevel
    ): PDImageXObject? {
        return try {
            val srcBitmap = image.image ?: return null
            val maxDim = level.maxImageDimension
            val w = srcBitmap.width
            val h = srcBitmap.height
            val scale = min(1f, maxDim.toFloat() / max(w, h).toFloat())

            val targetW = max(1, (w * scale).roundToInt())
            val targetH = max(1, (h * scale).roundToInt())

            val scaled = if (targetW != w || targetH != h) {
                Bitmap.createScaledBitmap(srcBitmap, targetW, targetH, true).also {
                    if (it !== srcBitmap) srcBitmap.recycle()
                }
            } else {
                srcBitmap
            }

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, level.jpegQuality, baos)
            scaled.recycle()

            val bytes = baos.toByteArray()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            JPEGFactory.createFromImage(doc, decoded, level.jpegQuality / 100f).also {
                decoded.recycle()
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun queryFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }
}

class PdfEngineException(val error: PdfLoadError) : Exception()
