<?php
/**
 * WC_Gateway_Elepay Class
 *
 * Extends WooCommerce WC_Payment_Gateway to provide seamless Mobile Money
 * (MTN MoMo, Telecel Cash, AirtelTigo Money) and Card checkout in Ghana Cedis (GHS).
 */

if (!defined('ABSPATH')) {
    exit;
}

class WC_Gateway_Elepay extends WC_Payment_Gateway {

    public $testmode;
    public $test_api_key;
    public $live_api_key;
    public $webhook_secret;
    public $api_base_url;
    public $merchant_id;

    public function __construct() {
        $this->id                 = 'elepay';
        $this->icon               = apply_filters('woocommerce_elepay_icon', WC_ELEPAY_PLUGIN_URL . 'assets/elepay-icon.svg');
        $this->has_fields         = false;
        $this->method_title       = __('ELEPAY (MoMo & Card)', 'woocommerce-elepay');
        $this->method_description = __('Accept MTN Mobile Money, Telecel Cash, AirtelTigo Money and Card payments via ELEPAY.', 'woocommerce-elepay');

        $this->supports = array(
            'products',
            'refunds',
        );

        $this->init_form_fields();
        $this->init_settings();

        $this->title          = $this->get_option('title', __('Mobile Money & Card (ELEPAY)', 'woocommerce-elepay'));
        $this->description    = $this->get_option('description', __('Pay securely with MTN MoMo, Telecel Cash, AirtelTigo Money, or Debit/Credit Card.', 'woocommerce-elepay'));
        $this->enabled        = $this->get_option('enabled', 'yes');
        $this->testmode       = 'yes' === $this->get_option('testmode', 'yes');
        $this->test_api_key   = $this->get_option('test_api_key');
        $this->live_api_key   = $this->get_option('live_api_key');
        $this->webhook_secret = $this->get_option('webhook_secret');
        $this->api_base_url   = $this->get_option('api_base_url', 'https://api.elepay.com');
        $this->merchant_id    = $this->get_option('merchant_id');

        // Save admin options
        add_action('woocommerce_update_options_payment_gateways_' . $this->id, array($this, 'process_admin_options'));

        // Webhook handler hook: /wc-api/wc_gateway_elepay
        add_action('woocommerce_api_wc_gateway_elepay', array($this, 'handle_webhook'));

        // Order thank you verification hook
        add_action('woocommerce_thankyou_' . $this->id, array($this, 'check_order_status_on_return'));
    }

    /**
     * Settings Fields Configuration
     */
    public function init_form_fields() {
        $webhook_url = WC()->api_request_url('wc_gateway_elepay');

        $this->form_fields = array(
            'enabled' => array(
                'title'   => __('Enable/Disable', 'woocommerce-elepay'),
                'type'    => 'checkbox',
                'label'   => __('Enable ELEPAY Payment Gateway', 'woocommerce-elepay'),
                'default' => 'yes',
            ),
            'title' => array(
                'title'       => __('Title', 'woocommerce-elepay'),
                'type'        => 'text',
                'description' => __('Payment method title that the customer will see on checkout.', 'woocommerce-elepay'),
                'default'     => __('Mobile Money & Card (ELEPAY)', 'woocommerce-elepay'),
                'desc_tip'    => true,
            ),
            'description' => array(
                'title'       => __('Description', 'woocommerce-elepay'),
                'type'        => 'textarea',
                'description' => __('Payment method description that the customer will see on checkout.', 'woocommerce-elepay'),
                'default'     => __('Pay securely with MTN MoMo, Telecel Cash, AirtelTigo Money, or Debit/Credit Card.', 'woocommerce-elepay'),
            ),
            'testmode' => array(
                'title'       => __('Sandbox / Test Mode', 'woocommerce-elepay'),
                'type'        => 'checkbox',
                'label'       => __('Enable Test Mode (simulates transactions without real money)', 'woocommerce-elepay'),
                'default'     => 'yes',
                'description' => __('Use test API keys from the ELEPAY developer portal.', 'woocommerce-elepay'),
            ),
            'test_api_key' => array(
                'title'       => __('Test API Key', 'woocommerce-elepay'),
                'type'        => 'password',
                'description' => __('Begins with ek_test_...', 'woocommerce-elepay'),
                'default'     => '',
            ),
            'live_api_key' => array(
                'title'       => __('Live API Key', 'woocommerce-elepay'),
                'type'        => 'password',
                'description' => __('Begins with ek_live_...', 'woocommerce-elepay'),
                'default'     => '',
            ),
            'webhook_secret' => array(
                'title'       => __('Webhook Secret', 'woocommerce-elepay'),
                'type'        => 'password',
                'description' => __('Begins with whsec_... Paste this into your ELEPAY Developer Portal webhook settings.', 'woocommerce-elepay'),
                'default'     => '',
            ),
            'merchant_id' => array(
                'title'       => __('Merchant UUID', 'woocommerce-elepay'),
                'type'        => 'text',
                'description' => __('Your ELEPAY Merchant UUID (used for refunds and merchant operations).', 'woocommerce-elepay'),
                'default'     => '',
            ),
            'api_base_url' => array(
                'title'       => __('API Base URL', 'woocommerce-elepay'),
                'type'        => 'text',
                'description' => __('Base endpoint for ELEPAY Core API.', 'woocommerce-elepay'),
                'default'     => 'https://api.elepay.com',
            ),
            'webhook_url_info' => array(
                'title'       => __('Webhook Notification URL', 'woocommerce-elepay'),
                'type'        => 'title',
                'description' => '<code>' . esc_url($webhook_url) . '</code><br><span class="description">' .
                    __('Copy this URL to your ELEPAY Developer Portal to receive real-time payment notifications.', 'woocommerce-elepay') . '</span>',
            ),
        );
    }

