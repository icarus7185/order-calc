package com.ordercalc;

import com.ordercalc.internal.CsvWriter;
import com.ordercalc.internal.InputColumn;
import com.ordercalc.internal.InvoiceLine;
import com.ordercalc.internal.InvoiceParser;
import com.ordercalc.internal.InvoiceParser.ParsedInvoice;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reads one invoice CSV file, validates it, calculates the VAT of each row and the invoice totals, then
 * writes the result CSV file.
 *
 * <p>Row values are calculated exactly, without rounding. Only the 3 totals are rounded, each one
 * independently, according to {@link CalculatorConfig}. Nothing is written when the input is invalid.
 *
 * <p>Thread-safe: no state is kept between calls.
 */
public final class InvoiceCalculator {

    /** Maximum number of data rows in one input file. */
    public static final int MAX_DATA_ROWS = 10_000;

    static final String TOTAL_LABEL = "TOTAL";
    static final List<String> COMPUTED_COLUMNS = List.of("vat_amount", "amount_before_tax", "amount_after_tax");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CalculatorConfig config;

    public InvoiceCalculator() {
        this(CalculatorConfig.DEFAULT);
    }

    public InvoiceCalculator(CalculatorConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Reads {@code input}, validates it, calculates and writes {@code output}. The output is written to a
     * temporary file in the same directory and then renamed, so an existing output file is only replaced
     * when processing succeeds.
     */
    public InvoiceResult process(Path input, Path output) throws InvoiceValidationException, IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");

        ParsedInvoice invoice;
        try (Reader reader = newReader(Files.newInputStream(input))) {
            invoice = parse(reader);
        }

        Path target = output.toAbsolutePath();
        Path temp = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            InvoiceResult result;
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                result = write(invoice, writer);
            }
            moveReplacing(temp, target);
            return result;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Stream variant. Does not close {@code input} or {@code output}; when the input is invalid, no bytes
     * are written to {@code output}.
     */
    public InvoiceResult process(InputStream input, OutputStream output)
            throws InvoiceValidationException, IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");

        ParsedInvoice invoice = parse(newReader(input));
        Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        InvoiceResult result = write(invoice, writer);
        writer.flush();
        return result;
    }

    private static ParsedInvoice parse(Reader reader) throws IOException, InvoiceValidationException {
        return new InvoiceParser(MAX_DATA_ROWS).parse(reader);
    }

    private InvoiceResult write(ParsedInvoice invoice, Writer out) throws IOException {
        CsvWriter csv = new CsvWriter(out);
        List<InputColumn> columns = invoice.columns();

        List<String> header = new ArrayList<>();
        columns.forEach(c -> header.add(c.headerName()));
        header.addAll(COMPUTED_COLUMNS);
        csv.writeRow(header);

        BigDecimal sumBefore = BigDecimal.ZERO;
        BigDecimal sumVat = BigDecimal.ZERO;
        BigDecimal sumAfter = BigDecimal.ZERO;
        for (InvoiceLine line : invoice.lines()) {
            BigDecimal before = line.quantity().multiply(line.unitPrice());
            BigDecimal vat = before.multiply(line.vatRate()).divide(HUNDRED);
            BigDecimal after = before.add(vat);
            sumBefore = sumBefore.add(before);
            sumVat = sumVat.add(vat);
            sumAfter = sumAfter.add(after);

            List<String> row = new ArrayList<>(line.values());
            row.add(formatLine(vat));
            row.add(formatLine(before));
            row.add(formatLine(after));
            csv.writeRow(row);
        }

        BigDecimal totalBefore = round(sumBefore);
        BigDecimal totalVat = round(sumVat);
        BigDecimal totalAfter = round(sumAfter);

        List<String> total = new ArrayList<>(Collections.nCopies(columns.size(), ""));
        total.set(columns.indexOf(InputColumn.ITEM_CODE), TOTAL_LABEL);
        total.add(totalVat.toPlainString());
        total.add(totalBefore.toPlainString());
        total.add(totalAfter.toPlainString());
        csv.writeRow(total);

        return new InvoiceResult(invoice.lines().size(), totalBefore, totalVat, totalAfter);
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(config.totalScale(), config.roundingMode());
    }

    private static String formatLine(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static Reader newReader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)));
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
