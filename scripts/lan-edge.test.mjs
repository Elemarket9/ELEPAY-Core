import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { LanOrderJournal, RelayError } from "../lan-edge/relay.mjs";

const merchantId = crypto.randomUUID();
const order = () => ({
  offlineTxId: crypto.randomUUID(), idempotencyKey: crypto.randomUUID(),
  merchantId, branchId: null, terminalId: null, cashierId: null, customerId: null,
  items: [{ itemName: "Waakye", quantity: 1, unitPricePesewas: 3000 }],
  orderType: "TAKEOUT", totalPesewas: 3000, payments: [], discountPesewas: 0,
  taxPesewas: 0, tipPesewas: 0,
});
test("durable LAN journal restores orders and idempotent bump actions", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "elepay-lan-"));
  try {
    const file = path.join(dir, "journal.jsonl");
    let j = new LanOrderJournal(file, merchantId);
    const payload = order();
    const row = j.accept(payload);
    assert.equal(row.status, "NEW");
    assert.equal(j.accept(payload).offlineTxId, payload.offlineTxId);
    assert.throws(() => j.accept({ ...payload, totalPesewas: 100 }), /different details/);
    const actionId = crypto.randomUUID();
    assert.equal(j.advance(payload.offlineTxId, actionId, "NEW").status, "PREPARING");
    assert.equal(j.advance(payload.offlineTxId, actionId, "NEW").status, "PREPARING");
    assert.throws(() => j.advance(payload.offlineTxId, crypto.randomUUID(), "NEW"), /changed/);
    j.link(payload.offlineTxId, crypto.randomUUID());
    j = new LanOrderJournal(file, merchantId);
    assert.equal(j.all()[0].status, "PREPARING");
    assert.ok(j.all()[0].serverOrderId);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
test("LAN relay does not accept invented electronic-payment approvals", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "elepay-lan-"));
  try {
    const j = new LanOrderJournal(path.join(dir, "audit.jsonl"), merchantId);
    assert.throws(() => j.accept({ ...order(), payments: [{ tenderType: "CARD", amountPesewas: 3000 }] }),
      e => e instanceof RelayError && e.status === 422);
    assert.equal(j.all().length, 0);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
test("tampering with completed LAN journal fails closed", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "elepay-lan-"));
  try {
    const file = path.join(dir, "audit.jsonl");
    const j = new LanOrderJournal(file, merchantId);
    j.accept(order());
    fs.appendFileSync(file, "{ \"bad\": true }\n");
    assert.throws(() => new LanOrderJournal(file, merchantId));
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
