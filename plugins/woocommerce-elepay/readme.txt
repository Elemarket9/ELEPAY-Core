=== ELEPAY Payment Gateway for WooCommerce ===
Contributors: elepay
Tags: woocommerce, payment gateway, mobile money, mtn momo, telecel cash, airteltigo, ghana, cards
Requires at least: 5.8
Tested up to: 6.7
Requires PHP: 7.4
Stable tag: 1.2.0
License: Apache-2.0
License URI: https://www.apache.org/licenses/LICENSE-2.0

Accept Mobile Money (MTN MoMo, Telecel Cash, AirtelTigo Money) and Card payments directly on your WooCommerce store in Ghana Cedis (GHS).

== Description ==

ELEPAY is the developer-friendly payment platform for Ghana. This official WooCommerce payment gateway plugin lets you accept customer payments via:
* **MTN Mobile Money**
* **Telecel Cash**
* **AirtelTigo Money**
* **Visa & Mastercard**
* **ELEPAY Wallet**

Features:
* Automatic order status updates via cryptographic webhook signatures (`Elepay-Signature`)
* Replay attack protection with 300-second timestamp drift validation
* Seamless checkout redirect with instant order return verification
* Full support for refunds directly from the WooCommerce order admin screen
* Compatible with High-Performance Order Storage (HPOS) and WooCommerce Checkout Blocks

== Installation ==

1. Upload the `woocommerce-elepay` folder to `/wp-content/plugins/` directory, or install via WordPress Plugin Admin.
2. Activate the plugin through the 'Plugins' menu in WordPress.
3. Go to **WooCommerce > Settings > Payments > ELEPAY**.
4. Enable the gateway and enter your API Key and Webhook Secret from the ELEPAY Developer Portal.
5. Save changes.

== Configuration ==

1. **Test Mode**: Check to test transactions in Sandbox with test keys (`ek_test_...`).
2. **API Keys**: Found under **Developer Portal > API Keys** on ELEPAY.
3. **Webhook Secret**: Copy the Webhook Notification URL shown in WooCommerce settings, register it in your ELEPAY Developer Portal, and copy the generated Webhook Secret (`whsec_...`) back to WooCommerce settings.

== Frequently Asked Questions ==

= Does this plugin support refunds? =
Yes! You can issue full or partial refunds directly within WooCommerce Order Admin.

= Does it support HPOS? =
Yes, it fully declares compatibility with WooCommerce High-Performance Order Storage (Custom Order Tables).

== Changelog ==

= 1.2.0 =
* Initial release of official ELEPAY WooCommerce payment gateway.
* Added support for MoMo, Card, and Wallet payments.
* Added HMAC-SHA256 webhook verification and replay protection.
* Added WooCommerce Cart & Checkout Blocks integration.
