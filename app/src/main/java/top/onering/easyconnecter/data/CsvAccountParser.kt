package top.onering.easyconnecter.data

import org.apache.commons.csv.CSVFormat
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.CsvImportResult
import top.onering.easyconnecter.model.CsvImportWarning
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

class CsvAccountParser {
    fun parse(inputStream: InputStream): CsvImportResult {
        return try {
            val content = decode(inputStream.readBytes())
            parseContent(content)
        } catch (error: Exception) {
            CsvImportResult(emptyList(), fatalError = "读取 CSV 失败: ${error.message ?: "未知错误"}")
        }
    }

    internal fun parseContent(content: String): CsvImportResult {
        val format = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get()

        return try {
            format.parse(content.reader()).use { parser ->
                val headers = parser.headerMap.keys
                val missing = REQUIRED_HEADERS.filterNot(headers::contains)
                if (missing.isNotEmpty()) {
                    return CsvImportResult(
                        emptyList(),
                        fatalError = "CSV 缺少必要表头: ${missing.joinToString("、")}"
                    )
                }

                val accounts = mutableListOf<Account>()
                val warnings = mutableListOf<CsvImportWarning>()
                parser.forEach { record ->
                    val row = record.recordNumber + 1
                    fun value(header: String): String =
                        if (record.isMapped(header) && record.isSet(header)) record.get(header).trim() else ""

                    val studentId = value("学号").replace(Regex("\\.0$"), "")
                    val idCard = value("身份证号")
                    if (studentId.isBlank() || idCard.isBlank()) {
                        warnings += CsvImportWarning(row, "学号或身份证号为空，已跳过")
                        return@forEach
                    }

                    val password = if (idCard.length >= 6) {
                        idCard.takeLast(6).replace("X", "0", ignoreCase = true)
                    } else {
                        warnings += CsvImportWarning(row, "身份证号不足 6 位，使用默认密码")
                        "123456"
                    }

                    accounts += Account(
                        studentId = studentId,
                        password = password,
                        name = value("姓名"),
                        className = value("班级"),
                        phone = value("联系电话"),
                        idCard = idCard
                    )
                }
                CsvImportResult(accounts, warnings)
            }
        } catch (error: Exception) {
            CsvImportResult(emptyList(), fatalError = "CSV 格式错误: ${error.message ?: "未知错误"}")
        }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8Bytes = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(utf8Bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
        }
    }

    private companion object {
        val REQUIRED_HEADERS = listOf("学号", "身份证号")
    }
}