    /**
     * Get the active API Key based on mode
     */
    public function get_api_key() {
        return $this->testmode ? $this->test_api_key : $this->live_api_key;
    }

    /**
     * Get API Client instance
     */
    public function get_api() {
        return new WC_Elepay_API($this->get_api_key(), $this->api_base_url);
    }

    /**
     * Process checkout payment
     */
    public function process_payment($order_id) {
        $order = wc_get_order($order_id);
        if (!$order) {
            wc_add_notice(__('Unable to find order.', 'woocommerce-elepay'), 'error');
            return array('result' => 'failure');
        }

        $api = $this->get_api();
        $total_pesewas = intval(round($order->get_total() * 100));

        // Build itemized lines
        $lines = array();
        foreach ($order->get_items() as $item) {
            $product = $item->get_product();
            $item_total_pesewas = intval(round($order->get_line_total($item, true) * 100));
            $lines[] = array(
                'kind'        => 'sale',
                'description' => $item->get_name() . ' x ' . $item->get_quantity(),
                'amount'      => $item_total_pesewas,
            );
        }

        // Add shipping line if applicable
        if ($order->get_shipping_total() > 0) {
            $lines[] = array(
                'kind'        => 'delivery',
                'description' => __('Shipping', 'woocommerce-elepay'),
                'amount'      => intval(round($order->get_shipping_total() * 100)),
            );
        }

        // Ensure total lines equals total pesewas or fall back to single line
        $sum_lines = array_sum(array_column($lines, 'amount'));
        if ($sum_lines !== $total_pesewas) {
            $lines = array(
                array(
                    'kind'        => 'sale',
                    'description' => sprintf(__('Order #%s', 'woocommerce-elepay'), $order->get_order_number()),
                    'amount'      => $total_pesewas,
                )
            );
        }

        $payload = array(
            'currency'       => 'GHS',
            'orderReference' => 'wc_' . $order->get_id() . '_' . time(),
            'description'    => sprintf(__('WooCommerce Order #%s at %s', 'woocommerce-elepay'), $order->get_order_number(), get_bloginfo('name')),
            'lines'          => $lines,
            'returnUrl'      => $this->get_return_url($order),
            'cancelUrl'      => $order->get_cancel_order_url(),
            'metadata'       => array(
                'order_id'       => $order->get_id(),
                'order_key'      => $order->get_order_key(),
                'customer_email' => $order->get_billing_email(),
                'customer_phone' => $order->get_billing_phone(),
            ),
        );

        try {
            $session = $api->create_checkout($payload);

            // Save checkout reference in order metadata
            $order->update_meta_data('_elepay_checkout_reference', $session['reference']);
            $order->add_order_note(sprintf(__('ELEPAY checkout session created. Reference: %s', 'woocommerce-elepay'), $session['reference']));
            $order->save();

            return array(
                'result'   => 'success',
                'redirect' => $session['payUrl'],
            );
        } catch (Exception $e) {
            wc_add_notice(__('Payment processing error: ', 'woocommerce-elepay') . $e->getMessage(), 'error');
            return array('result' => 'failure');
        }
    }

