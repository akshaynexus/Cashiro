package com.ritesh.cashiro.presentation.ui.features.settings.dataprivacy

import com.ritesh.cashiro.data.backup.BackupConfiguration
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import java.io.File

data class PdfAnalysisResult(
    val pendingTransactions: List<com.ritesh.parser.core.ParsedTransaction>,
    val transactionItems: List<PdfTransactionImportItem>,
    val transactionCount: Int,
    val accountMatches: List<PdfAccountMatch>
)

data class PdfTransactionImportItem(
    val parsed: com.ritesh.parser.core.ParsedTransaction,
    val duplicateMatch: TransactionEntity? = null,
    val initialDecision: TransactionImportDecision = if (duplicateMatch != null) TransactionImportDecision.SKIP else TransactionImportDecision.IMPORT_NEW
)

enum class TransactionImportDecision { IMPORT_NEW, SKIP, OVERRIDE_EXISTING }

data class PdfAccountMatch(
    val last4: String,
    val bankNameInPdf: String,
    val existingAccount: AccountBalanceEntity?
) {
    val hasExistingMatch: Boolean get() = existingAccount != null
}

enum class AccountImportDecision { MERGE_WITH_EXISTING, CREATE_NEW }

data class DataPrivacyUiState(
    val importExportMessage: String? = null,
    val exportedBackupFile: File? = null,
    val backupConfiguration: BackupConfiguration = BackupConfiguration(),
    val hasNewAccountsCreated: Boolean = false,

    val isPdfProcessing: Boolean = false,
    val pdfAnalysisResult: PdfAnalysisResult? = null,
    val pdfProcessingError: String? = null
)
