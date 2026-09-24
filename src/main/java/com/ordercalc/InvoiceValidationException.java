package com.ordercalc;

import java.util.List;
import java.util.stream.Collectors;

/** Thrown when the input is invalid. Holds every error found in the file. */
public class InvoiceValidationException extends Exception {

    private static final int MAX_ERRORS_IN_MESSAGE = 10;

    private final List<ValidationError> errors;

    public InvoiceValidationException(List<ValidationError> errors) {
        super(buildMessage(errors));
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> getErrors() {
        return errors;
    }

    private static String buildMessage(List<ValidationError> errors) {
        String shown = errors.stream()
                .limit(MAX_ERRORS_IN_MESSAGE)
                .map(ValidationError::message)
                .collect(Collectors.joining(System.lineSeparator()));
        int hidden = errors.size() - MAX_ERRORS_IN_MESSAGE;
        String more = hidden > 0 ? System.lineSeparator() + "... and " + hidden + " more" : "";
        return "Invalid invoice input (" + errors.size() + " error(s)):" + System.lineSeparator() + shown + more;
    }
}
