# ELEPAY product specification

## What we are building

ELEPAY is a Ghana-first, full-service fintech with its own customer wallets, merchant payment processing, transfers, payouts, foreign exchange workflows and settlement operations. It serves individuals, independent merchants and marketplaces. Its defining capability is one customer payment that can fund several independent sellers and a delivery partner, with a traceable share for each recipient.

## Customers and products

| User | Product |
| --- | --- |
| Individuals | An ELEPAY wallet for receiving, keeping, sending and spending funds, with top-ups and withdrawals through supported channels. Customers can also pay a merchant directly using supported MoMo or card methods. |
| Independent merchants | Payment links, QR codes, merchant codes and checkout integrations. A merchant dashboard shows sales, fees, refunds, pending funds, available balances and withdrawals. Branch and cashier permissions limit who can collect, refund or withdraw. |
| Marketplaces | A single checkout across multiple merchants, per-seller accounting, delivery allocations, order-level and item-level refund support, settlement reports and developer APIs. ELEMARKET and ELESEND are intended early integrations. |
| Operations teams | Tools to review onboarding, investigate disputed payments, manage limits and fees, reconcile provider statements, and audit sensitive actions. |

## Platform payouts and banking

ELEPAY should support earnings payouts for ride-hailing and delivery platforms such as Bolt and Yango, and creator platforms such as X and TikTok. These are target integrations. Each platform connection requires a supported payout interface and has its own countries, currencies and eligible destinations.

Support two payout flows. Platforms that integrate directly can send single or batch payouts to recipients. Drivers and creators can also receive earnings through an eligible bank account or other payout destination that their platform supports and that ELEPAY can connect to. Track an incoming payout using a confirmed provider or bank reference before making the funds available.

Support Ghanaian bank accounts for verified payout destinations, bank-to-wallet funding where supported, wallet-to-bank withdrawals, and merchant settlements. Use bank or payment-provider adapters so ELEPAY can add institutions without changing the ledger. Treat foreign-currency earnings as a separate flow with explicit currency, conversion rate, fees and settlement amounts wherever the provider supports conversion.

Give each payout a unique reference and track pending, completed, failed and returned outcomes. For batch payouts, track each recipient separately, including partial failures. Reconcile platform records, bank or provider records, and ledger entries. Surface unmatched or returned payouts for operations review.

## International payments and remittances

Support inbound remittances to Ghana, outbound transfers from Ghana where supported, international creator and contractor payouts, and cross-border merchant collections and settlements. Start with USD/GHS and design for GBP, EUR and other currencies as supported corridors become available.

Target Western Union and evaluate MoneyGram, Remitly, Wise and Payoneer for the corridors and products each can support. Connect through contracted provider or bank interfaces. Model each corridor by sending country, receiving country, source currency, destination currency and delivery method. Delivery methods may include a bank account, mobile money, an ELEPAY wallet or cash pickup where the partner supports it.

Before confirmation, show the amount sent, fees, exchange rate, amount the recipient will receive, quote expiration and expected arrival. Use a provider-confirmed quote and require a fresh confirmation if the quote expires. Record the source and destination amounts, rate and fees separately. Store amounts in currency-specific minor units and balance journal entries within each currency using linked FX clearing entries.

Give each transfer a traceable reference and show pending, paid, failed or returned status. Reconcile the funding, conversion and delivery records against the partner and bank statements. Support currency-specific statements and balances where the underlying account or provider explicitly supports holding that currency.

## Payment processing and network connections

ELEPAY operates customer and merchant accounts, checkout and payment APIs, payment processing, wallets, the authoritative ledger, refunds, disputes, payout instructions, reconciliation and settlement reporting. ELEPAY processes internal wallet transfers and wallet-funded merchant payments on its own ledger. Its multi-merchant checkout allocates one payment across sellers, delivery partners and fees, with traceable settlement and refunds for each allocation. Connect through the interfaces available to ELEPAY for banks, mobile-money operators, card acquiring partners or processors, and international remittance partners.

