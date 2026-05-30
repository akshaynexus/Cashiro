package com.ritesh.cashiro.presentation.ui.features.settings.importstatement

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ritesh.cashiro.data.database.entity.AccountBalanceEntity
import com.ritesh.cashiro.data.database.entity.TransactionEntity
import com.ritesh.cashiro.data.repository.AccountBalanceRepository
import com.ritesh.cashiro.data.repository.TransactionRepository
import com.ritesh.cashiro.data.statement.PdfParserFactory
import com.ritesh.cashiro.data.statement.PdfTextExtractor
import com.ritesh.cashiro.data.statement.StatementImportProcessor
import com.ritesh.cashiro.data.statement.StatementImportResult
import com.ritesh.cashiro.presentation.ui.features.settings.dataprivacy.AccountImportDecision
import com.ritesh.cashiro.presentation.ui.features.settings.dataprivacy.PdfAccountMatch
import com.ritesh.cashiro.presentation.ui.features.settings.dataprivacy.PdfAnalysisResult
import com.ritesh.cashiro.presentation.ui.features.settings.dataprivacy.PdfTransactionImportItem
import com.ritesh.cashiro.presentation.ui.features.settings.dataprivacy.TransactionImportDecision
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

sealed class ImportStatementUiState {
    data object Idle : ImportStatementUiState()
    data class Loading(val progress: Float = 0f) : ImportStatementUiState()
    data class Review(val analysisResult: PdfAnalysisResult) : ImportStatementUiState()
    data class Success(val result: StatementImportResult.Success) : ImportStatementUiState()
    data class Error(val message: String) : ImportStatementUiState()
}

