<?php
/**
 * WooCommerce Blocks Support for ELEPAY
 */

use Automattic\WooCommerce\Blocks\Payments\Integrations\AbstractPaymentMethodType;

if (!defined('ABSPATH')) {
    exit;
}

final class WC_Elepay_Blocks_Support extends AbstractPaymentMethodType {

    protected $name = 'elepay';

    public function initialize() {
        $this->settings = get_option('woocommerce_elepay_settings', array());
    }

    public function is_active() {
        return !empty($this->settings['enabled']) && 'yes' === $this->settings['enabled'];
    }

    public function get_payment_method_script_handles() {
        wp_register_script(
            'wc-elepay-blocks-integration',
            WC_ELEPAY_PLUGIN_URL . 'assets/blocks.js',
            array(
                'wc-blocks-registry',
                'wc-settings',
                'wp-element',
                'wp-html-entities',
                'wp-i18n',
            ),
            WC_ELEPAY_VERSION,
            true
        );

        return array('wc-elepay-blocks-integration');
    }

    public function get_payment_method_data() {
        return array(
            'title'       => $this->get_setting('title', 'Mobile Money, GhQR & Card (ELEPAY)'),
            'description' => $this->get_setting('description', 'Pay securely with MTN MoMo, Telecel Cash, AT Money, GhIPSS GhQR, or Debit/Credit Card.'),
            'supports'    => array('products'),
            'icon'        => WC_ELEPAY_PLUGIN_URL . 'assets/elepay-icon.svg',
        );
    }
}