Implementation should prioritize the bank, mobile-money and acquiring connections ELEPAY can obtain. Paystack and Hubtel are outside the required processing path. Keep network adapters independent so a new connection can be added without moving merchant balances or changing checkout rules.

## Platform API orders

Platforms can integrate ELEPAY into their own order lifecycle. An authenticated platform creates a payment order with its order reference, currency, amount and authorized merchant allocations. ELEPAY returns an order ID and any customer approval instructions. The platform can query status, cancel an unpaid order, request full or allocation-level refunds, submit eligible payout batches and retrieve settlement and reconciliation records.

Provide signed webhooks for payment, refund, payout and settlement events. Use stable event IDs, retry delivery and expose a status API so integrations can recover missed events. Require idempotency keys for financial requests and scope credentials to authorized merchants and actions. Keep authorization of customer funds explicit. For recurring or off-session payments, require a valid customer mandate supported by the payment method.

An ELEMARKET order can create one ELEPAY payment order covering several sellers and ELESEND delivery. ELEPAY confirms the payment and reports each allocation. The marketplace uses that confirmation to release the order for fulfillment and can later refund one seller's item through the API.

## How a marketplace payment works

A shopper owes GH₵80 to one seller, GH₵50 to another and GH₵20 for delivery. They approve GH₵150 once. ELEPAY records the three allocations under one receipt and tracks each recipient's funds through settlement. If the second seller cancels, ELEPAY can reverse that GH₵50 allocation and refund the buyer while preserving the other two allocations. Each seller can see their own transactions and settlement status.

## Recipient name confirmation

When a signed-in customer enters a phone number to send money, normalize it and perform an exact recipient lookup after the complete number is entered. For an active ELEPAY wallet, show the registered recipient name on the confirmation screen before the sender authorizes the transfer. For a mobile-money number or bank account, use the name returned by the connected payment network when its verification interface supports it.

Bind the confirmed recipient to the transfer request. Any edit to the number or destination invalidates the confirmation and requires a fresh lookup. If verification is unavailable, show that the name could not be verified. Require a verified recipient before completing a phone-number transfer.

Limit lookup to authenticated payment flows. Rate-limit by sender and audit abuse without exposing a searchable customer directory. Return only the name needed for confirmation, with no balance, identity documents or other profile fields.

## How stored value works

ELEPAY customers can keep funds in a wallet, and merchants can hold proceeds in a business balance for later withdrawal.

Merchant proceeds can move through pending, available and paid-out states. Wallet top-ups, payouts and external refunds require provider confirmation or reconciliation evidence before ELEPAY reports a final result. For a pending payment, customers and merchants use the same receipt reference to check status and request help.

## Access and affordability

Customers should be able to pay through a lightweight link without installing an app. The product targets Android, iOS and partner-supported USSD for basic phones. Show the merchant name, total amount and applicable fees before approval. Measure fees on small purchases such as GH₵5 and GH₵10. Give users a short receipt reference and an accessible support route when connectivity drops or a transaction stays pending.

## Engineering direction

Java 25 and Spring Boot power the core modules. PostgreSQL is the authoritative store for the ledger, with Flyway migrations and integer minor units. Financial postings must be atomic, balanced, append-only and idempotent. Refunds use linked reversals. Provider callbacks require verification, and money that passes through external providers must reconcile to provider records.

The product uses scoped roles, merchant isolation and audited operations. Spring Security with OAuth 2.0/OIDC is the target identity stack. Merchant and admin dashboards target React and TypeScript. Native apps target Kotlin and Swift. The engineering directive defines the detailed technology choices, security requirements and verification criteria.

## Implementation status

The repository contains a sandbox for simulated wallet funding, multi-recipient checkout, partial refunds, withdrawals and payment intent confirmation or cancellation. It records terminal payment events in a PostgreSQL outbox. The Java build and full PostgreSQL smoke run were still unverified at the time of this specification.

The live product still requires merchant onboarding and authorization, provider integrations, provider and bank reconciliation, settlement operations, outbox delivery, customer checkout, merchant and admin dashboards, mobile apps, and partner-supported USSD. A staged live pilot requires verified financial and operational controls.
