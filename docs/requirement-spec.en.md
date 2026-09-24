# Requirement Specification — Order Calc (invoice calculation library for CSV files)

> English translation of [requirement-spec.md](requirement-spec.md). The Vietnamese version is the original; if the two differ, the Vietnamese version applies.

| Item | Value |
|---|---|
| Source | [business-requirement.en.md](business-requirement.en.md) |
| Version | 1.1 |
| Date | 2026-09-24 |
| Status | Final — all open questions have been confirmed (section 9) |

> Convention: every decision with a `Qx` code has been confirmed by the business; see section 9. Items marked **[DESIGN]** are technical decisions made by the development team and do not change business behaviour.

### Change history
| Version | Change |
|---|---|
| 0.1 | First draft |
| 0.2 | Confirmed: unit price excludes VAT; `,` delimiter; English headers; VAT % accepts any value; rounding to 1 decimal place on the invoice totals only |
| 1.0 | Confirmed: limit of 10,000 data rows (new error code `TOO_MANY_ROWS`); `total_after_tax = round(Σ amount_after_tax)`; all remaining assumptions accepted. Fixed the wrong VAT total in the example in section 5.4 of version 0.2 |
| 1.1 | Added error code `MALFORMED_CSV` (found during implementation) |

---

## 1. Goals and scope

### 1.1 Goal
Provide a Java library, packaged as a `*.jar` file, that takes a CSV file representing **one invoice**, validates it, calculates value-added tax (VAT) for each row and for the invoice as a whole, and writes a result CSV file.

### 1.2 In scope
- Read 1 input CSV file (1 file = 1 invoice).
- Validate the input data; stop and return errors when it is invalid.
- Calculate for each row: amount before tax, VAT amount, amount after tax.
- Calculate the totals of these 3 values for the whole invoice.
- Write an output CSV file with the input columns + 3 new columns + 1 total row at the end.
- A public Java API for other applications to call.

### 1.3 Out of scope
- User interface, CLI, REST API (may be added later).
- Multiple invoices in one file, or merging several files.
- Looking up item codes / unit prices from an external data source.
- Merging rows with the same item code (Q6).
- Discounts, shipping fees, multiple currencies.
- Storing data in a database.

---

## 2. Glossary

| Term | Column name (CSV) | Meaning |
|---|---|---|
| Item code | `item_code` | Identifier of the item |
| Quantity | `quantity` | Quantity purchased |
| Unit price | `unit_price` | Price of 1 unit, **excluding VAT** (Q3) |
| VAT % | `vat_rate` | Tax rate as a percentage, e.g. `10` = 10% |
| Amount before tax | `amount_before_tax` | Item amount excluding VAT |
| VAT amount | `vat_amount` | VAT amount |
| Amount after tax | `amount_after_tax` | Item amount including VAT |

---

## 3. Input specification

### 3.1 File format
| Property | Rule |
|---|---|
| Format | CSV according to RFC 4180 (values enclosed in `"` are supported) |
| Encoding | UTF-8, with or without BOM |
| Delimiter | Comma `,`, fixed, not configurable (Q1) |
| Line endings | `\n` or `\r\n` |
| Header | Required, must be the first line |
| Blank lines | Completely blank lines are skipped (including blank lines at the end of the file) |

### 3.2 Header
- The header must contain exactly 4 columns: `item_code,quantity,unit_price,vat_rate` (Q2).
- Column name matching: case-insensitive, leading and trailing whitespace removed.
- Column order: determined by the names in the header, not by position. The output keeps the column order of the input.
- Missing, duplicate or unknown columns are not allowed.

### 3.3 Data rules per column

| Column | Type | Required | Constraints |
|---|---|---|---|
| `item_code` | Text | Yes | Not empty after trimming; at most 50 characters [DESIGN]. The same code may appear on several rows; rows are not merged (Q6) |
| `quantity` | Decimal | Yes | `> 0`; at most 3 decimal places (Q4) |
| `unit_price` | Decimal | Yes | `>= 0`; at most 2 decimal places [DESIGN] |
| `vat_rate` | Decimal | Yes | Any number `>= 0` (Q5, Q16): no upper limit, no limit on decimal places; negative numbers are rejected |

Number format:
- The decimal separator is a dot `.`; thousand separators, currency signs and the `%` sign are **not** accepted.
- Leading and trailing whitespace is allowed (it is trimmed).
- Scientific notation (`1e5`), `NaN` and `Infinity` are not accepted.

