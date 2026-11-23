=== ELEPAY Payment Gateway for WooCommerce ===
Contributors: elepay
Tags: woocommerce, payment gateway, mobile money, mtn momo, telecel cash, at money, ghipss ghqr, ghana, direct cards
Requires at least: 5.8
Tested up to: 6.7
Requires PHP: 7.4
Stable tag: 1.3.0
License: Apache-2.0
License URI: https://www.apache.org/licenses/LICENSE-2.0

Connect a Ghana Cedis (GHS) WooCommerce store to your ELEPAY merchant checkout. Payment methods are shown by ELEPAY only when their authorized external rails are available.

== Description ==

ELEPAY is a fintech payment platform. This optional WordPress plugin does not contain payment processing or a ledger. It forwards payments to the ELEPAY API, where only authorized and configured rails may process real transactions. Depending on integrations activated in ELEPAY Core, the merchant may offer:
* **MTN Mobile Money (MoMo)**
* **Telecel Cash**
* **AT Money**
* **GhIPSS GhQR (National Universal QR)**
* **Direct Card (Visa, Mastercard, Gh-Link)**
* **ELEPAY Wallet**

Features:
* Checkout requests use the published merchant API contract (GHS pesewas, merchant ID, stable order reference and idempotency key).
* Outbound HMAC-SHA256 v2 request signing with server-side nonce replay protection
* Automatic order status updates via cryptographic webhook signatures (`Elepay-Signature`)
* Replay attack protection with 300-second timestamp drift validation
* Seamless checkout redirect with instant order return verification
* Refunds from WooCommerce are supported for an unambiguous single-allocation checkout; multi-allocation refunds must be handled in ELEPAY's authorized merchant interface.
* A pending external rail refund is never reported as completed; reconcile it before updating WooCommerce.
* Compatible with High-Performance Order Storage (HPOS) and WooCommerce Checkout Blocks

== Installation ==

1. Upload the `woocommerce-elepay` folder to `/wp-content/plugins/` directory, or install via WordPress Plugin Admin.
2. Activate the plugin through the 'Plugins' menu in WordPress.
3. Go to **WooCommerce > Settings > Payments > ELEPAY**.
4. Configure the HTTPS ELEPAY API URL, Merchant UUID, API Key, Request Signing Secret and Webhook Secret from the ELEPAY Developer Portal. The gateway is disabled until explicitly enabled.
5. Save changes.

== Configuration ==

1. **Test Mode**: Optional non-production integration to an authorized test endpoint and test credentials; never use test mode for live payments. The plugin itself does not simulate successful payments.
2. **API Keys**: Found under **Developer Portal > API Keys** on ELEPAY.
3. **Request Signing Secret**: Used for HMAC-SHA256 v2 signature verification on all mutating requests.
4. **Webhook Secret**: Copy the Webhook Notification URL shown in WooCommerce settings, register it in your ELEPAY Developer Portal, and copy the generated Webhook Secret (`whsec_...`) back to WooCommerce settings.

== Frequently Asked Questions ==

= Does this plugin support refunds? =
Only when the verified checkout contains exactly one allocation and ELEPAY confirms the refund is completed. Multi-allocation refunds and pending/failed external rail refunds require operator reconciliation via ELEPAY.

= Does it support HPOS? =
Yes, it fully declares compatibility with WooCommerce High-Performance Order Storage (Custom Order Tables).

== Changelog ==

= 1.3.0 =
* Conform merchant checkout requests to the actual ELEPAY API contract.
* Verify checkout reference, amount and payment status before marking WooCommerce orders paid.
* Resolve refund allocation from the authenticated checkout instead of assuming line 1.
* Fail closed on ambiguous or pending refunds, missing credentials, non-HTTPS URLs, and invalid responses.
* Persist checkout/refund idempotency keys across retry attempts.
* Add isolated PHP security/contract regression tests.

= 1.3.0 =
* Official ELEPAY WooCommerce payment gateway with direct Ghanaian rails.
* Added support for MTN MoMo, Telecel Cash, AT Money, GhIPSS GhQR, Direct Card, and Wallet.
* Added HMAC-SHA256 v2 request signing and webhook signature verification with replay protection.
* Added WooCommerce Cart & Checkout Blocks integration.