@HiltViewModel
class ImportStatementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val accountBalanceRepository: AccountBalanceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportStatementUiState>(ImportStatementUiState.Idle)
    val uiState: StateFlow<ImportStatementUiState> = _uiState.asStateFlow()

    fun importStatement(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _uiState.value = ImportStatementUiState.Loading(0f)
        viewModelScope.launch {
            try {
                val uri = uris.first()
                val text = PdfTextExtractor.extractText(context, uri)

                val parser = PdfParserFactory.getParser(text)
                if (parser == null) {
                    _uiState.value = ImportStatementUiState.Error("Unsupported statement format. Please use a GPay or PhonePe PDF.")
                    return@launch
                }

                val parsedTransactions = parser.parse(text)
                if (parsedTransactions.isEmpty()) {
                    _uiState.value = ImportStatementUiState.Error("No transactions found in this PDF.")
                    return@launch
                }

                val distinctLast4s = parsedTransactions.mapNotNull { it.accountLast4 }.distinct()
                val accountMatches = distinctLast4s.map { last4 ->
                    val existing = accountBalanceRepository.getAccountByLast4(last4)
                    PdfAccountMatch(
                        last4 = last4,
                        bankNameInPdf = parsedTransactions.firstOrNull { it.accountLast4 == last4 }?.bankName ?: "PhonePe",
                        existingAccount = existing
                    )
                }

                val transactionItems = parsedTransactions.map { parsed ->
                    val dateTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(parsed.timestamp), ZoneId.systemDefault()
                    )
                    val potentialDuplicates = transactionRepository.findPotentialDuplicates(
                        amount = parsed.amount,
                        startDate = dateTime.minusMinutes(15),
                        endDate = dateTime.plusMinutes(15)
                    )

                    val duplicateMatch = potentialDuplicates.find { existing ->
                        val parsedUtr = parsed.reference?.replace(Regex("""\D"""), "")
                        if (!parsedUtr.isNullOrEmpty()) {
                            val existingUtr = extractUtr(existing.smsBody) ?: extractUtr(existing.description)
                            if (existingUtr == parsedUtr) return@find true
                        }
                        val existingAcc = existing.accountNumber
                        val parsedAcc = parsed.accountLast4
                        if (existingAcc == null || parsedAcc == null) return@find true
                        val existingDigits = existingAcc.replace(Regex("""\D"""), "")
                        val parsedDigits = parsedAcc.replace(Regex("""\D"""), "")
                        val existingLast4 = existingDigits.takeLast(4)
                        val parsedLast4 = parsedDigits.takeLast(4)
                        if (existingLast4 == parsedLast4 && existingLast4.isNotEmpty()) return@find true
                        false
                    }

                    PdfTransactionImportItem(
                        parsed = parsed,
                        duplicateMatch = duplicateMatch
                    )
                }

                _uiState.value = ImportStatementUiState.Review(
                    PdfAnalysisResult(
                        pendingTransactions = parsedTransactions,
                        transactionItems = transactionItems,
                        transactionCount = parsedTransactions.size,
                        accountMatches = accountMatches
                    )
                )
            } catch (e: Exception) {
                Log.e("ImportStatementVM", "Error analyzing PDF", e)
                _uiState.value = ImportStatementUiState.Error(e.message ?: "Failed to analyze PDF")
            }
        }
    }

    fun confirmPdfImport(
        accountDecisions: Map<String, AccountImportDecision>,
        transactionDecisions: Map<Int, TransactionImportDecision>
    ) {
        val state = _uiState.value
        if (state !is ImportStatementUiState.Review) return
        val analysis = state.analysisResult

        viewModelScope.launch {
            try {
                _uiState.value = ImportStatementUiState.Loading(0f)

                // Phase 1: Resolve accounts (0 to 0.1)
                val resolvedAccounts = mutableMapOf<String, Pair<String, String>>()
                for (match in analysis.accountMatches) {
                    val decision = accountDecisions[match.last4] ?: AccountImportDecision.MERGE_WITH_EXISTING
                    val accountPair: Pair<String, String> = if (decision == AccountImportDecision.MERGE_WITH_EXISTING && match.existingAccount != null) {
                        match.existingAccount.bankName to match.last4
                    } else {
                        val newAccount = AccountBalanceEntity(
                            bankName = match.bankNameInPdf,
                            accountLast4 = match.last4,
                            balance = BigDecimal.ZERO,
                            timestamp = LocalDateTime.now(),
                            sourceType = "PDF_IMPORT",
                            iconName = "type_finance_bank"
                        )
                        val existing = accountBalanceRepository.getLatestBalance(match.bankNameInPdf, match.last4)
                        if (existing == null) {
                            accountBalanceRepository.insertBalance(newAccount)
                        }
                        match.bankNameInPdf to match.last4
                    }
                    resolvedAccounts[match.last4] = accountPair
                }

                // Phase 2: Collect transactions to import and handle overrides
                val toImport = mutableListOf<com.ritesh.parser.core.ParsedTransaction>()
                for ((index, item) in analysis.transactionItems.withIndex()) {
                    val decision = transactionDecisions[index] ?: item.initialDecision
                    when (decision) {
                        TransactionImportDecision.SKIP -> {}
                        TransactionImportDecision.OVERRIDE_EXISTING -> {
                            item.duplicateMatch?.let { transactionRepository.deleteTransaction(it, hardDelete = true) }
                            toImport.add(item.parsed)
                        }
                        TransactionImportDecision.IMPORT_NEW -> {
                            toImport.add(item.parsed)
                        }
                    }
                }

                // Phase 3: Use StatementImportProcessor for dedup, enrich, and insert (0.1 to 1.0)
                val totalCount = analysis.transactionItems.size
                val accountsPhaseWeight = 0.1f
                val importPhaseWeight = 0.9f

                val result = withContext(Dispatchers.IO) {
                    StatementImportProcessor(repositoryStore()).process(toImport) { fileProgress ->
                        val overallProgress = accountsPhaseWeight + (fileProgress * importPhaseWeight)
                        _uiState.value = ImportStatementUiState.Loading(overallProgress)
                    }
                }

                _uiState.value = ImportStatementUiState.Success(result)
            } catch (e: Exception) {
                Log.e("ImportStatementVM", "Error committing PDF import", e)
                _uiState.value = ImportStatementUiState.Error(e.message ?: "Failed to import transactions")
            }
        }
    }

    fun resetState() {
        _uiState.value = ImportStatementUiState.Idle
    }

    private fun repositoryStore() = object : StatementImportProcessor.TransactionStore {
        override suspend fun getTransactionByHash(transactionHash: String): TransactionEntity? =
            transactionRepository.getTransactionByHash(transactionHash)

        override suspend fun findStatementMergeCandidate(
            transaction: TransactionEntity
        ): TransactionEntity? =
            transactionRepository.findStatementMergeCandidate(transaction)

        override suspend fun updateTransaction(transaction: TransactionEntity) {
            transactionRepository.updateTransaction(transaction)
        }

        override suspend fun getTransactionByAmountAndDate(
            amount: BigDecimal,
            dateStart: LocalDateTime,
            dateEnd: LocalDateTime
        ): List<TransactionEntity> =
            transactionRepository.getTransactionByAmountAndDate(amount, dateStart, dateEnd)

        override suspend fun insertTransactions(transactions: List<TransactionEntity>) {
            transactionRepository.insertTransactions(transactions)
        }
    }

    private fun extractUtr(text: String?): String? {
        if (text == null) return null
        val utrRegex = Regex("""(?:UPI[:\s]*|UTR\s+No\.?[:\s]*|Ref\s+No\.?[:\s]*|ID[:\s]*)([\d\s]+)""", RegexOption.IGNORE_CASE)
        return utrRegex.find(text)?.groupValues?.get(1)?.replace(Regex("""\D"""), "")
    }
}
