package com.ordercalc.internal;

import java.math.BigDecimal;
import java.util.List;

/**
 * A validated data row.
 *
 * @param values original trimmed values, in the column order of the input header
 */
public record InvoiceLine(List<String> values, BigDecimal quantity, BigDecimal unitPrice, BigDecimal vatRate) {
}
