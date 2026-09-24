package com.ordercalc.internal;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/** Input file columns and the validation rules of each column (section 3.3 of the spec). */
public enum InputColumn {
    ITEM_CODE("item_code", null, false, 0),
    QUANTITY("quantity", BigDecimal.ZERO, true, 3),
    UNIT_PRICE("unit_price", BigDecimal.ZERO, false, 2),
    VAT_RATE("vat_rate", BigDecimal.ZERO, false, -1);

    private final String headerName;
    private final BigDecimal lowerBound;
    private final boolean lowerBoundExclusive;
    private final int maxDecimals;

    InputColumn(String headerName, BigDecimal lowerBound, boolean lowerBoundExclusive, int maxDecimals) {
        this.headerName = headerName;
        this.lowerBound = lowerBound;
        this.lowerBoundExclusive = lowerBoundExclusive;
        this.maxDecimals = maxDecimals;
    }

    public String headerName() {
        return headerName;
    }

    boolean isNumeric() {
        return lowerBound != null;
    }

    BigDecimal lowerBound() {
        return lowerBound;
    }

    boolean lowerBoundExclusive() {
        return lowerBoundExclusive;
    }

    /** Maximum number of decimal places; negative means unlimited. */
    int maxDecimals() {
        return maxDecimals;
    }

    /** Looks up a column by header name, case-insensitive, ignoring leading and trailing whitespace. */
    static Optional<InputColumn> fromHeader(String name) {
        String normalized = name.strip().toLowerCase(Locale.ROOT);
        for (InputColumn column : values()) {
            if (column.headerName.equals(normalized)) {
                return Optional.of(column);
            }
        }
        return Optional.empty();
    }
}
