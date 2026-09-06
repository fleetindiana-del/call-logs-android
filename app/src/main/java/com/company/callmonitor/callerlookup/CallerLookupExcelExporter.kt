package com.company.callmonitor.callerlookup

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a real .xlsx (Office Open XML) from local Room results and shares it.
 * No third-party Excel library required.
 */
object CallerLookupExcelExporter {
    fun exportAndShare(context: Context, jobId: String? = null) {
        Thread {
            try {
                val dao = CallerLookupDatabase.getInstance(context).callerLookupDao()
                val job = if (jobId.isNullOrBlank()) {
                    dao.getLatestJob()
                } else {
                    dao.getJob(jobId)
                }
                if (job == null) {
                    toast(context, "No caller lookup job on this device yet")
                    return@Thread
                }
                val results = dao.getResultsForJob(job.jobId)
                if (results.isEmpty()) {
                    toast(context, "No lookup results to export yet")
                    return@Thread
                }

                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "caller-lookup-${job.jobId.takeLast(8)}-$stamp.xlsx")
                writeWorkbook(file, job, results)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "Caller lookup ${job.seriesLabel} (${results.size} rows)"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val chooser = Intent.createChooser(share, "Export caller lookup Excel")
                    if (context !is android.app.Activity) {
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                }
            } catch (t: Throwable) {
                toast(context, "Export failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }.start()
    }

    private fun toast(context: Context, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun writeWorkbook(
        file: File,
        job: CallerLookupJobEntity,
        results: List<CallerLookupResultEntity>
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypesXml())
            writeEntry(zip, "_rels/.rels", rootRelsXml())
            writeEntry(zip, "xl/workbook.xml", workbookXml())
            writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            writeEntry(zip, "xl/styles.xml", stylesXml())
            writeEntry(zip, "xl/worksheets/sheet1.xml", summarySheetXml(job, results.size))
            writeEntry(zip, "xl/worksheets/sheet2.xml", resultsSheetXml(results))
        }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, xml: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(xml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun escape(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return buildString(value.length + 8) {
            for (ch in value) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(ch)
                }
            }
        }
    }

    private fun inlineCell(ref: String, value: String?, bold: Boolean = false): String {
        val t = escape(value)
        val style = if (bold) """ s="1"""" else ""
        return """<c r="$ref"$style t="inlineStr"><is><t xml:space="preserve">$t</t></is></c>"""
    }

    private fun numberCell(ref: String, value: Long): String =
        """<c r="$ref"><v>$value</v></c>"""

    private fun colLetter(index1Based: Int): String {
        var n = index1Based
        val sb = StringBuilder()
        while (n > 0) {
            n--
            sb.insert(0, ('A' + n % 26))
            n /= 26
        }
        return sb.toString()
    }

    private fun contentTypesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
        </Types>
    """.trimIndent()

    private fun rootRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="Job Summary" sheetId="1" r:id="rId1"/>
            <sheet name="Lookup Results" sheetId="2" r:id="rId2"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private fun workbookRelsXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun stylesXml() = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="2">
            <font><sz val="11"/><name val="Calibri"/></font>
            <font><b/><sz val="11"/><name val="Calibri"/></font>
          </fonts>
          <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
          <borders count="1"><border/></borders>
          <cellStyleXfs count="1"><xf/></cellStyleXfs>
          <cellXfs count="2">
            <xf fontId="0" fillId="0" borderId="0"/>
            <xf fontId="1" fillId="0" borderId="0" applyFont="1"/>
          </cellXfs>
        </styleSheet>
    """.trimIndent()

    private fun summarySheetXml(job: CallerLookupJobEntity, resultCount: Int): String {
        val rows = listOf(
            "Field" to "Value",
            "Job ID" to job.jobId,
            "Status" to job.status,
            "Device ID" to job.deviceId,
            "Series" to job.seriesLabel,
            "Prefix" to job.seriesPrefix,
            "Start Number" to job.startNumber,
            "End Number" to job.endNumber,
            "Processed" to job.processed.toString(),
            "Successful" to job.successful.toString(),
            "Failed / empty" to job.failed.toString(),
            "Total Planned" to job.totalPlanned.toString(),
            "Lookup Provider" to job.lookupProviderId,
            "Mobile Provider" to job.mobileProvider,
            "Exported At" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            "Rows Exported" to resultCount.toString()
        )
        val xmlRows = StringBuilder()
        rows.forEachIndexed { index, (field, value) ->
            val r = index + 1
            val bold = index == 0
            xmlRows.append("<row r=\"$r\">")
            xmlRows.append(inlineCell("A$r", field, bold))
            xmlRows.append(inlineCell("B$r", value, bold))
            xmlRows.append("</row>")
        }
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>$xmlRows</sheetData>
            </worksheet>
        """.trimIndent()
    }

    private fun resultsSheetXml(results: List<CallerLookupResultEntity>): String {
        val headers = listOf(
            "Phone Number",
            "Caller Name",
            "Lookup Status",
            "Provider",
            "Lookup Provider ID",
            "Duration (ms)",
            "Retries",
            "Error",
            "Looked Up At",
            "KYC",
            "Metadata"
        )
        val xmlRows = StringBuilder()
        xmlRows.append("<row r=\"1\">")
        headers.forEachIndexed { i, h ->
            xmlRows.append(inlineCell("${colLetter(i + 1)}1", h, bold = true))
        }
        xmlRows.append("</row>")

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        results.forEachIndexed { index, r ->
            val rowNum = index + 2
            val values = listOf(
                r.phoneNumber,
                r.callerName,
                r.lookupStatus,
                r.provider,
                r.lookupProviderId,
                null, // duration as number below
                null, // retries as number below
                r.error,
                fmt.format(Date(r.lookedUpAt)),
                r.kycJson,
                r.metadataJson
            )
            xmlRows.append("<row r=\"$rowNum\">")
            values.forEachIndexed { i, v ->
                val ref = "${colLetter(i + 1)}$rowNum"
                when (i) {
                    5 -> xmlRows.append(numberCell(ref, r.durationMs))
                    6 -> xmlRows.append(numberCell(ref, r.retryCount.toLong()))
                    else -> xmlRows.append(inlineCell(ref, v))
                }
            }
            xmlRows.append("</row>")
        }

        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>$xmlRows</sheetData>
            </worksheet>
        """.trimIndent()
    }
}
