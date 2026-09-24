package com.ordercalc.internal;

import com.ordercalc.ErrorCode;
import com.ordercalc.InvoiceValidationException;
import com.ordercalc.ValidationError;
import com.ordercalc.internal.CsvReader.CsvFormatException;
import com.ordercalc.internal.CsvReader.CsvRecord;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and validates the input file (sections 3 and 4.1 of the spec). Header errors stop immediately;
 * data errors are all collected and thrown at once.
 */
public final class InvoiceParser {

    public static final int MAX_ITEM_CODE_LENGTH = 50;

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final int maxDataRows;

    public InvoiceParser(int maxDataRows) {
        this.maxDataRows = maxDataRows;
    }

    /** A validated input file: the column order from the header, and the data rows. */
    public record ParsedInvoice(List<InputColumn> columns, List<InvoiceLine> lines) {
    }

    public ParsedInvoice parse(Reader reader) throws IOException, InvoiceValidationException {
        CsvReader csv = new CsvReader(reader);
        List<ValidationError> errors = new ArrayList<>();

        CsvRecord header;
        try {
            header = nextNonBlank(csv);
        } catch (CsvFormatException e) {
            throw fail(ValidationError.of(e.lineNumber(), null, ErrorCode.MALFORMED_CSV, e.getMessage()));
        }
        if (header == null) {
            throw fail(ValidationError.of(1, null, ErrorCode.EMPTY_FILE, "file is empty, header is required"));
        }
        List<InputColumn> columns = parseHeader(header, errors);
        if (!errors.isEmpty()) {
            throw new InvoiceValidationException(errors);
        }

        List<InvoiceLine> lines = new ArrayList<>();
        int dataRows = 0;
        while (true) {
            CsvRecord record;
            try {
                record = csv.next();
            } catch (CsvFormatException e) {
                errors.add(ValidationError.of(e.lineNumber(), null, ErrorCode.MALFORMED_CSV, e.getMessage()));
                break;
            }
            if (record == null) {
                break;
            }
            if (record.blank()) {
                continue;
            }
            dataRows++;
            if (dataRows > maxDataRows) {
                errors.add(ValidationError.of(record.lineNumber(), null, ErrorCode.TOO_MANY_ROWS,
                        "file must have at most " + maxDataRows + " data rows"));
                break;
            }
            InvoiceLine line = parseLine(record, columns, errors);
            if (line != null) {
                lines.add(line);
            }
        }

        if (dataRows == 0 && errors.isEmpty()) {
            errors.add(ValidationError.of(header.lineNumber(), null, ErrorCode.NO_DATA_ROWS,
                    "file must have at least 1 data row"));
        }
        if (!errors.isEmpty()) {
            throw new InvoiceValidationException(errors);
        }
        return new ParsedInvoice(columns, lines);
    }

    private static CsvRecord nextNonBlank(CsvReader csv) throws IOException, CsvFormatException {
        CsvRecord record;
        do {
            record = csv.next();
        } while (record != null && record.blank());
        return record;
    }

    private static List<InputColumn> parseHeader(CsvRecord header, List<ValidationError> errors) {
        int line = header.lineNumber();
        List<InputColumn> columns = new ArrayList<>();
        Set<InputColumn> seen = EnumSet.noneOf(InputColumn.class);
        for (String name : header.fields()) {
            Optional<InputColumn> column = InputColumn.fromHeader(name);
            if (column.isEmpty()) {
                errors.add(ValidationError.of(line, name.strip(), ErrorCode.UNKNOWN_COLUMN, "unknown column"));
            } else if (!seen.add(column.get())) {
                errors.add(ValidationError.of(line, column.get().headerName(), ErrorCode.DUPLICATE_COLUMN,
                        "column appears more than once"));
            } else {
                columns.add(column.get());
            }
        }
        for (InputColumn column : InputColumn.values()) {
            if (!seen.contains(column)) {
                errors.add(ValidationError.of(line, column.headerName(), ErrorCode.MISSING_COLUMN,
                        "required column is missing"));
            }
        }
        return columns;
    }

    private static InvoiceLine parseLine(CsvRecord record, List<InputColumn> columns, List<ValidationError> errors) {
        int line = record.lineNumber();
        if (record.fields().size() != columns.size()) {
            errors.add(ValidationError.of(line, null, ErrorCode.COLUMN_COUNT_MISMATCH,
                    "expected " + columns.size() + " columns but found " + record.fields().size()));
            return null;
        }

        List<String> values = new ArrayList<>(columns.size());
        Map<InputColumn, BigDecimal> numbers = new EnumMap<>(InputColumn.class);
        boolean valid = true;
        for (int i = 0; i < columns.size(); i++) {
            InputColumn column = columns.get(i);
            String value = record.fields().get(i).strip();
            values.add(value);
            ValidationError error = column.isNumeric()
                    ? validateNumber(line, column, value, numbers)
                    : validateItemCode(line, column, value);
            if (error != null) {
                errors.add(error);
                valid = false;
            }
        }
        if (!valid) {
            return null;
        }
        return new InvoiceLine(List.copyOf(values),
                numbers.get(InputColumn.QUANTITY),
                numbers.get(InputColumn.UNIT_PRICE),
                numbers.get(InputColumn.VAT_RATE));
    }

    private static ValidationError validateItemCode(int line, InputColumn column, String value) {
        if (value.isEmpty()) {
            return ValidationError.of(line, column.headerName(), ErrorCode.REQUIRED_VALUE_MISSING, "value is required");
        }
        if (value.codePointCount(0, value.length()) > MAX_ITEM_CODE_LENGTH) {
            return ValidationError.of(line, column.headerName(), ErrorCode.VALUE_TOO_LONG,
                    "value must be at most " + MAX_ITEM_CODE_LENGTH + " characters");
        }
        return null;
    }

    private static ValidationError validateNumber(int line, InputColumn column, String value,
                                                  Map<InputColumn, BigDecimal> numbers) {
        String name = column.headerName();
        if (value.isEmpty()) {
            return ValidationError.of(line, name, ErrorCode.REQUIRED_VALUE_MISSING, "value is required");
        }
        if (!NUMBER.matcher(value).matches()) {
            return ValidationError.of(line, name, ErrorCode.INVALID_NUMBER,
                    "value '" + value + "' is not a valid number");
        }
        BigDecimal number = new BigDecimal(value);
        int cmp = number.compareTo(column.lowerBound());
        if (column.lowerBoundExclusive() ? cmp <= 0 : cmp < 0) {
            String bound = column.lowerBoundExclusive() ? "greater than " : "greater than or equal to ";
            return ValidationError.of(line, name, ErrorCode.OUT_OF_RANGE,
                    "value '" + value + "' must be " + bound + column.lowerBound().toPlainString());
        }
        if (column.maxDecimals() >= 0 && number.stripTrailingZeros().scale() > column.maxDecimals()) {
            return ValidationError.of(line, name, ErrorCode.TOO_MANY_DECIMALS,
                    "value '" + value + "' must have at most " + column.maxDecimals() + " decimal places");
        }
        numbers.put(column, number);
        return null;
    }

    private static InvoiceValidationException fail(ValidationError error) {
        return new InvoiceValidationException(List.of(error));
    }
}
