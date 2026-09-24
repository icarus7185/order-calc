package com.ordercalc;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * Rounding settings for the invoice totals.
 *
 * @param totalScale   number of decimal places of the totals, default 1
 * @param roundingMode rounding mode, default {@link RoundingMode#HALF_UP}
 */
public record CalculatorConfig(int totalScale, RoundingMode roundingMode) {

    public static final CalculatorConfig DEFAULT = new CalculatorConfig(1, RoundingMode.HALF_UP);

    public CalculatorConfig {
        Objects.requireNonNull(roundingMode, "roundingMode");
        if (totalScale < 0) {
            throw new IllegalArgumentException("totalScale must be >= 0: " + totalScale);
        }
        if (roundingMode == RoundingMode.UNNECESSARY) {
            throw new IllegalArgumentException("roundingMode UNNECESSARY is not supported");
        }
    }
}
