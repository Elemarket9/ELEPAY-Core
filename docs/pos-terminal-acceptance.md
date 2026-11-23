# ELEPAY restaurant POS: terminals, QR and barcode payments

This is a provider-neutral integration boundary, **not** a claim that a physical card
acceptance rail has been activated.

## Working today

- Cash tender is recorded against a POS order and active terminal shift.
- MoMo/card/QR payment uses the authoritative ELEPAY checkout; the sale is only PAID
  after the existing checkout module verifies a fully paid, undisputed ledger transaction.
- `POST /v1/pos/orders/{orderId}/payment-code` provides the hosted checkout URL
  as a QR payload and an `ELEPAY-PAY:` barcode reference. A 2D barcode scanner can
  read the payment QR. Neither a scanned QR nor a typed barcode alone confirms payment.
- Menu SKU/product barcode lookup is supported in the POS web register with keyboard-
  wedge scanners (USB/Bluetooth scanners behaving like a keyboard).
- `GET /v1/pos/terminals/available/{merchantId}` returns active terminals available
  to the operator, with branch scoping.

## Certified card-present terminals

- `POST /v1/pos/orders/{orderId}/terminal-payments` accepts
  `{ "terminalId": "...", "mode": "NFC_TAP" }` (also `CHIP_INSERT` or
  `MAGSTRIPE`) and requires an `Idempotency-Key` header.
- The server requires a registered, active, branch-matching terminal and a
  configured implementation of `PosTerminalAcceptanceService.CertifiedTerminalAdapter`.
- Without a certified adapter the API fails with `RAIL_NOT_CONFIGURED`.
- An adapter may return only PENDING plus a real provider reference; **it must not**
  mark a POS order PAID. Settlement is reconciled through the existing checkout
  rail and its ledger evidence before calling `/digital-checkout/confirm`.
- Card PAN, PIN, EMV cryptograms and raw NFC APDUs must never be sent to the ELEPAY
  browser, POS REST API or logs. Use the acquiring partner's certified device SDK.

## Offline safety

Unverified offline card requests are declined. Historic offline-card queue rows are
not automatically settled, and replaying that queue cannot mint merchant funds.
Offline cash ordering and local-network KDS operation require their own durable
offline implementation and endurance tests.

## Next integration prerequisites

1. Acquire a contractual acquirer/device-provider integration and certified SDK
   (PAX/Sunmi/other hardware choices by adapter, not vendor-specific business logic).
2. Provision terminal credentials and merchant IDs outside source code.
3. Implement signed terminal webhooks, terminal-pending recovery, void/refund, and
   an idempotent acquirer-to-checkout reconciliation with ledger evidence.
4. Code 128 B rendering is now available in the browser; confirm it on actual scanner/printer hardware.
5. Verify real NFC tap, chip, 2D barcode and cash sales on physical hardware.
6. For Tap to Phone on Android, use a validated PCI MPoC solution, not browser Web NFC.

The backend never fabricates an authorization or reports a card tap as successful
when no certified payment route is connected.

A merchant scanner can resolve an issued `ELEPAY-PAY:` reference through
`POST /v1/pos/payment-codes/resolve`. This operation verifies ownership,
returns the pending hosted checkout, and **never captures or settles payment**.
