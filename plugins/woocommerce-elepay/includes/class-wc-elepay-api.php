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

    /** Fail closed on missing or unsafe production gateway configuration. */
    private function require_configuration() {
        if (!$this->api_key || !$this->signing_secret || !$this->merchant_id) {
            throw new Exception('ELEPAY API key, signing secret and merchant ID are required.');
        }
        if (!filter_var($this->base_url, FILTER_VALIDATE_URL)
                || parse_url($this->base_url, PHP_URL_SCHEME) !== 'https'
                || parse_url($this->base_url, PHP_URL_USER) !== null
                || parse_url($this->base_url, PHP_URL_PASS) !== null
                || parse_url($this->base_url, PHP_URL_QUERY) !== null
                || parse_url($this->base_url, PHP_URL_FRAGMENT) !== null) {
            throw new Exception('ELEPAY API base URL must be an HTTPS address without credentials or query parameters.');
        }
    }

    /** Validate an ELEPAY API response before using it to change financial state. */
    private static function decode_response($response, $operation) {
        if (is_wp_error($response)) {
            throw new Exception('ELEPAY ' . $operation . ' could not reach the API.');
        }
        $code = wp_remote_retrieve_response_code($response);
        $body = json_decode(wp_remote_retrieve_body($response), true);
        if ($code < 200 || $code >= 300 || !is_array($body)) {
            throw new Exception('ELEPAY ' . $operation . ' failed or returned an invalid response (HTTP ' . intval($code) . ').');
        }
        return $body;
    }

    /** Fetch a checkout through a merchant-authorized API, not a shopper-supplied callback. */
    public function get_checkout_details($reference) {
        $this->require_configuration();
        $url = $this->base_url . '/v1/merchants/' . rawurlencode($this->merchant_id)
            . '/checkouts/' . rawurlencode($reference);
        $response = wp_remote_get($url, array(
            'timeout' => 15, 'redirection' => 0,
            'headers' => array('Accept' => 'application/json', 'X-Elepay-Api-Key' => $this->api_key),
        ));
        $body = self::decode_response($response, 'checkout lookup');
        if (!isset($body['reference']) || !is_string($body['reference'])
                || !hash_equals((string) $reference, $body['reference'])) {
            throw new Exception('ELEPAY returned a checkout with a mismatched reference.');
        }
        return $body;
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
    public function create_checkout($payload, $idempotency_key) {
        $this->require_configuration();
        if (!is_string($idempotency_key) || $idempotency_key === '') {
            throw new Exception('A stable checkout idempotency key is required.');
        }
        $url = $this->base_url . '/v1/merchants/' . rawurlencode($this->merchant_id) . '/checkouts';
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

        $args = array(
            'method'      => 'POST',
            'timeout'     => 30,
            'redirection' => 0,
            'httpversion' => '1.1',
            'headers'     => $headers,
            'body'        => $body_json,
        );

        $response = self::decode_response(wp_remote_post($url, $args), 'checkout creation');
        if (empty($response['reference']) || !is_string($response['reference'])
                || empty($response['payUrl']) || !is_string($response['payUrl'])
                || !filter_var($response['payUrl'], FILTER_VALIDATE_URL)
                || parse_url($response['payUrl'], PHP_URL_SCHEME) !== 'https') {
            throw new Exception('ELEPAY checkout response has no valid reference or secure payment URL.');
        }
        return $response;
    }

    /**
     * Query Checkout Status
     *
     * @param string $reference
     * @return array
     * @throws Exception
     */
    public function get_checkout_status($reference) {
        $this->require_configuration();
        $url = $this->base_url . '/v1/public/checkouts/' . rawurlencode($reference) . '/status';
        $response = wp_remote_get($url, array(
            'timeout' => 15, 'redirection' => 0,
            'headers' => array('Accept' => 'application/json', 'X-Elepay-Api-Key' => $this->api_key),
        ));
        $body = self::decode_response($response, 'payment status lookup');
        if (empty($body['reference']) || !is_string($body['reference'])
                || !hash_equals((string) $reference, $body['reference'])
                || !isset($body['status'], $body['subtotal'])
                || !is_string($body['status']) || !is_numeric($body['subtotal'])) {
            throw new Exception('ELEPAY payment status response is incomplete or mismatched.');
        }
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
    public function create_refund($merchant_id, $checkout_ref, $line, $amount_pesewas, $reason, $idempotency_key) {
        $this->require_configuration();
        if (!hash_equals($this->merchant_id, (string) $merchant_id)
                || !is_int($line) || $line < 0
                || !is_int($amount_pesewas) || $amount_pesewas <= 0
                || !is_string($reason) || trim($reason) === ''
                || !is_string($idempotency_key) || $idempotency_key === '') {
            throw new Exception('ELEPAY refund has invalid merchant, allocation, amount, reason or idempotency key.');
        }
        $url = $this->base_url . '/v1/merchants/' . rawurlencode($merchant_id)
            . '/checkouts/' . rawurlencode($checkout_ref)
            . '/allocations/' . $line . '/refunds';
        $timestamp = (string) time();
        $nonce = wp_generate_password(32, false);
        $body_json = wp_json_encode(array('amount' => $amount_pesewas, 'reason' => $reason));
        $path = parse_url($url, PHP_URL_PATH);
        $query = parse_url($url, PHP_URL_QUERY) ?: '';
        $headers = array(
            'Content-Type' => 'application/json',
            'Accept' => 'application/json',
            'X-Elepay-Api-Key' => $this->api_key,
            'Idempotency-Key' => $idempotency_key,
            'X-Elepay-Timestamp' => $timestamp,
            'X-Elepay-Nonce' => $nonce,
            'X-Elepay-Signature-Version' => '2',
            'X-Elepay-Signature' => self::compute_signature_v2(
                $this->signing_secret, $timestamp, 'POST', $path, $query,
                $nonce, $idempotency_key, $body_json
            ),
        );
        $body = self::decode_response(wp_remote_post($url, array(
            'method' => 'POST', 'timeout' => 30, 'redirection' => 0,
            'headers' => $headers, 'body' => $body_json,
        )), 'refund');
        if (empty($body['reference']) || !isset($body['line'], $body['amount'])
                || !hash_equals((string) $checkout_ref, (string) $body['checkout'])
                || (int) $body['line'] !== $line
                || (int) $body['amount'] !== $amount_pesewas) {
            throw new Exception('ELEPAY refund confirmation does not match the requested allocation or amount.');
        }
        return $body;
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

        if (!$timestamp || !$signature || !ctype_xdigit($signature) || strlen($signature) !== 64) {
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
