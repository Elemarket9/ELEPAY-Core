import { test } from "node:test";
import assert from "node:assert/strict";
import { parseAllowedPrinter, encodeEscPos, processPrintJob, signedHeaders } from "../scripts/kitchen-printer-agent.mjs";
test("Only specifically allowed private-network printers are reachable", () => {
  assert.deepEqual(parseAllowedPrinter("tcp://192.168.1.50:9100", ["192.168.1.50"]),
    { host: "192.168.1.50", port: 9100 });
  assert.throws(() => parseAllowedPrinter("tcp://8.8.8.8:9100", ["8.8.8.8"]));
  assert.throws(() => parseAllowedPrinter("tcp://192.168.1.55:9100", ["192.168.1.50"]));
  assert.throws(() => parseAllowedPrinter("usb://unsupported", []));
});
test("ESC POS payload initializes, contains ticket and requests cut", () => {
  const bytes = encodeEscPos("KITCHEN\n2 x Waakye");
  assert.equal(bytes[0], 0x1b);
  assert.match(bytes.toString("utf8"), /Waakye/);
  assert.deepEqual([...bytes.subarray(-3)], [0x1d,0x56,0x00]);
});
test("Failed delivery reports error and never marks print complete", async () => {
  const cfg = { merchantId:"merchant", deviceId:"device", allowedHosts:[] };
  const report = await processPrintJob({merchantId:"merchant", printerAddress:"usb://printer", content:"Food"}, cfg,
    async () => { throw new Error("unsupported"); });
  assert.equal(report.printed, false);
  assert.equal(report.error, "unsupported");
});
test("Printer queue signing uses an explicit nonce and HMAC", () => {
  const headers = signedHeaders("/v1/restaurant/kds/print-jobs/claim", "x=y", "POST", "", "key", "secret");
  assert.equal(headers["X-Elepay-Signature-Version"], "2");
  assert.equal(headers["X-Elepay-Signature"].length, 64);
});
