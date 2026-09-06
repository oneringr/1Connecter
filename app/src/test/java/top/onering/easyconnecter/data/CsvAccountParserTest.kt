package top.onering.easyconnecter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class CsvAccountParserTest {
    private val parser = CsvAccountParser()

    @Test
    fun parsesUtf8BomQuotedCommaMultilineAndTerminalDecimalSuffix() {
        val csv = "\uFEFF学号,姓名,身份证号,班级,联系电话\n" +
            "20230001.0,\"张,三\",51000020000101123X,\"临床\n一班\",13800000000\n"

        val result = parser.parse(ByteArrayInputStream(csv.toByteArray(StandardCharsets.UTF_8)))

        assertNull(result.fatalError)
        assertEquals(1, result.accounts.size)
        assertEquals("20230001", result.accounts.single().studentId)
        assertEquals("张,三", result.accounts.single().name)
        assertEquals("临床\n一班", result.accounts.single().className)
        assertEquals("1230", result.accounts.single().password.takeLast(4))
    }

    @Test
    fun fallsBackToGb18030() {
        val csv = "学号,姓名,身份证号\n20230002,李四,510000200001011234\n"

        val result = parser.parse(
            ByteArrayInputStream(csv.toByteArray(Charset.forName("GB18030")))
        )

        assertNull(result.fatalError)
        assertEquals("李四", result.accounts.single().name)
        assertEquals("011234", result.accounts.single().password)
    }

    @Test
    fun reportsMissingRequiredHeaders() {
        val result = parser.parseContent("学号,姓名\n20230003,王五\n")

        assertTrue(result.accounts.isEmpty())
        assertTrue(result.fatalError.orEmpty().contains("身份证号"))
    }

    @Test
    fun skipsInvalidRowsAndKeepsEmbeddedDotZero() {
        val result = parser.parseContent(
            "学号,姓名,身份证号\n" +
                "2023.012.0,有效,510000200001011234\n" +
                ",无效,510000200001011234\n"
        )

        assertEquals("2023.012", result.accounts.single().studentId)
        assertEquals(1, result.warnings.size)
        assertEquals(3L, result.warnings.single().row)
    }
}
