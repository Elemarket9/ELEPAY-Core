<?php
/**
 * ELEPAY API Client wrapper for WooCommerce
 * Integrates directly with ELEPAY Core using HMAC-SHA256 v2 request signing
 * and direct Ghanaian payment network rails (MTN MoMo, Telecel Cash, AT Money, GhIPSS GhQR, Direct Card).
 */

if (!defined('ABSPATH')) {
    exit;
}

class WC_Elepay_API {

    private $api_key;
    private $signing_secret;
    private $base_url;
    private $merchant_id;

    public function __construct($api_key, $signing_secret = '', $base_url = 'https://api.elepay.com', $merchant_id = '') {
        $this->api_key = trim($api_key);
        $this->signing_secret = trim($signing_secret);
        $this->base_url = rtrim($base_url, '/');
        $this->merchant_id = trim($merchant_id);
    }

    /**
     * Compute Canonical String for HMAC-SHA256 v2 Request Signing
     * Format: elepay-request:v2:{len}:{timestamp}:{len}:{method}:{len}:{path}:{len}:{query}:{len}:{nonce}:{len}:{idempotencyKey}:{len}:{body}
     *
     * @param string $timestamp
     * @param string $method
     * @param string $path
     * @param string $query
     * @param string $nonce
     * @param string $idempotency_key
     * @param string $body
     * @return string
     */
    public static function canonical_v2($timestamp, $method, $path, $query, $nonce, $idempotency_key, $body) {
        $fields = array($timestamp, strtoupper($method), $path, $query, $nonce, $idempotency_key, $body);
        $canonical = 'elepay-request:v2';
        foreach ($fields as $field) {
            $val = ($field === null) ? '' : (string) $field;
            $canonical .= ':' . strlen($val) . ':' . $val;
        }
        return $canonical;
    }

    /**
     * Compute HMAC-SHA256 v2 signature
     *
     * @param string $secret
     * @param string $timestamp
     * @param string $method
     * @param string $path
     * @param string $query
     * @param string $nonce
     * @param string $idempotency_key
     * @param string $body
     * @return string
     */
    public static function compute_signature_v2($secret, $timestamp, $method, $path, $query, $nonce, $idempotency_key, $body) {
        $canonical = self::canonical_v2($timestamp, $method, $path, $query, $nonce, $idempotency_key, $body);
        return hash_hmac('sha256', $canonical, $secret);
    }

    /**
     * Create Checkout Session on ELEPAY Core with v2 request signing
     *
     * @param array $payload
     * @return array
     * @throws Exception
     */
    public function create_checkout($payload) {
        // If merchant_id is set, target merchant checkout route; otherwise root checkouts route
        if (!empty($this->merchant_id)) {
            $url = $this->base_url . '/v1/merchants/' . urlencode($this->merchant_id) . '/checkouts';
        } else {
            $url = $this->base_url . '/v1/checkouts';
        }

        $idempotency_key = wp_generate_uuid4();
        $timestamp = (string) time();
        $nonce = wp_generate_password(32, false);
        $body_json = wp_json_encode($payload);

        $path = parse_url($url, PHP_URL_PATH);
        $query = parse_url($url, PHP_URL_QUERY);
        if (!$query) {
            $query = '';
        }

        $headers = array(
            'Content-Type'               => 'application/json',
            'Accept'                     => 'application/json',
            'X-Elepay-Api-Key'           => $this->api_key,
            'Idempotency-Key'            => $idempotency_key,
            'X-Elepay-Timestamp'         => $timestamp,
            'X-Elepay-Nonce'             => $nonce,
            'X-Elepay-Signature-Version' => '2',
            'User-Agent'                 => 'ELEPAY-WooCommerce/' . WC_ELEPAY_VERSION . ' (WordPress/' . get_bloginfo('version') . ')',
        );

        if (!empty($this->signing_secret)) {
            $headers['X-Elepay-Signature'] = self::compute_signature_v2(
                $this->signing_secret,
                $timestamp,
                'POST',
                $path,
                $query,
                $nonce,
                $idempotency_key,
                $body_json
            );
        }

        $args = array(
            'method'      => 'POST',
            'timeout'     => 30,
            'redirection' => 5,
            'httpversion' => '1.1',
            'headers'     => $headers,
            'body'        => $body_json,
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
                'Accept'           => 'application/json',
                'X-Elepay-Api-Key' => $this->api_key,
                'User-Agent'       => 'ELEPAY-WooCommerce/' . WC_ELEPAY_VERSION . ' (WordPress/' . get_bloginfo('version') . ')',
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
     * Issue Refund on Checkout with HMAC-SHA256 v2 request signing
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

        $idempotency_key = wp_generate_uuid4();
        $timestamp = (string) time();
        $nonce = wp_generate_password(32, false);
        $body_json = wp_json_encode(array(
            'amount' => $amount_pesewas,
            'reason' => $reason,
        ));

        $path = parse_url($url, PHP_URL_PATH);
        $query = parse_url($url, PHP_URL_QUERY);
        if (!$query) {
            $query = '';
        }

        $headers = array(
            'Content-Type'               => 'application/json',
            'Accept'                     => 'application/json',
            'X-Elepay-Api-Key'           => $this->api_key,
            'Idempotency-Key'            => $idempotency_key,
            'X-Elepay-Timestamp'         => $timestamp,
            'X-Elepay-Nonce'             => $nonce,
            'X-Elepay-Signature-Version' => '2',
            'User-Agent'                 => 'ELEPAY-WooCommerce/' . WC_ELEPAY_VERSION . ' (WordPress/' . get_bloginfo('version') . ')',
        );

        if (!empty($this->signing_secret)) {
            $headers['X-Elepay-Signature'] = self::compute_signature_v2(
                $this->signing_secret,
                $timestamp,
                'POST',
                $path,
                $query,
                $nonce,
                $idempotency_key,
                $body_json
            );
        }

        $args = array(
            'method'  => 'POST',
            'timeout' => 30,
            'headers' => $headers,
            'body'    => $body_json,
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
