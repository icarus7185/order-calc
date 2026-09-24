package com.ordercalc.internal;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads RFC 4180 CSV with a {@code ,} delimiter. Supports values enclosed in {@code "} (including line
 * breaks and {@code ""} inside), {@code \n} or {@code \r\n} line endings, and skips a leading UTF-8 BOM.
 */
public final class CsvReader {

    private static final char DELIMITER = ',';
    private static final char QUOTE = '"';
    private static final char BOM = '﻿';

    private final Reader in;
    private boolean started;
    private int line = 1;
    /** Character read ahead but not yet consumed; {@code NO_PUSH_BACK} if none. */
    private int pushBack = NO_PUSH_BACK;
    private static final int NO_PUSH_BACK = -2;

    public CsvReader(Reader in) {
        this.in = in;
    }

    /** A CSV record. {@code lineNumber} is the physical line where the record starts. */
    public record CsvRecord(int lineNumber, List<String> fields, boolean blank) {
    }

    /** Thrown when an opening {@code "} is not closed before the end of the file. */
    public static final class CsvFormatException extends Exception {
        private final int lineNumber;

        CsvFormatException(int lineNumber, String message) {
            super(message);
            this.lineNumber = lineNumber;
        }

        public int lineNumber() {
            return lineNumber;
        }
    }

    /** Reads the next record, or returns {@code null} at the end of the file. */
    public CsvRecord next() throws IOException, CsvFormatException {
        int c = read();
        if (c == -1) {
            return null;
        }
        int startLine = line;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldQuoted = false;
        boolean anyQuoted = false;

        while (true) {
            if (inQuotes) {
                if (c == -1) {
                    throw new CsvFormatException(startLine, "unterminated quoted field");
                }
                if (c == QUOTE) {
                    int n = read();
                    if (n == QUOTE) {
                        field.append(QUOTE);
                    } else {
                        inQuotes = false;
                        c = n;
                        continue;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append((char) c);
                }
            } else if (c == -1 || c == '\n' || c == '\r') {
                if (c == '\r') {
                    int n = read();
                    if (n != '\n' && n != -1) {
                        // A lone \r is also treated as a line break.
                        pushBack = n;
                    }
                }
                if (c != -1) {
                    line++;
                }
                fields.add(field.toString());
                boolean blank = !anyQuoted && fields.size() == 1 && fields.get(0).isBlank();
                return new CsvRecord(startLine, fields, blank);
            } else if (c == DELIMITER) {
                fields.add(field.toString());
                field.setLength(0);
                fieldQuoted = false;
            } else if (c == QUOTE && field.length() == 0 && !fieldQuoted) {
                inQuotes = true;
                fieldQuoted = true;
                anyQuoted = true;
            } else {
                field.append((char) c);
            }
            c = read();
        }
    }

    private int read() throws IOException {
        if (pushBack != NO_PUSH_BACK) {
            int c = pushBack;
            pushBack = NO_PUSH_BACK;
            return c;
        }
        int c = in.read();
        if (!started) {
            started = true;
            if (c == BOM) {
                c = in.read();
            }
        }
        return c;
    }
}
