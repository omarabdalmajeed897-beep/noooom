package com.compressor.app.pdf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryRepository(private val context: Context) {

    private val historyFile: File
        get() = File(context.filesDir, "compression_history.json")

    fun loadAll(): List<CompressionHistoryEntry> {
        val file = historyFile
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pdfFile = File(obj.getString("filePath"))
                    if (pdfFile.exists()) {
                        add(
                            CompressionHistoryEntry(
                                id = obj.getString("id"),
                                originalName = obj.getString("originalName"),
                                compressedName = obj.getString("compressedName"),
                                originalBytes = obj.getLong("originalBytes"),
                                compressedBytes = obj.getLong("compressedBytes"),
                                pageCount = obj.getInt("pageCount"),
                                level = CompressionLevel.valueOf(obj.getString("level")),
                                filePath = obj.getString("filePath"),
                                createdAtMillis = obj.getLong("createdAtMillis")
                            )
                        )
                    }
                }
            }.sortedByDescending { it.createdAtMillis }
        }.getOrDefault(emptyList())
    }

    fun saveAll(entries: List<CompressionHistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("originalName", e.originalName)
                    put("compressedName", e.compressedName)
                    put("originalBytes", e.originalBytes)
                    put("compressedBytes", e.compressedBytes)
                    put("pageCount", e.pageCount)
                    put("level", e.level.name)
                    put("filePath", e.filePath)
                    put("createdAtMillis", e.createdAtMillis)
                }
            )
        }
        historyFile.writeText(arr.toString())
    }
}
