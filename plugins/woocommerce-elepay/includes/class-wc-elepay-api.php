<?php
/**
 * ELEPAY API Client wrapper for WooCommerce
 */

if (!defined('ABSPATH')) {
    exit;
}

class WC_Elepay_API {

    private $api_key;
    private $base_url;

    public function __construct($api_key, $base_url = 'https://api.elepay.com') {
        $this->api_key = trim($api_key);
        $this->base_url = rtrim($base_url, '/');
    }

    /**
     * Create Checkout Session on ELEPAY Core
     *
     * @param array $payload
     * @return array
     * @throws Exception
     */
    public function create_checkout($payload) {
        $url = $this->base_url . '/v1/checkouts';
        $idempotency_key = wp_generate_uuid4();

        $args = array(
            'method'      => 'POST',
            'timeout'     => 30,
            'redirection' => 5,
            'httpversion' => '1.1',
            'headers'     => array(
                'Content-Type'    => 'application/json',
                'Accept'          => 'application/json',
                'X-Elepay-Api-Key' => $this->api_key,
                'Idempotency-Key' => $idempotency_key,
                'User-Agent'      => 'ELEPAY-WooCommerce/' . WC_ELEPAY_VERSION . ' (WordPress/' . get_bloginfo('version') . ')',
            ),
            'body'        => wp_json_encode($payload),
        );

        $response = wp_remote_post($url, $args);

        if (is_wp_error($response)) {
            throw new Exception($response->get_error_message());
        }

        $status_code = wp_remote_retrieve_response_code($response);
        $body = json_decode(wp_remote_retrieve_body($response), true);

        if ($status_code < 200 || $status_code >= 300) {
            $msg = isset($body['detail']) ? $body['detail'] : (isset($body['message']) ? $body['message'] : 'Payment initiation failed');
            throw new Exception('ELEPAY API Error (' . $status_code . '): ' . $msg);
        }

        return $body;
    }

    /**
     * Query Checkout Status
     *
     * @param string $reference
     * @return array
     * @throws Exception
     */
    public function get_checkout_status($reference) {
        $url = $this->base_url . '/v1/public/checkouts/' . urlencode($reference) . '/status';

        $args = array(
            'method'  => 'GET',
            'timeout' => 15,
            'headers' => array(
                'Accept'          => 'application/json',
                'X-Elepay-Api-Key' => $this->api_key,
            ),
        );

        $response = wp_remote_get($url, $args);

        if (is_wp_error($response)) {
            throw new Exception($response->get_error_message());
        }

        $body = json_decode(wp_remote_retrieve_body($response), true);
        return $body;
    }

    /**
     * Issue Refund on Checkout
     *
     * @param string $merchant_id
     * @param string $checkout_ref
     * @param int $line
     * @param int $amount_pesewas
     * @param string $reason
     * @return array
     * @throws Exception
     */
    public function create_refund($merchant_id, $checkout_ref, $line, $amount_pesewas, $reason) {
        $url = $this->base_url . '/v1/merchants/' . urlencode($merchant_id) . '/checkouts/' . urlencode($checkout_ref) . '/allocations/' . intval($line) . '/refunds';

        $args = array(
            'method'  => 'POST',
            'timeout' => 30,
            'headers' => array(
                'Content-Type'    => 'application/json',
                'Accept'          => 'application/json',
                'X-Elepay-Api-Key' => $this->api_key,
                'Idempotency-Key' => wp_generate_uuid4(),
            ),
            'body'    => wp_json_encode(array(
                'amount' => $amount_pesewas,
                'reason' => $reason,
            )),
        );

        $response = wp_remote_post($url, $args);

        if (is_wp_error($response)) {
            throw new Exception($response->get_error_message());
        }

        $code = wp_remote_retrieve_response_code($response);
        if ($code < 200 || $code >= 300) {
            $body = json_decode(wp_remote_retrieve_body($response), true);
            throw new Exception('Refund failed: ' . (isset($body['detail']) ? $body['detail'] : 'Unknown error'));
        }

        return json_decode(wp_remote_retrieve_body($response), true);
    }

    /**
     * Validate Webhook Signature Header
     *
     * Header format: Elepay-Signature: t=1732250000,v1=abcdef...
     * Signed string: timestamp . "." . raw_payload
     *
     * @param string $raw_payload
     * @param string $signature_header
     * @param string $secret
     * @param int $tolerance_seconds
     * @return bool
     */
    public static function verify_webhook_signature($raw_payload, $signature_header, $secret, $tolerance_seconds = 300) {
        if (empty($signature_header) || empty($secret) || empty($raw_payload)) {
            return false;
        }

        $parts = explode(',', $signature_header);
        $timestamp = null;
        $signature = null;

        foreach ($parts as $part) {
            $kv = explode('=', trim($part), 2);
            if (count($kv) === 2) {
                if ($kv[0] === 't') {
                    $timestamp = intval($kv[1]);
                } elseif ($kv[0] === 'v1') {
                    $signature = trim($kv[1]);
                }
            }
        }

        if (!$timestamp || !$signature) {
            return false;
        }

        // Prevent replay attacks by checking timestamp drift
        $current_time = time();
        if (abs($current_time - $timestamp) > $tolerance_seconds) {
            return false;
        }

        // Compute expected HMAC-SHA256
        $signed_payload = $timestamp . '.' . $raw_payload;
        $expected = hash_hmac('sha256', $signed_payload, $secret);

        return hash_equals(strtolower($expected), strtolower($signature));
    }
}
