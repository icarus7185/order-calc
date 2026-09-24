# order-calc

A small Java library that reads an invoice from a CSV file, validates it, calculates VAT for every line and the invoice totals, and writes the result to a new CSV file.

- **One file = one invoice.** Each data row is one invoice line.
- **Exact arithmetic.** All amounts are calculated with `BigDecimal`. Line values are never rounded; only the three invoice totals are rounded (1 decimal place, `HALF_UP`).
- **All-or-nothing.** If the input is invalid, every error is reported at once and no output is written.
- **No runtime dependencies.** The jar only needs Java 17 or newer.

The full requirement specification is in [docs/requirement-spec.en.md](docs/requirement-spec.en.md) (English) and [docs/requirement-spec.md](docs/requirement-spec.md) (Vietnamese, the original).

---

## Contents

- [Building and packaging](#building-and-packaging)
- [Usage](#usage)
- [Input CSV format](#input-csv-format)
- [Output CSV format](#output-csv-format)
- [Error handling](#error-handling)
- [Project layout](#project-layout)

---

## Building and packaging

### Requirements

| Tool  | Version |
|-------|---------|
| JDK   | 17 or newer |
| Maven | 3.9 or newer |

### Build the jar

```bash
mvn clean package
```

This compiles the code, runs the tests and creates:

```
target/order-calc-1.0.0.jar
```

The jar contains only the library classes. There are no runtime dependencies to ship with it.

### Build with coverage check

```bash
mvn clean verify
```

`verify` also runs JaCoCo and fails the build if line coverage drops below 80%. The HTML report is written to `target/site/jacoco/index.html`.

### Install into your local Maven repository

```bash
mvn clean install
```

Other Maven projects on the same machine can then depend on it:

```xml
<dependency>
    <groupId>com.ordercalc</groupId>
    <artifactId>order-calc</artifactId>
    <version>1.0.0</version>
</dependency>
```

For projects that don't use Maven, add `target/order-calc-1.0.0.jar` to the classpath.

---

## Usage

### Process a file

```java
import com.ordercalc.InvoiceCalculator;
import com.ordercalc.InvoiceResult;
import com.ordercalc.InvoiceValidationException;

import java.io.IOException;
import java.nio.file.Path;

InvoiceCalculator calculator = new InvoiceCalculator();

try {
    InvoiceResult result = calculator.process(
            Path.of("docs/sample-input.csv"),
            Path.of("result.csv"));

    System.out.println("Lines:          " + result.lineCount());
    System.out.println("Before tax:     " + result.totalBeforeTax());
    System.out.println("VAT:            " + result.totalVat());
    System.out.println("After tax:      " + result.totalAfterTax());
} catch (InvoiceValidationException e) {
    // The input is invalid; nothing was written.
    e.getErrors().forEach(error -> System.err.println(error.message()));
} catch (IOException e) {
    // The input could not be read or the output could not be written.
    e.printStackTrace();
}
```

The output is first written to a temporary file in the same directory and then renamed. An existing output file is replaced only when processing succeeds.

### Process streams

Use the stream variant when the data doesn't come from or go to the file system (for example an upload or an HTTP response):

```java
try (InputStream in = ...; OutputStream out = ...) {
    InvoiceResult result = calculator.process(in, out);
}
```

The library does not close the streams. If the input is invalid, no bytes are written to `out`.

### Configuration

The default rounding for totals is 1 decimal place, `HALF_UP`. Both can be changed:

```java
import com.ordercalc.CalculatorConfig;
import java.math.RoundingMode;

InvoiceCalculator calculator = new InvoiceCalculator(new CalculatorConfig(0, RoundingMode.HALF_EVEN));
```

| Setting        | Default   | Meaning |
|----------------|-----------|---------|
| `totalScale`   | `1`       | Number of decimal places of the three totals (must be `>= 0`) |
| `roundingMode` | `HALF_UP` | Rounding mode of the totals (`UNNECESSARY` is not allowed) |

The delimiter (`,`) and the 10,000-row limit are fixed business rules and cannot be configured.

### Thread safety

`InvoiceCalculator` keeps no state between calls. One instance can be shared by any number of threads.

---

## Input CSV format

Sample file: [docs/sample-input.csv](docs/sample-input.csv)

```csv
item_code,quantity,unit_price,vat_rate
SP001,2,100000,10
SP002,3,15500,8
SP003,1.5,33333.33,8
SP004,10,2500,0
"SP005, gift box",1,75000.5,5
```

### File rules

| Rule | Value |
|------|-------|
| Format | CSV, [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180) |
| Encoding | UTF-8, with or without BOM |
| Delimiter | Comma `,` |
| Line endings | `\n` or `\r\n` |
| Quoting | Values containing `,`, `"` or a line break must be enclosed in `"`; a `"` inside a quoted value is written as `""` |
| Header | Required, first line of the file |
| Blank lines | Ignored, including trailing blank lines |
| Data rows | At least 1, at most **10,000** (header and blank lines not counted) |

### Header

The header must contain exactly these four columns:

```
item_code,quantity,unit_price,vat_rate
```

- Column names are case-insensitive and surrounding spaces are ignored (`Item_Code`, ` QUANTITY ` are accepted).
- Columns may appear in any order. The output keeps the same order.
- Missing, duplicate or unknown columns are errors.

### Columns

| Column | Type | Required | Rules |
|--------|------|----------|-------|
| `item_code`  | Text    | Yes | Not empty; at most 50 characters. The same code may appear on several rows. |
| `quantity`   | Decimal | Yes | Greater than `0`; at most 3 decimal places |
| `unit_price` | Decimal | Yes | `0` or greater; at most 2 decimal places. The price **excludes** VAT. |
| `vat_rate`   | Decimal | Yes | `0` or greater, as a percentage (`10` means 10%). No upper limit and no limit on decimal places. |

### Number format

- Use a dot `.` as the decimal separator: `1.5`, `33333.33`.
- Spaces around a value are allowed and trimmed.
- The following are **rejected**: thousand separators (`1,000`), currency or percent signs (`10%`), a leading `+`, a missing integer or fraction part (`.5`, `5.`), scientific notation (`1e5`), `NaN` and `Infinity`.
- Trailing zeros don't count as decimal places: `2.5000` is a valid unit price.

---

## Output CSV format

Sample file: [docs/sample-output.csv](docs/sample-output.csv), generated from the sample input above.

```csv
item_code,quantity,unit_price,vat_rate,vat_amount,amount_before_tax,amount_after_tax
SP001,2,100000,10,20000,200000,220000
SP002,3,15500,8,3720,46500,50220
SP003,1.5,33333.33,8,3999.9996,49999.995,53999.9946
SP004,10,2500,0,0,25000,25000
"SP005, gift box",1,75000.5,5,3750.025,75000.5,78750.525
TOTAL,,,,31470.0,396500.5,427970.5
```

### File rules

| Rule | Value |
|------|-------|
| Encoding | UTF-8, **without** BOM |
| Delimiter | Comma `,` |
| Line endings | `\n` |
| Quoting | Only when a value contains `,`, `"` or a line break |

### Columns

The input columns come first, in the same order as the input header, followed by three calculated columns:

| Column | Formula |
|--------|---------|
| `vat_amount`        | `amount_before_tax × vat_rate / 100` |
| `amount_before_tax` | `quantity × unit_price` |
| `amount_after_tax`  | `amount_before_tax + vat_amount` |

- Input values are copied as they were written, with surrounding spaces removed.
- Header names are written in lowercase, as listed above.
- Data rows keep the order of the input file.

### Line values: exact, not rounded

Line values are exact results. Trailing zeros are removed and no thousand separators are used, for example `20000`, `3999.9996`, `0.04`. On every line, `amount_after_tax = amount_before_tax + vat_amount` exactly.

### TOTAL row: rounded

The last row of the file holds the invoice totals:

- `item_code` is `TOTAL`; `quantity`, `unit_price` and `vat_rate` are empty.
- Each total is the sum of the **exact** line values, rounded afterwards to 1 decimal place (`HALF_UP`).
- The three totals are rounded **independently**:

  ```
  total_vat        = round(Σ vat_amount)
  total_before_tax = round(Σ amount_before_tax)
  total_after_tax  = round(Σ amount_after_tax)
  ```

  In the sample, `Σ amount_before_tax = 396500.495` becomes `396500.5` and `Σ vat_amount = 31470.0246` becomes `31470.0`.

Because each total is rounded on its own, `total_after_tax` can differ by `0.1` from `total_before_tax + total_vat`. This is intended. For example, one line with `quantity=1, unit_price=0.05, vat_rate=100` gives the totals `0.1`, `0.1` and `0.1`.

---

## Error handling

| Exception | When |
|-----------|------|
| `InvoiceValidationException` | The input is invalid. `getErrors()` returns every error found. |
| `IOException` | A file cannot be read or written, or the input is not valid UTF-8. |
| `NullPointerException` | An argument is `null`. |

Header errors stop validation straight away. Data errors are collected for the whole file and reported together, so all of them can be fixed in one pass. Reading stops at the 10,001st data row, so very large files aren't read to the end.

Each `ValidationError` has a `lineNumber` (physical line in the file; the header is line 1), a `column` (or `null`), a `code`, and a readable `message`:

```
Line 3, column 'quantity': value '-2' must be greater than 0 (OUT_OF_RANGE)
```

### Error codes

| Code | Level | Meaning |
|------|-------|---------|
| `EMPTY_FILE`             | File   | The file is empty; there is no header |
| `NO_DATA_ROWS`           | File   | The file has a header but no data rows |
| `TOO_MANY_ROWS`          | File   | More than 10,000 data rows |
| `MALFORMED_CSV`          | File   | A quoted value is not closed before the end of the file |
| `MISSING_COLUMN`         | Header | A required column is missing |
| `DUPLICATE_COLUMN`       | Header | A column appears more than once |
| `UNKNOWN_COLUMN`         | Header | A column is not one of the four allowed columns |
| `COLUMN_COUNT_MISMATCH`  | Row    | The row has a different number of values than the header |
| `REQUIRED_VALUE_MISSING` | Cell   | A value is empty |
| `INVALID_NUMBER`         | Cell   | A value is not a valid number (see [Number format](#number-format)) |
| `OUT_OF_RANGE`           | Cell   | `quantity <= 0`, `unit_price < 0` or `vat_rate < 0` |
| `TOO_MANY_DECIMALS`      | Cell   | `quantity` has more than 3 or `unit_price` more than 2 decimal places |
| `VALUE_TOO_LONG`         | Cell   | `item_code` is longer than 50 characters |

---

## Project layout

```
order-calc/
├── pom.xml
├── docs/
│   ├── business-requirement.md    original business requirement (Vietnamese)
│   ├── business-requirement.en.md English translation
│   ├── requirement-spec.md        detailed specification (Vietnamese)
│   ├── requirement-spec.en.md     English translation
│   ├── sample-input.csv           sample input
│   └── sample-output.csv          output generated from sample-input.csv
└── src/
    ├── main/java/com/ordercalc/
    │   ├── InvoiceCalculator.java         entry point
    │   ├── CalculatorConfig.java          rounding settings
    │   ├── InvoiceResult.java             rounded totals
    │   ├── InvoiceValidationException.java
    │   ├── ValidationError.java
    │   ├── ErrorCode.java
    │   └── internal/                      CSV reading/writing and validation (not public API)
    └── test/java/com/ordercalc/
        └── InvoiceCalculatorTest.java     one test per acceptance criterion, plus edge cases
```

Classes in `com.ordercalc.internal` are implementation details and may change without notice.