### 3.4 File-level constraints
- There must be at least 1 data row after the header; a file with only a header is an error (Q7).
- At most **10,000 data rows** (header and blank lines not counted) (Q15). Exactly 10,000 rows is valid; the 10,001st row causes a `TOO_MANY_ROWS` error.
- Every data row must have exactly as many columns as the header.

---

## 4. Processing specification

### 4.1 Processing flow
```
1. Open the input file  ──(IO error)───────────► return error, stop
2. Read & validate the header ──(invalid)──────► return error, stop
3. Read & validate each data row
      ├─ reaching data row 10,001 ─────────────► add TOO_MANY_ROWS error, stop reading
      └─ collect ALL errors of the rows read
   Any errors? ────────────────────────────────► return the error list, stop, do NOT write output
4. Calculate each row — exact values, no rounding (section 4.2)
5. Calculate the invoice totals, then round to 1 decimal place (section 4.3)
6. Write the output file (section 5)
```

- **Validation strategy**: collect all data errors in the file and return them at once, rather than stopping at the first error (Q8). Header / IO errors stop immediately because reading cannot continue.
- **Row limit exceeded**: stop reading as soon as data row 10,001 is reached; very large files are not read to the end. The `TOO_MANY_ROWS` error is returned together with the errors already collected from the previous 10,000 rows.
- **Atomicity**: when there are errors, no output file is created and an existing output file is not overwritten. Write to a temporary file and rename it on success [DESIGN].

### 4.2 Row formulas — no rounding
All calculations use `java.math.BigDecimal` with **exact** arithmetic (no `double`/`float`, no `MathContext` that limits precision).

```
amount_before_tax = quantity × unit_price
vat_amount        = amount_before_tax × vat_rate / 100
amount_after_tax  = amount_before_tax + vat_amount
```

- Row values are **not rounded** (Q10). All 3 calculations produce finite results (dividing by 100 is always exact), so no precision is lost.
- Every row always satisfies `amount_after_tax = amount_before_tax + vat_amount`.

### 4.3 Invoice totals — rounded to 1 decimal place
Each total is calculated **independently** by adding up the exact, unrounded values of the rows, and only then rounding (Q9, Q10, Q17):

```
total_before_tax = round( Σ amount_before_tax )
total_vat        = round( Σ vat_amount )
total_after_tax  = round( Σ amount_after_tax )
```

- `round(x)`: round to **1 decimal place**, `HALF_UP` mode (Q9b).
- Because the 3 totals are rounded independently, the TOTAL row **may differ by 0.1** from the equation `total_after_tax = total_before_tax + total_vat` (see AC-13). This is correct behaviour according to Q17, not a defect.

---

## 5. Output specification

### 5.1 File format
- CSV according to RFC 4180, UTF-8 **without BOM** (Q11), `,` delimiter, `\n` line endings.
- Values are enclosed in `"` only when needed (when they contain `,`, `"` or a line break).
- The output file path is passed in by the caller (Q14).

### 5.2 Columns
The input columns (same column order, original values trimmed), followed by 3 new columns in the order given in the business requirement:

| # | Column | Source |
|---|---|---|
| 1–4 | `item_code`, `quantity`, `unit_price`, `vat_rate` | From input |
| 5 | `vat_amount` | Calculated |
| 6 | `amount_before_tax` | Calculated |
| 7 | `amount_after_tax` | Calculated |

Number formatting:
- **Data rows**: exact value in plain form (`stripTrailingZeros().toPlainString()`), trailing zeros after the decimal point removed, no thousand separators. For example `20000`, `3999.9996`, `0.04`.
- **Total row**: always exactly 1 decimal place. For example `296500.0`, `27720.0`.
- Data rows keep the order of the input.

### 5.3 Total row
- It is the last row of the file.
- `item_code` = `TOTAL`; `quantity`, `unit_price`, `vat_rate` are empty; the quantity is not totalled (Q12).
- The 3 new columns contain `total_vat`, `total_before_tax`, `total_after_tax`.

### 5.4 Example

Input:
```csv
item_code,quantity,unit_price,vat_rate
SP001,2,100000,10
SP002,3,15500,8
SP003,1.5,33333.33,8
```

Calculation per row:

