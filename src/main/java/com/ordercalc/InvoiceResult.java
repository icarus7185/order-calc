package com.ordercalc;

import java.math.BigDecimal;

/**
 * Result of processing an invoice. The totals are rounded and match the TOTAL row of the output file.
 *
 * @param lineCount      number of data rows
 * @param totalBeforeTax round(Σ amount_before_tax)
 * @param totalVat       round(Σ vat_amount)
 * @param totalAfterTax  round(Σ amount_after_tax)
 */
public record InvoiceResult(
        int lineCount,
        BigDecimal totalBeforeTax,
        BigDecimal totalVat,
        BigDecimal totalAfterTax) {
}
