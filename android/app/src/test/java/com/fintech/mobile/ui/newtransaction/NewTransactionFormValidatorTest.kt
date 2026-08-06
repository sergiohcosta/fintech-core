package com.fintech.mobile.ui.newtransaction

import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NewTransactionFormValidatorTest {

    private val accountId = UUID.randomUUID()

    @Test
    fun `valid form without installments parses amount and account`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "150,00",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Valid>(result)
        assertEquals("Mercado", result.form.description)
        assertEquals(150.0, result.form.amount)
        assertEquals(accountId, result.form.accountId)
        assertEquals(null, result.form.totalInstallments)
    }

    @Test
    fun `blank description is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "  ",
            amountText = "150,00",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("description"))
    }

    @Test
    fun `unparseable amount is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "abc",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("amount"))
    }

    @Test
    fun `missing account is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "150,00",
            accountId = null,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("accountId"))
    }

    @Test
    fun `valid installments count is parsed when required`() {
        val result = NewTransactionFormValidator.validate(
            description = "Notebook",
            amountText = "3000",
            accountId = accountId,
            totalInstallmentsText = "10",
            requiresInstallments = true
        )

        assertIs<FormValidationResult.Valid>(result)
        assertEquals(10, result.form.totalInstallments)
    }

    @Test
    fun `NaN amount is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "NaN",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("amount"))
    }

    @Test
    fun `Infinity amount is a field error`() {
        val result = NewTransactionFormValidator.validate(
            description = "Mercado",
            amountText = "Infinity",
            accountId = accountId,
            totalInstallmentsText = "",
            requiresInstallments = false
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("amount"))
    }

    @Test
    fun `zero installments is a field error when required`() {
        val result = NewTransactionFormValidator.validate(
            description = "Notebook",
            amountText = "3000",
            accountId = accountId,
            totalInstallmentsText = "0",
            requiresInstallments = true
        )

        assertIs<FormValidationResult.Invalid>(result)
        assertTrue(result.fieldErrors.containsKey("totalInstallments"))
    }
}
