package com.lzb.report

import com.lzb.core.EventStore
import com.lzb.core.StoragePolicy
import com.lzb.core.TraceletEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class JsonEventStore(
    private val directory: File, private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val storagePolicy: StoragePolicy
) : EventStore {


    companion object {
        // 清理间隔 1 分钟
        private const val CLEANUP_INTERVAL_MS = 60 * 1000L
    }

    private var lastCleanupTime: Long = 0L
    private val mJson = Json {
        encodeDefaults = true
    }

    override suspend fun write(event: TraceletEvent) {
        withContext(dispatcher) {
            cleanupIfNeeded()
            val exists = directory.exists()
            if (!exists) {
                val mkdirs = directory.mkdirs()
                if (!mkdirs && !directory.isDirectory) {
                    println("create dirs failed: ${directory.absolutePath}")
                    return@withContext
                }
            }
            try {
                val fileName =
                    "${directory.absolutePath}/${event.type}-${event.timestampMs}-${event.id}.json"
                val targetFile = File(fileName)
                val fileTemp = File("$fileName.tmp")
                if (!fileTemp.exists()) {
                    fileTemp.createNewFile()
                }
                fileTemp.bufferedWriter().use {
                    it.write(mJson.encodeToString(event))
                }
                val renamed = fileTemp.renameTo(targetFile)
                if (!renamed) {
                    println("rename file failed: ${fileTemp.absolutePath}")
                }
            } catch (e: Exception) {
                println("write event failed: $e")
            }
        }
    }

    private fun cleanupIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return
        }
        // 清理文件
        val fileInfos = directory.listFiles()
            ?.mapNotNull { it.toEventFileInfo() }
            .orEmpty()

        if (fileInfos.isEmpty()) return

        // 1 删除过去文件
        val eventFileInfos = fileInfos.filter { it.timestampMs < now - storagePolicy.maxFileAge }
        eventFileInfos.forEach { deleteEventFile(it) }

        // 剩余文件按时间从旧到新排序
        var sortedFileInfos = fileInfos
            .filterNot { it in eventFileInfos }
            .sortedBy { it.timestampMs }

        // 2 数量超限，从最旧开始删
        val maxFileCount = storagePolicy.maxFileCount
        if (sortedFileInfos.size > maxFileCount) {
            val deleteCount = sortedFileInfos.size - maxFileCount
            sortedFileInfos.subList(0, deleteCount).forEach { deleteEventFile(it) }
            sortedFileInfos = sortedFileInfos.drop(deleteCount)
        }

        // 3 总大小超限，继续从最旧开始删
        val maxFileSize = storagePolicy.maxTotalBytes
        var totalSizeBytes = sortedFileInfos.sumOf { it.sizeBytes }
        var index = 0
        while (totalSizeBytes > maxFileSize && index < sortedFileInfos.size) {
            val oldest = sortedFileInfos[index]
            deleteEventFile(oldest)
            totalSizeBytes -= oldest.sizeBytes
            index++
        }

        lastCleanupTime = now
    }

    override suspend fun readAll(): List<TraceletEvent> = withContext(dispatcher) {
        val list = mutableListOf<TraceletEvent>()
        directory.listFiles()?.forEach {
            val absoluteFile = it.absoluteFile
            if (absoluteFile.isFile && absoluteFile.name.endsWith(".json")) {
                try {
                    val event = mJson.decodeFromString<TraceletEvent>(absoluteFile.readText())
                    list.add(event)
                } catch (e: Exception) {
                    absoluteFile.delete()
                    println("read event failed: $e")
                }
            }
        }
        list
    }

    override suspend fun exportEventsTo(destinationDirectory: File): File =
        withContext(dispatcher) {
            val exportFile = File(
                destinationDirectory,
                "tracelet-export-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            )
            if (!exportFile.mkdirs()) {
                throw IllegalStateException(
                    "create export dir failed: ${exportFile.absolutePath}"
                )
            }

            if (directory.exists()) {
                directory.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
                    ?.forEach { file ->
                        file.copyTo(File(exportFile, file.name), overwrite = true)
                    }
            }
            exportFile
        }

    override suspend fun clearAll() {
        withContext(dispatcher) {
            directory.listFiles()
                ?.filter {
                    it.isFile &&
                            (it.name.endsWith(".json") || it.name.endsWith(".tmp"))
                }
                ?.forEach { file ->
                    try {
                        if (!file.delete()) {
                            println("clear file failed: ${file.absolutePath}")
                        }
                    } catch (e: Exception) {
                        println("clear file failed: ${file.absolutePath}: $e")
                    }
                }
        }
    }

    private data class EventFileInfo(
        val file: File,
        val timestampMs: Long,
        val sizeBytes: Long
    )

    private fun File.toEventFileInfo(): EventFileInfo? {
        if (this.isFile && this.name.endsWith(".json")) {
            val timestamp = name
                .removeSuffix(".json")
                .split('-')
                .getOrNull(1)
                ?.toLongOrNull()
            val sizeBytes = this.length()
            return EventFileInfo(this, timestamp ?: lastModified(), sizeBytes)
        }
        return null
    }

    private fun deleteEventFile(info: EventFileInfo) {
        try {
            if (!info.file.delete()) {
                println("delete event failed: ${info.file.absolutePath}")
            }
        } catch (e: Exception) {
            println("delete event failed: ${info.file.absolutePath}: $e")
        }
    }
}
