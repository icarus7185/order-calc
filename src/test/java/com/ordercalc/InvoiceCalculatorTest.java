package com.ordercalc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceCalculatorTest {

    private static final String HEADER = "item_code,quantity,unit_price,vat_rate\n";
    private static final String OUT_HEADER =
            "item_code,quantity,unit_price,vat_rate,vat_amount,amount_before_tax,amount_after_tax\n";

    private final InvoiceCalculator calculator = new InvoiceCalculator();

    @TempDir
    Path tempDir;

    // ---------- helpers ----------

    private String process(String input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        calculator.process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private List<ValidationError> errorsOf(String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> calculator.process(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out));
        assertEquals(0, out.size(), "nothing must be written when input is invalid");
        return e.getErrors();
    }

    private static ValidationError single(List<ValidationError> errors) {
        assertEquals(1, errors.size(), errors.toString());
        return errors.get(0);
    }

    private static String rows(int count) {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= count; i++) {
            sb.append("SP").append(i).append(",1.5,33333.33,8\n");
        }
        return sb.toString();
    }

    // ---------- AC-1: example from section 5.4 ----------

    @Test
    void ac1_specExample_matchesByteByByte() throws Exception {
        Path input = tempDir.resolve("invoice.csv");
        Path output = tempDir.resolve("result.csv");
        Files.writeString(input, HEADER
                + "SP001,2,100000,10\n"
                + "SP002,3,15500,8\n"
                + "SP003,1.5,33333.33,8\n", StandardCharsets.UTF_8);

        InvoiceResult result = calculator.process(input, output);

        String expected = OUT_HEADER
                + "SP001,2,100000,10,20000,200000,220000\n"
                + "SP002,3,15500,8,3720,46500,50220\n"
                + "SP003,1.5,33333.33,8,3999.9996,49999.995,53999.9946\n"
                + "TOTAL,,,,27720.0,296500.0,324220.0\n";
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(output));
        assertEquals(new InvoiceResult(3, new BigDecimal("296500.0"), new BigDecimal("27720.0"),
                new BigDecimal("324220.0")), result);
        try (var files = Files.list(tempDir)) {
            assertEquals(2, files.count(), "temp file must be cleaned up");
        }
    }

    @Test
    void docsSampleOutputIsUpToDate() throws Exception {
        Path output = tempDir.resolve("sample-output.csv");
        calculator.process(Path.of("docs/sample-input.csv"), output);
        assertArrayEquals(Files.readAllBytes(Path.of("docs/sample-output.csv")), Files.readAllBytes(output),
                "docs/sample-output.csv is stale; regenerate it from docs/sample-input.csv");
    }

    // ---------- AC-2, AC-3: header / file ----------

    @Test
    void ac2_missingColumn() {
        ValidationError e = single(errorsOf("item_code,quantity,unit_price\nSP1,1,1\n"));
        assertEquals(ErrorCode.MISSING_COLUMN, e.code());
        assertEquals("vat_rate", e.column());
        assertEquals(1, e.lineNumber());
    }

    @Test
    void ac2_missingColumn_fileDoesNotCreateOutput() throws Exception {
        Path input = tempDir.resolve("in.csv");
        Path output = tempDir.resolve("out.csv");
        Files.writeString(input, "item_code,quantity,unit_price\nSP1,1,1\n");
        assertThrows(InvoiceValidationException.class, () -> calculator.process(input, output));
        assertFalse(Files.exists(output));
        try (var files = Files.list(tempDir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void ac3_headerOnly_noDataRows() {
        assertEquals(ErrorCode.NO_DATA_ROWS, single(errorsOf(HEADER + "\n\n")).code());
    }

    @Test
    void emptyFile() {
        assertEquals(ErrorCode.EMPTY_FILE, single(errorsOf("")).code());
        assertEquals(ErrorCode.EMPTY_FILE, single(errorsOf("\n  \n")).code());
    }

    @Test
    void duplicateAndUnknownColumns_allReported() {
        List<ValidationError> errors = errorsOf("item_code,quantity,Quantity,unit_price,vat_rate,note\n");
        assertEquals(List.of(ErrorCode.DUPLICATE_COLUMN, ErrorCode.UNKNOWN_COLUMN),
                errors.stream().map(ValidationError::code).toList());
        assertEquals("note", errors.get(1).column());
    }

    // ---------- AC-4: all errors are collected ----------

    @Test
    void ac4_allErrorsCollected() {
        List<ValidationError> errors = errorsOf(HEADER
                + "SP1,1,1,1\n"
                + "SP2,abc,1,1\n"
                + "SP3,1,1,1\n"
                + "SP4,1,1,-1\n");
        assertEquals(2, errors.size(), errors.toString());
        assertEquals(ErrorCode.INVALID_NUMBER, errors.get(0).code());
        assertEquals(3, errors.get(0).lineNumber());
        assertEquals("quantity", errors.get(0).column());
        assertEquals(ErrorCode.OUT_OF_RANGE, errors.get(1).code());
        assertEquals(5, errors.get(1).lineNumber());
        assertEquals("vat_rate", errors.get(1).column());
    }

    @Test
    void errorMessageFormat() {
        ValidationError e = single(errorsOf(HEADER + "SP1,-2,1,1\n"));
        assertEquals("Line 2, column 'quantity': value '-2' must be greater than 0 (OUT_OF_RANGE)", e.message());
    }

    // ---------- AC-5, AC-6, AC-13: rounding on totals only ----------

    @Test
    void ac5_roundingOnTotalsOnly() throws Exception {
        assertEquals(OUT_HEADER
                + "SP1,3,3333,8,799.92,9999,10798.92\n"
                + "TOTAL,,,,799.9,9999.0,10798.9\n", process(HEADER + "SP1,3,3333,8\n"));
    }

    @Test
    void ac6_linesAreNotRoundedBeforeSumming() throws Exception {
        assertEquals(OUT_HEADER
                + "SP1,1,0.5,8,0.04,0.5,0.54\n"
                + "SP2,1,0.5,8,0.04,0.5,0.54\n"
                + "TOTAL,,,,0.1,1.0,1.1\n", process(HEADER + "SP1,1,0.5,8\nSP2,1,0.5,8\n"));
    }

    @Test
    void ac13_totalsRoundedIndependently() throws Exception {
        assertEquals(OUT_HEADER
                + "SP1,1,0.05,100,0.05,0.05,0.1\n"
                + "TOTAL,,,,0.1,0.1,0.1\n", process(HEADER + "SP1,1,0.05,100\n"));
    }

    @Test
    void roundingIsHalfUp() throws Exception {
        // 0.25 -> 0.3 with HALF_UP (HALF_EVEN would give 0.2)
        assertTrue(process(HEADER + "SP1,1,0.25,0\n").endsWith("TOTAL,,,,0.0,0.3,0.3\n"));
    }

    @Test
    void customConfig() throws Exception {
        InvoiceCalculator custom = new InvoiceCalculator(new CalculatorConfig(0, RoundingMode.HALF_EVEN));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InvoiceResult result = custom.process(
                new ByteArrayInputStream((HEADER + "SP1,1,2.5,0\n").getBytes(StandardCharsets.UTF_8)), out);
        assertEquals(new BigDecimal("2"), result.totalBeforeTax());
    }

    @Test
    void invalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new CalculatorConfig(-1, RoundingMode.HALF_UP));
        assertThrows(IllegalArgumentException.class, () -> new CalculatorConfig(1, RoundingMode.UNNECESSARY));
        assertThrows(NullPointerException.class, () -> new CalculatorConfig(1, null));
    }

    // ---------- AC-7 .. AC-9: value ranges ----------

    @Test
    void ac7_anyNonNegativeVatRate() throws Exception {
        assertEquals(OUT_HEADER
                + "SP1,1,100,150,150,100,250\n"
                + "SP2,1,100,0,0,100,100\n"
                + "SP3,1,100,12.345678,12.345678,100,112.345678\n"
                + "TOTAL,,,,162.3,300.0,462.3\n",
                process(HEADER + "SP1,1,100,150\nSP2,1,100,0\nSP3,1,100,12.345678\n"));
    }

    @Test
    void ac8_zeroUnitPrice() throws Exception {
        assertEquals(OUT_HEADER + "SP1,5,0,10,0,0,0\nTOTAL,,,,0.0,0.0,0.0\n", process(HEADER + "SP1,5,0,10\n"));
    }

    @Test
    void ac9_quantityMustBePositive() {
        List<ValidationError> errors = errorsOf(HEADER + "SP1,0,1,1\nSP2,-1,1,1\n");
        assertEquals(2, errors.size());
        errors.forEach(e -> assertEquals(ErrorCode.OUT_OF_RANGE, e.code()));
    }

    @Test
    void negativeUnitPrice() {
        assertEquals(ErrorCode.OUT_OF_RANGE, single(errorsOf(HEADER + "SP1,1,-1,1\n")).code());
    }

    @Test
    void tooManyDecimals() {
        List<ValidationError> errors = errorsOf(HEADER + "SP1,1.2345,1,1\nSP2,1,1.234,1\n");
        assertEquals(2, errors.size());
        errors.forEach(e -> assertEquals(ErrorCode.TOO_MANY_DECIMALS, e.code()));
    }

    @Test
    void trailingZerosDoNotCountAsDecimals() throws Exception {
        assertTrue(process(HEADER + "SP1,1.500000,2.5000,10\n").startsWith(OUT_HEADER + "SP1,1.500000,2.5000,10,"));
    }

    @Test
    void invalidNumberFormats() {
        for (String bad : List.of("1,000", "1e5", "NaN", "Infinity", "+1", ".5", "5.", "10%", "1 000")) {
            String value = bad.contains(",") ? '"' + bad + '"' : bad;
            ValidationError e = single(errorsOf(HEADER + "SP1,1,1," + value + "\n"));
            assertEquals(ErrorCode.INVALID_NUMBER, e.code(), bad);
        }
    }

    @Test
    void requiredValues() {
        List<ValidationError> errors = errorsOf(HEADER + " ,,,\n");
        assertEquals(4, errors.size());
        errors.forEach(e -> assertEquals(ErrorCode.REQUIRED_VALUE_MISSING, e.code()));
    }

    @Test
    void itemCodeTooLong() throws Exception {
        String max = "A".repeat(50);
        assertTrue(process(HEADER + max + ",1,1,1\n").contains(max));
        assertEquals(ErrorCode.VALUE_TOO_LONG, single(errorsOf(HEADER + max + "B,1,1,1\n")).code());
    }

    @Test
    void columnCountMismatch() {
        ValidationError e = single(errorsOf(HEADER + "SP1,1,1\n"));
        assertEquals(ErrorCode.COLUMN_COUNT_MISMATCH, e.code());
        assertEquals(2, e.lineNumber());
    }

    @Test
    void unterminatedQuote() {
        ValidationError e = single(errorsOf(HEADER + "SP1,1,1,1\n\"SP2,1,1,1\n"));
        assertEquals(ErrorCode.MALFORMED_CSV, e.code());
        assertEquals(3, e.lineNumber());
    }

    // ---------- AC-10, AC-11, AC-14: CSV format ----------

    @Test
    void ac10_quotedValues() throws Exception {
        assertEquals(OUT_HEADER
                + "\"SP,1\",1,1,0,0,1,1\n"
                + "\"SP \"\"2\"\"\",1,1,0,0,1,1\n"
                + "TOTAL,,,,0.0,2.0,2.0\n",
                process(HEADER + "\"SP,1\",1,1,0\n\"SP \"\"2\"\"\",1,1,0\n"));
    }

    @Test
    void quotedValueWithNewline_lineNumbersArePhysical() {
        List<ValidationError> errors = errorsOf(HEADER + "\"SP\n1\",1,1,1\nSP2,x,1,1\n");
        assertEquals(4, single(errors).lineNumber());
    }

    @Test
    void ac11_bomCrlfAndTrailingBlankLines() throws Exception {
        String input = "﻿" + HEADER.replace("\n", "\r\n") + "SP1,1,10,10\r\n\r\n\r\n";
        assertEquals(OUT_HEADER + "SP1,1,10,10,1,10,11\nTOTAL,,,,1.0,10.0,11.0\n", process(input));
    }

    @Test
    void outputHasNoBom() throws Exception {
        assertFalse(process(HEADER + "SP1,1,1,1\n").startsWith("﻿"));
    }

    @Test
    void ac14_headerCaseWhitespaceAndOrder() throws Exception {
        assertEquals("vat_rate,quantity,item_code,unit_price,vat_amount,amount_before_tax,amount_after_tax\n"
                + "10,2,SP1,5,1,10,11\n"
                + ",,TOTAL,,1.0,10.0,11.0\n",
                process(" VAT_Rate , Quantity,ITEM_CODE,unit_price\n 10 , 2 , SP1 , 5 \n"));
    }

    // ---------- AC-12: atomic output ----------

    @Test
    void ac12_existingOutputUntouchedOnError() throws Exception {
        Path input = tempDir.resolve("in.csv");
        Path output = tempDir.resolve("out.csv");
        Files.writeString(input, HEADER + "SP1,abc,1,1\n");
        Files.writeString(output, "previous content");

        assertThrows(InvoiceValidationException.class, () -> calculator.process(input, output));
        assertEquals("previous content", Files.readString(output));
    }

    @Test
    void existingOutputReplacedOnSuccess() throws Exception {
        Path input = tempDir.resolve("in.csv");
        Path output = tempDir.resolve("out.csv");
        Files.writeString(input, HEADER + "SP1,1,1,0\n");
        Files.writeString(output, "previous content");

        calculator.process(input, output);
        assertTrue(Files.readString(output).startsWith(OUT_HEADER));
    }

    @Test
    void missingInputFile() {
        assertThrows(IOException.class,
                () -> calculator.process(tempDir.resolve("nope.csv"), tempDir.resolve("out.csv")));
    }

    @Test
    void invalidUtf8IsIoError() {
        byte[] bytes = (HEADER + "SP1,1,1,1\n").getBytes(StandardCharsets.UTF_8);
        byte[] broken = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, broken, 0, bytes.length);
        broken[bytes.length] = (byte) 0xC3;
        assertThrows(IOException.class, () -> calculator.process(new ByteArrayInputStream(broken),
                new ByteArrayOutputStream()));
    }

    @Test
    void nullArguments() {
        assertThrows(NullPointerException.class, () -> calculator.process((Path) null, tempDir.resolve("x")));
        assertThrows(NullPointerException.class, () -> calculator.process(tempDir.resolve("x"), (Path) null));
        assertThrows(NullPointerException.class, () -> calculator.process((InputStream) null,
                new ByteArrayOutputStream()));
        assertThrows(NullPointerException.class, () -> calculator.process(InputStream.nullInputStream(),
                (OutputStream) null));
        assertThrows(NullPointerException.class, () -> new InvoiceCalculator(null));
    }

    @Test
    void streamsAreNotClosed() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        InputStream in = new ByteArrayInputStream((HEADER + "SP1,1,1,1\n").getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
        OutputStream out = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed.incrementAndGet();
            }
        };
        calculator.process(in, out);
        assertEquals(0, closed.get());
    }

    // ---------- AC-15 .. AC-19: invariants, row limit, duplicate codes ----------

    @Test
    void ac15_afterEqualsBeforePlusVatOnEveryLine() throws Exception {
        String output = process(HEADER + "A,1.234,99.99,7.5\nB,3,0.01,33.333\nC,999.999,12345.67,10\n");
        String[] lines = output.split("\n");
        for (int i = 1; i < lines.length - 1; i++) {
            String[] cols = lines[i].split(",");
            BigDecimal vat = new BigDecimal(cols[4]);
            BigDecimal before = new BigDecimal(cols[5]);
            BigDecimal after = new BigDecimal(cols[6]);
            assertEquals(0, after.compareTo(before.add(vat)), lines[i]);
        }
        String[] total = lines[lines.length - 1].split(",", -1);
        for (int col = 4; col <= 6; col++) {
            assertEquals(1, new BigDecimal(total[col]).scale(), lines[lines.length - 1]);
        }
    }

    @Test
    void ac16_exactlyMaxRowsWithBlankLines() throws Exception {
        String input = rows(InvoiceCalculator.MAX_DATA_ROWS).replace("SP5000,", "\n\nSP5000,");
        Path in = tempDir.resolve("in.csv");
        Path out = tempDir.resolve("out.csv");
        Files.writeString(in, input);

        long start = System.nanoTime();
        InvoiceResult result = calculator.process(in, out);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(10_000, result.lineCount());
        assertEquals(10_002, Files.readAllLines(out).size());
        assertTrue(millis < 1000, "NFR-3: took " + millis + " ms");
    }

    @Test
    void ac17_tooManyRows() {
        ValidationError e = single(errorsOf(rows(InvoiceCalculator.MAX_DATA_ROWS + 1)));
        assertEquals(ErrorCode.TOO_MANY_ROWS, e.code());
        assertEquals(10_002, e.lineNumber());
    }

    @Test
    void ac18_stopsReadingAfterLimit() {
        String input = rows(50_000).replace("SP19,1.5,", "SP19,abc,");
        AtomicInteger bytesRead = new AtomicInteger();
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                int n = super.read(b, off, len);
                if (n > 0) {
                    bytesRead.addAndGet(n);
                }
                return n;
            }
        };
        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> calculator.process(in, new ByteArrayOutputStream()));

        List<ValidationError> errors = e.getErrors();
        assertEquals(2, errors.size(), errors.toString());
        assertEquals(ErrorCode.INVALID_NUMBER, errors.get(0).code());
        assertEquals(20, errors.get(0).lineNumber());
        assertEquals(ErrorCode.TOO_MANY_ROWS, errors.get(1).code());
        assertTrue(bytesRead.get() < bytes.length / 2, "read " + bytesRead.get() + " of " + bytes.length);
    }

    @Test
    void ac19_duplicateItemCodesKeptSeparate() throws Exception {
        assertEquals(OUT_HEADER
                + "SP001,1,10,0,0,10,10\n"
                + "SP001,2,10,0,0,20,20\n"
                + "TOTAL,,,,0.0,30.0,30.0\n", process(HEADER + "SP001,1,10,0\nSP001,2,10,0\n"));
    }

    @Test
    void exceptionMessageListsErrors() {
        StringBuilder input = new StringBuilder(HEADER);
        for (int i = 0; i < 12; i++) {
            input.append("SP,x,1,1\n");
        }
        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> calculator.process(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayOutputStream()));
        assertTrue(e.getMessage().startsWith("Invalid invoice input (12 error(s)):"));
        assertTrue(e.getMessage().endsWith("... and 2 more"));
    }
}
