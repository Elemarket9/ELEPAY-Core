=== ELEPAY Payment Gateway for WooCommerce ===
Contributors: elepay
Tags: woocommerce, payment gateway, mobile money, mtn momo, telecel cash, at money, ghipss ghqr, ghana, direct cards
Requires at least: 5.8
Tested up to: 6.7
Requires PHP: 7.4
Stable tag: 1.2.0
License: Apache-2.0
License URI: https://www.apache.org/licenses/LICENSE-2.0

Accept direct Ghanaian payment network rails (MTN MoMo, Telecel Cash, AT Money, GhIPSS GhQR, and Direct Card) on your WooCommerce store in Ghana Cedis (GHS).

== Description ==

ELEPAY is the developer-first payment platform for Ghana, providing direct connections to national clearing and telecom acquiring rails without intermediate aggregators. This official WooCommerce payment gateway plugin lets you accept customer payments via:
* **MTN Mobile Money (MoMo)**
* **Telecel Cash**
* **AT Money**
* **GhIPSS GhQR (National Universal QR)**
* **Direct Card (Visa, Mastercard, Gh-Link)**
* **ELEPAY Wallet**

Features:
* Outbound HMAC-SHA256 v2 request signing with distributed nonce replay protection
* Automatic order status updates via cryptographic webhook signatures (`Elepay-Signature`)
* Replay attack protection with 300-second timestamp drift validation
* Seamless checkout redirect with instant order return verification
* Full support for refunds directly from the WooCommerce order admin screen
* Compatible with High-Performance Order Storage (HPOS) and WooCommerce Checkout Blocks

== Installation ==

1. Upload the `woocommerce-elepay` folder to `/wp-content/plugins/` directory, or install via WordPress Plugin Admin.
2. Activate the plugin through the 'Plugins' menu in WordPress.
3. Go to **WooCommerce > Settings > Payments > ELEPAY**.
4. Enable the gateway and enter your API Key, Request Signing Secret, and Webhook Secret from the ELEPAY Developer Portal.
5. Save changes.

== Configuration ==

1. **Test Mode**: Check to test transactions in Sandbox with test keys (`ek_test_...`) and test signing secrets.
2. **API Keys**: Found under **Developer Portal > API Keys** on ELEPAY.
3. **Request Signing Secret**: Used for HMAC-SHA256 v2 signature verification on all mutating requests.
4. **Webhook Secret**: Copy the Webhook Notification URL shown in WooCommerce settings, register it in your ELEPAY Developer Portal, and copy the generated Webhook Secret (`whsec_...`) back to WooCommerce settings.

== Frequently Asked Questions ==

= Does this plugin support refunds? =
Yes! You can issue full or partial refunds directly within WooCommerce Order Admin.

= Does it support HPOS? =
Yes, it fully declares compatibility with WooCommerce High-Performance Order Storage (Custom Order Tables).

== Changelog ==

= 1.2.0 =
* Official ELEPAY WooCommerce payment gateway with direct Ghanaian rails.
* Added support for MTN MoMo, Telecel Cash, AT Money, GhIPSS GhQR, Direct Card, and Wallet.
* Added HMAC-SHA256 v2 request signing and webhook signature verification with replay protection.
* Added WooCommerce Cart & Checkout Blocks integration.
