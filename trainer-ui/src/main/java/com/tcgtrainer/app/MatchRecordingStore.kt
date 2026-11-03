package com.tcgtrainer.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.tcgtrainer.engine.MatchRecording
import java.io.File

data class RecordingSummary(
    val gameId: String,
    val startedAtEpochMs: Long,
    val difficulty: String,
    val humanDeck: String,
    val cpuDeck: String,
    val winner: Int?,
    val totalTurns: Int,
    val file: File
)

object MatchRecordingStore {
    private const val RECORDINGS_DIR = "match_recordings"
    private const val MAX_SAVED_MATCHES = 30
    private const val MEMORY_FILE = "cpu_experience_memory.json"

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, RECORDINGS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveRecording(context: Context, recording: MatchRecording) {
        try {
            val dir = getDir(context)
            val file = File(dir, "match-${recording.gameId}.json")
            file.writeText(recording.toJson())
            pruneOldRecordings(dir)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun listRecordings(context: Context): List<RecordingSummary> {
        val dir = getDir(context)
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val rec = MatchRecording.fromJson(f.readText())
                RecordingSummary(
                    gameId = rec.gameId,
                    startedAtEpochMs = rec.startedAtEpochMs,
                    difficulty = rec.difficulty,
                    humanDeck = rec.humanDeckId,
                    cpuDeck = rec.cpuDeckId,
                    winner = rec.winner,
                    totalTurns = rec.totalTurns,
                    file = f
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.startedAtEpochMs }
    }

    fun loadRecording(context: Context, gameId: String): MatchRecording? {
        val dir = getDir(context)
        val file = File(dir, "match-$gameId.json")
        if (!file.exists()) return null
        return try {
            MatchRecording.fromJson(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun deleteRecording(context: Context, gameId: String) {
        val dir = getDir(context)
        val file = File(dir, "match-$gameId.json")
        if (file.exists()) file.delete()
    }

    fun clearAll(context: Context) {
        val dir = getDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    fun saveExperienceMemory(context: Context, jsonText: String) {
        try {
            val file = File(context.filesDir, MEMORY_FILE)
            file.writeText(jsonText)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadExperienceMemory(context: Context): String {
        val file = File(context.filesDir, MEMORY_FILE)
        return if (file.exists()) file.readText() else ""
    }

    fun exportRecordingFile(context: Context, recording: MatchRecording, asJson: Boolean): File {
        val exportDir = File(context.cacheDir, "exports")
        if (!exportDir.exists()) exportDir.mkdirs()
        val extension = if (asJson) "json" else "txt"
        val file = File(exportDir, "pokecard-match-${recording.gameId}.$extension")
        if (asJson) {
            file.writeText(recording.toJson())
        } else {
            file.writeText(recording.toReadableTxt())
        }
        return file
    }

    fun shareRecording(context: Context, recording: MatchRecording, asJson: Boolean) {
        try {
            val file = exportRecordingFile(context, recording, asJson)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = if (asJson) "application/json" else "text/plain"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PokéCard Partida ${recording.gameId}")
                putExtra(Intent.EXTRA_TEXT, "Registro da partida do PokéCard TCG Trainer (${recording.difficulty}).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(sendIntent, "Exportar Partida")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pruneOldRecordings(dir: File) {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return
        if (files.size > MAX_SAVED_MATCHES) {
            val sorted = files.sortedBy { it.lastModified() }
            val excess = sorted.take(files.size - MAX_SAVED_MATCHES)
            excess.forEach { it.delete() }
        }
    }
}