    /**
     * Process Refunds
     */
    public function process_refund($order_id, $amount = null, $reason = '') {
        $order = wc_get_order($order_id);
        if (!$order) {
            return new WP_Error('invalid_order', __('Order not found.', 'woocommerce-elepay'));
        }

        $checkout_ref = $order->get_meta('_elepay_checkout_reference');
        if (empty($checkout_ref)) {
            return new WP_Error('missing_reference', __('No ELEPAY checkout reference found on this order.', 'woocommerce-elepay'));
        }

        if (empty($this->merchant_id)) {
            return new WP_Error('missing_merchant_id', __('ELEPAY Merchant UUID is not configured in settings.', 'woocommerce-elepay'));
        }

        $amount_pesewas = intval(round($amount * 100));
        $api = $this->get_api();

        try {
            $api->create_refund($this->merchant_id, $checkout_ref, 1, $amount_pesewas, $reason ? $reason : 'WooCommerce Admin Refund');
            $order->add_order_note(sprintf(__('Refund of GH₵%s processed via ELEPAY.', 'woocommerce-elepay'), wc_format_decimal($amount, 2)));
            return true;
        } catch (Exception $e) {
            return new WP_Error('refund_failed', $e->getMessage());
        }
    }

    /**
     * Real-time Webhook Receiver
     * Validates Elepay-Signature, timestamp drift, and completes order
     */
    public function handle_webhook() {
        $raw_body = file_get_contents('php://input');
        $signature_header = isset($_SERVER['HTTP_ELEPAY_SIGNATURE']) ? $_SERVER['HTTP_ELEPAY_SIGNATURE'] : '';

        if (empty($signature_header)) {
            $headers = getallheaders();
            $signature_header = isset($headers['Elepay-Signature']) ? $headers['Elepay-Signature'] : '';
        }

        if (empty($this->webhook_secret)) {
            wp_die('Webhook Secret not configured.', 'ELEPAY Webhook Error', array('response' => 500));
        }

        $valid = WC_Elepay_API::verify_webhook_signature($raw_body, $signature_header, $this->webhook_secret);
        if (!$valid) {
            status_header(401);
            wp_send_json_error(array('message' => 'Invalid webhook signature or replay detected.'));
            exit;
        }

        $data = json_decode($raw_body, true);
        if (!$data || !isset($data['event'])) {
            status_header(400);
            wp_send_json_error(array('message' => 'Malformed payload.'));
            exit;
        }

        $event = $data['event'];
        $payload_data = isset($data['data']) ? $data['data'] : array();

        if ($event === 'checkout.paid' || $event === 'payment.success') {
            $reference = isset($payload_data['reference']) ? $payload_data['reference'] : null;
            $order_reference = isset($payload_data['orderReference']) ? $payload_data['orderReference'] : null;

            $order = null;
            if ($order_reference && preg_match('/^wc_(\d+)_/', $order_reference, $matches)) {
                $order = wc_get_order(intval($matches[1]));
            }

            if (!$order && $reference) {
                // Search by meta
                $orders = wc_get_orders(array(
                    'limit'      => 1,
                    'meta_key'   => '_elepay_checkout_reference',
                    'meta_value' => $reference,
                ));
                if (!empty($orders)) {
                    $order = $orders[0];
                }
            }

            if ($order) {
                if (!$order->is_paid()) {
                    $order->payment_complete($reference);
                    $order->add_order_note(sprintf(__('Payment verified via ELEPAY webhook. Reference: %s', 'woocommerce-elepay'), $reference));
                }
            }
        } elseif ($event === 'payment.failed') {
            $order_reference = isset($payload_data['orderReference']) ? $payload_data['orderReference'] : null;
            if ($order_reference && preg_match('/^wc_(\d+)_/', $order_reference, $matches)) {
                $order = wc_get_order(intval($matches[1]));
                if ($order && !$order->is_paid()) {
                    $reason = isset($payload_data['reason']) ? $payload_data['reason'] : __('Payment failed', 'woocommerce-elepay');
                    $order->update_status('failed', sprintf(__('ELEPAY Payment Failed: %s', 'woocommerce-elepay'), $reason));
                }
            }
        }

        status_header(200);
        wp_send_json_success(array('status' => 'acknowledged', 'event' => $event));
        exit;
    }

    /**
     * Fallback verification on thank-you page
     */
    public function check_order_status_on_return($order_id) {
        $order = wc_get_order($order_id);
        if ($order && !$order->is_paid()) {
            $reference = $order->get_meta('_elepay_checkout_reference');
            if ($reference) {
                try {
                    $status = $this->get_api()->get_checkout_status($reference);
                    if (isset($status['status']) && $status['status'] === 'paid') {
                        $order->payment_complete($reference);
                        $order->add_order_note(sprintf(__('Payment confirmed upon return from ELEPAY. Reference: %s', 'woocommerce-elepay'), $reference));
                    }
                } catch (Exception $e) {
                    // Non-fatal, webhook will reconcile
                }
            }
        }
    }
}
