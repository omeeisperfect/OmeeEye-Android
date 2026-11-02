package com.nimu.omeeeye.data

import android.content.Context
import android.util.Xml
import com.nimu.omeeeye.model.Workflow
import java.io.File
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Data-driven repeat (feature 4): reads TXT / CSV / XLSX files that were
 * copied into internal storage when the user attached them.
 *
 * A row is an ordered map of column name -> value, plus the synthetic
 * keys "ROW" (1-based row number) and, for TXT files, "TEXT".
 *
 * TXT: one value per line; the single column is named "TEXT".
 * CSV: first line is the header; fields are split on commas with basic
 * double-quote handling (a quoted field may contain commas and escaped
 * double quotes). No external CSV dependency.
 * XLSX: first sheet only, values only (no formulas are evaluated — a
 * formula cell reads as its cached value, or empty when there is none).
 * Parsed with the built-in zip + XML pull parser, so no POI dependency.
 * Returns an error string instead of throwing, so the runner can fail a
 * run with a clean message.
 */
object DataSourceLoader {

    data class Result(val rows: List<Map<String, String>>, val error: String?)

    fun load(context: Context, workflow: Workflow): Result {
        val fileName = workflow.dataFileName
        if (workflow.dataType.isBlank() || fileName.isBlank()) {
            return Result(emptyList(), "No data source attached")
        }
        val file = File(context.filesDir, fileName)
        if (!file.exists()) {
            return Result(emptyList(), "Data file not found")
        }
        return try {
            when (workflow.dataType.uppercase()) {
                "TXT" -> Result(loadTxt(file), null)
                "CSV" -> Result(loadCsv(file), null)
                "EXCEL" -> loadXlsx(file)
                else -> Result(emptyList(), "Unknown data type")
            }
        } catch (e: Exception) {
            Result(emptyList(), "Could not read data file: ${e.message}")
        }
    }

    private fun loadTxt(file: File): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        file.readLines().forEachIndexed { index, line ->
            rows.add(mapOf("TEXT" to line, "ROW" to (index + 1).toString()))
        }
        return rows
    }

    private fun loadCsv(file: File): List<Map<String, String>> {
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val headers = splitCsvLine(lines[0]).map { it.trim() }
        val rows = mutableListOf<Map<String, String>>()
        for (i in 1 until lines.size) {
            val values = splitCsvLine(lines[i])
            val row = LinkedHashMap<String, String>()
            headers.forEachIndexed { col, header ->
                row[header] = values.getOrNull(col) ?: ""
            }
            row["ROW"] = i.toString()
            rows.add(row)
        }
        return rows
    }

    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out.add(current.toString())
        return out
    }

    // ------------------------------------------------------------------
    // Minimal XLSX reader (first sheet, shared strings, cached values)
    // ------------------------------------------------------------------

    private fun loadXlsx(file: File): Result {
        var sharedStrings: List<String> = emptyList()
        var sheetXml: String? = null
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/sharedStrings.xml" -> {
                        sharedStrings = parseSharedStrings(zip.readBytes())
                    }
                    "xl/worksheets/sheet1.xml" -> {
                        sheetXml = String(zip.readBytes(), Charsets.UTF_8)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val xml = sheetXml ?: return Result(emptyList(), "No sheet found in Excel file")
        return Result(parseSheet(xml, sharedStrings), null)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")
        var event = parser.eventType
        var inSi = false
        val current = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        inSi = true
                        current.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi) current.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        inSi = false
                        out.add(current.toString())
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSheet(xml: String, sharedStrings: List<String>): List<Map<String, String>> {
        val rows = mutableListOf<MutableMap<Int, String>>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.byteInputStream(Charsets.UTF_8), "UTF-8")
        var event = parser.eventType
        var currentRow: MutableMap<Int, String>? = null
        var currentCol = -1
        var currentType: String? = null
        var currentValue = StringBuilder()
        var inValue = false
        var inInline = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = LinkedHashMap()
                        "c" -> {
                            currentCol = columnIndex(parser.getAttributeValue(null, "r"))
                            currentType = parser.getAttributeValue(null, "t")
                            currentValue = StringBuilder()
                        }
                        "v" -> inValue = true
                        "is" -> inInline = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValue || inInline) currentValue.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v" -> inValue = false
                        "is" -> inInline = false
                        "c" -> {
                            val row = currentRow
                            if (row != null && currentCol >= 0) {
                                val raw = currentValue.toString()
                                val value = when (currentType) {
                                    "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                                    "str" -> raw
                                    else -> raw
                                }
                                row[currentCol] = value
                            }
                        }
                        "row" -> {
                            currentRow?.let { rows.add(it) }
                            currentRow = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (rows.isEmpty()) return emptyList()
        val headers = rows[0].entries.sortedBy { it.key }
            .map { it.value.ifBlank { "Column${it.key + 1}" } }
        val out = mutableListOf<Map<String, String>>()
        for (i in 1 until rows.size) {
            val row = LinkedHashMap<String, String>()
            headers.forEachIndexed { col, header ->
                row[header] = rows[i][col] ?: ""
            }
            row["ROW"] = i.toString()
            out.add(row)
        }
        return out
    }

    /** "AB12" -> column index 27 (0-based). Letters only, digits ignored. */
    private fun columnIndex(ref: String?): Int {
        if (ref.isNullOrBlank()) return -1
        var index = 0
        for (c in ref) {
            if (c.isLetter()) {
                index = index * 26 + (c.uppercaseChar() - 'A' + 1)
            } else {
                break
            }
        }
        return index - 1
    }
}