| Row | before | VAT | after |
|---|---|---|---|
| SP001 | 2 × 100000 = 200000 | 200000 × 10% = 20000 | 220000 |
| SP002 | 3 × 15500 = 46500 | 46500 × 8% = 3720 | 50220 |
| SP003 | 1.5 × 33333.33 = 49999.995 | 49999.995 × 8% = 3999.9996 | 53999.9946 |
| **Exact Σ** | 296499.995 | 27719.9996 | 324219.9946 |
| **TOTAL (rounded)** | `296500.0` | `27720.0` | `324220.0` |

Output:
```csv
item_code,quantity,unit_price,vat_rate,vat_amount,amount_before_tax,amount_after_tax
SP001,2,100000,10,20000,200000,220000
SP002,3,15500,8,3720,46500,50220
SP003,1.5,33333.33,8,3999.9996,49999.995,53999.9946
TOTAL,,,,27720.0,296500.0,324220.0
```

---

## 6. Public API

Root package: `com.ordercalc` (Q13).

```java
public final class InvoiceCalculator {
    /** Maximum number of data rows in one input file. */
    public static final int MAX_DATA_ROWS = 10_000;

    public InvoiceCalculator();                       // default configuration
    public InvoiceCalculator(CalculatorConfig config);

    /** Reads the input, validates, calculates and writes the output. */
    public InvoiceResult process(Path input, Path output)
            throws InvoiceValidationException, IOException;

    /** Stream variant for integration without the file system. */
    public InvoiceResult process(InputStream input, OutputStream output)
            throws InvoiceValidationException, IOException;
}

/** The rounded totals, same as the TOTAL row in the output file. */
public record InvoiceResult(
        int lineCount,
        BigDecimal totalBeforeTax,
        BigDecimal totalVat,
        BigDecimal totalAfterTax) {}

public record CalculatorConfig(
        int totalScale,               // default 1
        RoundingMode roundingMode) {} // default HALF_UP

public class InvoiceValidationException extends Exception {
    public List<ValidationError> getErrors();
}

public record ValidationError(
        int lineNumber,     // physical line number in the file, header = 1
        String column,      // null for file/header/row-level errors
        ErrorCode code,
        String message) {}
```

- Data errors → `InvoiceValidationException` (contains the list of errors).
- File read/write errors → `IOException`.
- `null` arguments → `NullPointerException` (fail fast).
- `InvoiceCalculator` keeps no state between calls → thread-safe.
- The delimiter and the 10,000-row limit are fixed business rules and are not part of `CalculatorConfig`.

### 6.1 Error codes

| Code | Level | Condition |
|---|---|---|
| `EMPTY_FILE` | File | The file is empty, there is no header |
| `NO_DATA_ROWS` | File | Only a header, no data rows |
| `TOO_MANY_ROWS` | File | More than 10,000 data rows; `lineNumber` is the physical line of data row 10,001 |
| `MALFORMED_CSV` | File | An opening `"` is not closed before the end of the file; `lineNumber` is the line where the faulty record starts [DESIGN] |
| `MISSING_COLUMN` | Header | A required column is missing |
| `DUPLICATE_COLUMN` | Header | A column appears more than once |
| `UNKNOWN_COLUMN` | Header | A column is not in the list |
| `COLUMN_COUNT_MISMATCH` | Row | The row's number of columns differs from the header |
| `REQUIRED_VALUE_MISSING` | Cell | A required value is empty |
| `INVALID_NUMBER` | Cell | The value cannot be parsed as a number according to section 3.3 |
| `OUT_OF_RANGE` | Cell | `quantity <= 0`, `unit_price < 0` or `vat_rate < 0` |
| `TOO_MANY_DECIMALS` | Cell | `quantity` or `unit_price` exceeds the allowed number of decimal places (does not apply to `vat_rate`) |
| `VALUE_TOO_LONG` | Cell | `item_code` exceeds 50 characters |

Example message: `Line 3, column 'quantity': value '-2' must be greater than 0 (OUT_OF_RANGE)`.

---

## 7. Non-functional requirements

| Code | Requirement |
|---|---|
| NFR-1 | Java 17+ (Q13); built with Maven, producing 1 `.jar` file |
| NFR-2 | Minimise external dependencies; if a CSV library is used (e.g. Apache Commons CSV), also provide a fat jar or document the dependency |
| NFR-3 | Process a 10,000-row file (the maximum) in under 1 second, with heap usage not exceeding 64 MB [DESIGN] |
| NFR-4 | Absolute monetary precision: use only `BigDecimal` with exact arithmetic; round only when calculating the totals |
| NFR-5 | Thread-safe, no mutable static state |
| NFR-6 | Test coverage ≥ 80% for the validation and calculation logic |
| NFR-7 | Do not log invoice data to stdout; if logging is needed, use SLF4J |

