package com.fintech.mobile.ui.newtransaction

import com.fintech.mobile.core.format.AmountParser
import java.util.UUID

data class ParsedTransactionForm(
    val description: String,
    val amount: Double,
    val accountId: UUID,
    val totalInstallments: Int?
)

sealed class FormValidationResult {
    data class Valid(val form: ParsedTransactionForm) : FormValidationResult()
    data class Invalid(val fieldErrors: Map<String, String>) : FormValidationResult()
}

object NewTransactionFormValidator {

    fun validate(
        description: String,
        amountText: String,
        accountId: UUID?,
        totalInstallmentsText: String,
        requiresInstallments: Boolean
    ): FormValidationResult {
        val errors = mutableMapOf<String, String>()

        if (description.isBlank()) errors["description"] = "Informe uma descrição"

        val amount = AmountParser.parse(amountText)
        // AmountParser delega a toDoubleOrNull(), que aceita "NaN"/"Infinity" (IEEE754 válidos
        // como Double). `amount < 0.01` é sempre false para NaN (toda comparação com NaN é
        // false), então esses valores passavam a validação e só quebravam depois, no Gson
        // (IllegalArgumentException fora do apiCall, crash não tratado da coroutine).
        if (amount == null || !amount.isFinite() || amount < 0.01) errors["amount"] = "Informe um valor válido"

        if (accountId == null) errors["accountId"] = "Selecione uma conta"

        var totalInstallments: Int? = null
        if (requiresInstallments && totalInstallmentsText.isNotBlank()) {
            totalInstallments = totalInstallmentsText.toIntOrNull()
            if (totalInstallments == null || totalInstallments < 1) {
                errors["totalInstallments"] = "Número de parcelas inválido"
            }
        }

        if (errors.isNotEmpty()) return FormValidationResult.Invalid(errors)

        return FormValidationResult.Valid(
            ParsedTransactionForm(
                description = description.trim(),
                amount = amount!!,
                accountId = accountId!!,
                totalInstallments = totalInstallments
            )
        )
    }
}
