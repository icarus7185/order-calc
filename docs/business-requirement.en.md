> English translation of [business-requirement.md](business-requirement.md). The Vietnamese version is the original.

Build a Java library that can be packaged as a *.jar file.
1. Input: a CSV file with a header. The columns are: item code, quantity, unit price, VAT %. One input file corresponds to one invoice.
2. Output: a CSV file with a header.
   - In addition to the columns from the input, add the following columns: VAT amount, amount before tax, amount after tax.
   - Add one row at the end with the totals of the 3 new columns.
3. Main processing of the function:
   - Validate the input. Stop processing and return an error when necessary.
   - Calculate the VAT amount, amount before tax and amount after tax of each row.
   - Calculate the totals of the 3 columns above for this invoice, which is also this file. Save the totals at the end of the file.