---

## 8. Acceptance criteria

| # | Scenario | Expected result |
|---|---|---|
| AC-1 | Valid file as in the example in section 5.4 | Output identical byte-by-byte to the example |
| AC-2 | Header missing the `vat_rate` column | `MISSING_COLUMN`, no output file created |
| AC-3 | File with only a header | `NO_DATA_ROWS` |
| AC-4 | Line 3 has `quantity=abc`, line 5 has `vat_rate=-1` | **Both** errors returned (`INVALID_NUMBER` on line 3, `OUT_OF_RANGE` on line 5) |
| AC-5 | 1 row: `quantity=3, unit_price=3333, vat_rate=8` | Row: before `9999`, VAT `799.92`, after `10798.92`. TOTAL: VAT `799.9`, before `9999.0`, after `10798.9` |
| AC-6 | 2 rows, each `quantity=1, unit_price=0.5, vat_rate=8` | VAT per row `0.04` (not rounded). TOTAL VAT = round(0.08) = `0.1`. Rounding each row would give `0.0`, which is **wrong** |
| AC-7 | `vat_rate` = `150`, `0`, `12.345678` | All valid and calculated with the correct formula |
| AC-8 | `unit_price=0` | Valid, calculated columns = `0`, TOTAL = `0.0` |
| AC-9 | `quantity=0` or negative | `OUT_OF_RANGE` |
| AC-10 | `item_code` contains a comma and is enclosed in `"` | Read correctly, still enclosed in `"` in the output |
| AC-11 | Input file has a UTF-8 BOM and ends with blank lines | Processed normally |
| AC-12 | Output file already exists, input is invalid | The existing output file is not changed |
| AC-13 | 1 row: `quantity=1, unit_price=0.05, vat_rate=100` | Row: before `0.05`, VAT `0.05`, after `0.1`. TOTAL: before `0.1`, VAT `0.1`, after `0.1` (= round(0.1), **not** equal to before + VAT, as per Q17) |
| AC-14 | Header in upper case / with whitespace, columns reordered | Accepted, output keeps the input column order |
| AC-15 | Any valid file | Every data row satisfies `after = before + vat`; the TOTAL row has exactly 1 decimal place |
| AC-16 | Exactly 10,000 valid data rows (with blank lines in between) | Processed successfully, output has 10,000 data rows + TOTAL |
| AC-17 | 10,001 data rows | `TOO_MANY_ROWS`, no output file created |
| AC-18 | 50,000 data rows, line 20 has `quantity=abc` | Returns `INVALID_NUMBER` on line 20 and `TOO_MANY_ROWS`; the library does not read past data row 10,001 |
| AC-19 | 2 rows with the same `item_code=SP001` | Valid, kept as 2 separate rows in the output |

---

## 9. Business decisions (confirmed)

| Code | Question | Decision |
|---|---|---|
| Q1 | Delimiter | Comma `,` |
| Q2 | Header column names | English: `item_code,quantity,unit_price,vat_rate` |
| Q3 | Does the unit price include VAT? | No, it excludes VAT |
| Q4 | Can the quantity be fractional? | Yes, at most 3 decimal places |
| Q5 | Range of `%vat` | Any value accepted |
| Q6 | Duplicate item codes in 1 invoice? | Allowed, rows are not merged |
| Q7 | File with only a header? | Error `NO_DATA_ROWS` |
| Q8 | Stop at the first error or return all? | Return all errors |
| Q9 | How many decimal places when rounding? | 1 decimal place |
| Q9b | Rounding mode | `HALF_UP` |
| Q10 | Round per row or on the total? | Round only on the invoice totals |
| Q11 | Does the output have a BOM? | No BOM |
| Q12 | Total row label, total quantity? | `TOTAL`, quantity is not totalled |
| Q13 | Package, Java version | `com.ordercalc`, Java 17 |
| Q14 | Output file name | Passed in by the caller |
| Q15 | File size limit | At most 10,000 data rows |
| Q16 | Does `%vat` accept negative numbers? | No; any number `>= 0` is accepted |
| Q17 | How is the total after tax calculated? | `round(Σ amount_after_tax)`, independently of the other 2 totals |
