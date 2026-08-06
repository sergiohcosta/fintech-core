package com.fintech.mobile.core.format

object AmountParser {

    // Mesma inferência "por valor" do CsvExtractor do backend (ver summary.md):
    // dois separadores → o último é o decimal; só vírgula → vírgula é decimal.
    fun parse(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val hasComma = trimmed.contains(',')
        val hasDot = trimmed.contains('.')

        val normalized = when {
            hasComma && hasDot -> {
                if (trimmed.lastIndexOf(',') > trimmed.lastIndexOf('.')) {
                    trimmed.replace(".", "").replace(",", ".")
                } else {
                    trimmed.replace(",", "")
                }
            }
            hasComma -> trimmed.replace(",", ".")
            else -> trimmed
        }

        return normalized.toDoubleOrNull()
    }
}
