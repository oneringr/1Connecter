package top.onering.easyconnecter.model

data class CsvImportWarning(
    val row: Long,
    val reason: String
)

data class CsvImportResult(
    val accounts: List<Account>,
    val warnings: List<CsvImportWarning> = emptyList(),
    val fatalError: String? = null
)
