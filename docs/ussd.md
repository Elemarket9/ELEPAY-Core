# ELEPAY USSD

Basic-phone menu: balance, send money with recipient name confirmation, and pay a merchant by code.

```
ELEPAY
1. Balance        -> PIN -> available balance
2. Send money     -> number -> "Send to <verified name> (+233…)" -> amount -> fee/total -> PIN -> receipt
3. Pay merchant   -> 6-digit code -> merchant name -> amount -> fee/total -> PIN -> receipt
0. Exit
```

- The caller is the MSISDN that the verified gateway supplies. The wallet must be active and have a USSD PIN, which customers set in the app or web wallet (`PUT /v1/wallet/ussd-pin`, 4–6 digits, not repeated or sequential). PINs are stored as bcrypt hashes. Three wrong PINs in a row lock USSD for 30 minutes and are audited.
- Sending uses the same lookup, rate limits and single-use confirmation as the apps. The registered name is shown before the PIN is asked.
- Idempotency keys derive from the gateway session id, so a repeated gateway request cannot send or pay twice. Sessions expire after 5 idle minutes.
- Gateway protocol `generic`: `POST /v1/ussd/generic` with JSON `sessionId`, `msisdn`, `input` and `type` (`start`, `continue` or `end`), signed `X-Ussd-Signature: hex(HMAC-SHA256(ELEPAY_USSD_SECRET, body))`. The response is `{ "message", "end" }`. The endpoint returns 503 until a secret is configured.

No mobile network operator or USSD aggregator connection is configured yet. A short code and a network-specific gateway adapter are required before customers can dial ELEPAY. All tests use the generic protocol directly.
