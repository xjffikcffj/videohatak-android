package com.videohatak.player.core.subtitle.parser

import android.content.Context
import android.net.Uri
import com.videohatak.player.core.subtitle.model.SubtitleCue
import com.videohatak.player.core.subtitle.model.SubtitleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object SubtitleParser {

    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val SSA_STYLE_REGEX = Regex("\\{[^}]*\\}")

    fun detectFormat(fileName: String, content: String? = null): SubtitleFormat {
        val lowerName = fileName.lowercase()
        if (lowerName.endsWith(".vtt") || lowerName.endsWith(".webvtt")) {
            return SubtitleFormat.WEBVTT
        }
        if (lowerName.endsWith(".srt")) {
            return SubtitleFormat.SRT
        }
        if (content != null && content.trimStart().startsWith("WEBVTT")) {
            return SubtitleFormat.WEBVTT
        }
        return SubtitleFormat.SRT
    }

    fun parse(content: String, format: SubtitleFormat): List<SubtitleCue> {
        val cleanContent = content.removePrefix("\uFEFF") // Remove UTF-8 BOM
        val lines = cleanContent.lines()
        return when (format) {
            SubtitleFormat.WEBVTT -> parseVttLines(lines)
            SubtitleFormat.SRT -> parseSrtLines(lines)
        }
    }

    suspend fun parse(context: Context, uri: Uri): List<SubtitleCue> = withContext(Dispatchers.IO) {
        val cues = mutableListOf<SubtitleCue>()
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    val fullText = reader.readText()
                    val format = detectFormat(uri.lastPathSegment ?: "", fullText)
                    cues.addAll(parse(fullText, format))
                }
            }
        } catch (_: Exception) {}
        cues
    }

    fun findActiveCues(cues: List<SubtitleCue>, positionMs: Long): List<SubtitleCue> {
        return cues.filter { positionMs in it.startTimeMs..it.endTimeMs }
    }

    private fun parseSrtLines(lines: List<String>): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        var cueIndex = 1

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }

            var parsedIndex = cueIndex
            if (line.all { it.isDigit() }) {
                parsedIndex = line.toIntOrNull() ?: cueIndex
                i++
                if (i >= lines.size) break
            }

            val timeLine = lines[i].trim()
            if (timeLine.contains("-->")) {
                val parts = timeLine.split("-->")
                if (parts.size == 2) {
                    val startMs = parseTimestamp(parts[0].trim())
                    val endMs = parseTimestamp(parts[1].trim())

                    i++
                    val textBuilder = StringBuilder()
                    while (i < lines.size && lines[i].trim().isNotEmpty()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(sanitizeText(lines[i].trim()))
                        i++
                    }

                    if (startMs >= 0 && endMs >= startMs) {
                        cues.add(
                            SubtitleCue(
                                index = parsedIndex,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                text = textBuilder.toString()
                            )
                        )
                        cueIndex++
                    }
                }
            }
            i++
        }
        return cues
    }

    private fun parseVttLines(lines: List<String>): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        var cueIndex = 1

        while (i < lines.size && (lines[i].startsWith("WEBVTT") || lines[i].isBlank() || lines[i].startsWith("NOTE") || lines[i].startsWith("STYLE"))) {
            if (lines[i].startsWith("STYLE") || lines[i].startsWith("NOTE")) {
                while (i < lines.size && lines[i].isNotBlank()) {
                    i++
                }
            }
            i++
        }

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("NOTE")) {
                i++
                continue
            }

            var parsedIndex = cueIndex
            var currentLine = line

            if (!currentLine.contains("-->")) {
                parsedIndex = currentLine.toIntOrNull() ?: cueIndex
                i++
                if (i >= lines.size) break
                currentLine = lines[i].trim()
            }

            if (currentLine.contains("-->")) {
                val parts = currentLine.split("-->")
                if (parts.size == 2) {
                    val startMs = parseTimestamp(parts[0].trim().split(" ")[0])
                    val endMs = parseTimestamp(parts[1].trim().split(" ")[0])

                    i++
                    val textBuilder = StringBuilder()
                    while (i < lines.size && lines[i].trim().isNotEmpty() && !lines[i].contains("-->")) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(sanitizeText(lines[i].trim()))
                        i++
                    }

                    if (startMs >= 0 && endMs >= startMs) {
                        cues.add(
                            SubtitleCue(
                                index = parsedIndex,
                                startTimeMs = startMs,
                                endTimeMs = endMs,
                                text = textBuilder.toString()
                            )
                        )
                        cueIndex++
                    }
                }
            }
            i++
        }
        return cues
    }

    private fun sanitizeText(raw: String): String {
        return raw
            .replace(HTML_TAG_REGEX, "")
            .replace(SSA_STYLE_REGEX, "")
            .trim()
    }

    private fun parseTimestamp(raw: String): Long {
        return try {
            val normalized = raw.replace(',', '.')
            val parts = normalized.split(":")
            when (parts.size) {
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secParts = parts[2].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
                }
                2 -> {
                    val minutes = parts[0].toLong()
                    val secParts = parts[1].split(".")
                    val seconds = secParts[0].toLong()
                    val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLong() else 0L
                    (minutes * 60 + seconds) * 1000 + millis
                }
                else -> -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }
}
