package com.ordercalc;

import java.util.Objects;

/**
 * A single validation error.
 *
 * @param lineNumber physical line number in the file, header = 1
 * @param column     column name; {@code null} for file-, header- or row-level errors
 * @param code       error code
 * @param message    full description, for example
 *                   {@code Line 3, column 'quantity': value '-2' must be greater than 0 (OUT_OF_RANGE)}
 */
public record ValidationError(int lineNumber, String column, ErrorCode code, String message) {

    public ValidationError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    /** Creates an error and builds {@code message} in the standard format. */
    public static ValidationError of(int lineNumber, String column, ErrorCode code, String detail) {
        String location = column == null
                ? "Line " + lineNumber
                : "Line " + lineNumber + ", column '" + column + "'";
        return new ValidationError(lineNumber, column, code, location + ": " + detail + " (" + code + ")");
    }

    @Override
    public String toString() {
        return message;
    }
}
