<?php
/**
 * Plugin Name: ELEPAY Payment Gateway for WooCommerce
 * Plugin URI: https://elepay.com/developer/woocommerce
 * Description: Accept direct Ghanaian payment rails (MTN MoMo, Telecel Cash, AT Money, GhIPSS GhQR, Direct Card) seamlessly on your WooCommerce store via ELEPAY.
 * Version: 1.2.0
 * Author: ELEPAY Technologies Ltd
 * Author URI: https://elepay.com
 * Text Domain: woocommerce-elepay
 * Domain Path: /languages
 * Requires at least: 5.8
 * Requires PHP: 7.4
 * WC requires at least: 5.0
 * WC tested up to: 9.2
 * License: Apache-2.0
 * License URI: https://www.apache.org/licenses/LICENSE-2.0
 */

if (!defined('ABSPATH')) {
    exit; // Exit if accessed directly
}

define('WC_ELEPAY_VERSION', '1.2.0');
define('WC_ELEPAY_PLUGIN_DIR', plugin_dir_path(__FILE__));
define('WC_ELEPAY_PLUGIN_URL', plugin_dir_url(__FILE__));

/**
 * Initialize ELEPAY WooCommerce Gateway after WooCommerce is loaded.
 */
function wc_elepay_init() {
    if (!class_exists('WC_Payment_Gateway')) {
        add_action('admin_notices', 'wc_elepay_missing_wc_notice');
        return;
    }

    require_once WC_ELEPAY_PLUGIN_DIR . 'includes/class-wc-elepay-api.php';
    require_once WC_ELEPAY_PLUGIN_DIR . 'includes/class-wc-gateway-elepay.php';

    // Register Gateway
    add_filter('woocommerce_payment_gateways', 'wc_elepay_add_gateway_class');

    // Declare HPOS Compatibility
    add_action('before_woocommerce_init', function () {
        if (class_exists(\Automattic\WooCommerce\Utilities\FeaturesUtil::class)) {
            \Automattic\WooCommerce\Utilities\FeaturesUtil::declare_compatibility('custom_order_tables', __FILE__, true);
            \Automattic\WooCommerce\Utilities\FeaturesUtil::declare_compatibility('cart_checkout_blocks', __FILE__, true);
        }
    });

    // Register Blocks Support
    add_action('woocommerce_blocks_loaded', 'wc_elepay_register_blocks_support');
}
add_action('plugins_loaded', 'wc_elepay_init', 11);

/**
 * Notice if WooCommerce is not active.
 */
function wc_elepay_missing_wc_notice() {
    echo '<div class="error"><p>' .
        sprintf(
            /* translators: %s: WooCommerce download link */
            esc_html__('ELEPAY Payment Gateway requires %s to be installed and active.', 'woocommerce-elepay'),
            '<a href="https://wordpress.org/plugins/woocommerce/" target="_blank">WooCommerce</a>'
        ) . '</p></div>';
}

/**
 * Register ELEPAY gateway with WooCommerce.
 */
function wc_elepay_add_gateway_class($gateways) {
    $gateways[] = 'WC_Gateway_Elepay';
    return $gateways;
}

/**
 * Register Block Support for Cart & Checkout Blocks.
 */
function wc_elepay_register_blocks_support() {
    if (class_exists('Automattic\WooCommerce\Blocks\Payments\Integrations\AbstractPaymentMethodType')) {
        require_once WC_ELEPAY_PLUGIN_DIR . 'includes/class-wc-elepay-blocks.php';
        add_action(
            'woocommerce_blocks_payment_method_type_registration',
            function (\Automattic\WooCommerce\Blocks\Payments\PaymentMethodRegistry $payment_method_registry) {
                $payment_method_registry->register(new WC_Elepay_Blocks_Support());
            }
        );
    }
}

/**
 * Add settings action link to plugins list.
 */
function wc_elepay_plugin_action_links($links) {
    $plugin_links = array(
        '<a href="' . admin_url('admin.php?page=wc-settings&tab=checkout&section=elepay') . '">' . __('Settings', 'woocommerce-elepay') . '</a>',
        '<a href="https://elepay.com/developer/docs" target="_blank">' . __('Docs', 'woocommerce-elepay') . '</a>',
    );
    return array_merge($plugin_links, $links);
}
add_filter('plugin_action_links_' . plugin_basename(__FILE__), 'wc_elepay_plugin_action_links');
