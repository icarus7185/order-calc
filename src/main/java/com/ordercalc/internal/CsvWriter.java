package com.ordercalc.internal;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** Writes RFC 4180 CSV with a {@code ,} delimiter and {@code \n} line endings. Quotes values only when needed. */
public final class CsvWriter {

    private final Writer out;

    public CsvWriter(Writer out) {
        this.out = out;
    }

    public void writeRow(List<String> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(values.get(i)));
        }
        out.write('\n');
    }

    static String escape(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
