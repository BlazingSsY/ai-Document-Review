import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";

const inputPath = path.resolve("prompts/QTP检查单-全部规则-PDDS.xlsx");
const outputDir = path.resolve(".codex_tmp/qtp_workbook");
await fs.mkdir(outputDir, { recursive: true });

const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(inputPath));
const summary = await workbook.inspect({
  kind: "workbook,sheet,table,definedName",
  maxChars: 12000,
  tableMaxRows: 10,
  tableMaxCols: 12,
  tableMaxCellChars: 180,
});
console.log("===SUMMARY===");
console.log(summary.ndjson);

const sheets = workbook.worksheets.items;
const extracted = [];
for (const sheet of sheets) {
  const used = sheet.getUsedRange();
  const values = used ? used.values : [];
  extracted.push({ name: sheet.name, values });
}
await fs.writeFile(
  path.join(outputDir, "qtp-values.json"),
  JSON.stringify(extracted, null, 2),
  "utf8",
);

for (const sheet of sheets) {
  const rendered = await workbook.render({
    sheetName: sheet.name,
    range: "A1:F45",
    scale: 0.8,
    format: "png",
  });
  const safeName = sheet.name.replace(/[\\/:*?"<>|]/g, "_");
  await fs.writeFile(
    path.join(outputDir, `${safeName}.png`),
    new Uint8Array(await rendered.arrayBuffer()),
  );
}
console.log("===EXTRACTED===");
console.log(JSON.stringify(extracted));
