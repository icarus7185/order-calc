package com.ordercalc;

/** Validation error codes, see section 6.1 of the requirement spec. */
public enum ErrorCode {
    // File level
    EMPTY_FILE,
    NO_DATA_ROWS,
    TOO_MANY_ROWS,
    MALFORMED_CSV,
    // Header level
    MISSING_COLUMN,
    DUPLICATE_COLUMN,
    UNKNOWN_COLUMN,
    // Row level
    COLUMN_COUNT_MISMATCH,
    // Cell level
    REQUIRED_VALUE_MISSING,
    INVALID_NUMBER,
    OUT_OF_RANGE,
    TOO_MANY_DECIMALS,
    VALUE_TOO_LONG
}
