<?php
/**
 * Standalone regression tests for ELEPAY's optional WooCommerce gateway.
 * All external calls are in-memory fixtures; no financial rails are contacted.
 */
error_reporting(E_ALL);
define('ABSPATH', __DIR__);
define('WC_ELEPAY_VERSION', 'test');
class WC_Payment_Gateway {
    public function get_return_url($order) { return 'https://merchant.example/received'; }
}
class WP_Error {
    public $code;
    public $message;
    public function __construct($code, $message) { $this->code = $code; $this->message = $message; }
}
function __($message, $domain = null) { return $message; }
function get_bloginfo($field) { return 'Test WordPress'; }
function wc_format_decimal($amount, $decimals = 2) { return number_format((float) $amount, $decimals, '.', ''); }
function wp_json_encode($data) { return json_encode($data); }
function wp_generate_uuid4() {
    $GLOBALS['uuid_count'] = ($GLOBALS['uuid_count'] ?? 0) + 1;
    return 'aaaaaaaa-bbbb-4ccc-8ddd-' . sprintf('%012d', $GLOBALS['uuid_count']);
}
function wp_generate_password($length = 12, $special_chars = true) { return str_repeat('n', $length); }
function is_wp_error($value) { return $value instanceof WP_Error; }
function wp_remote_retrieve_response_code($response) { return $response['code']; }
function wp_remote_retrieve_body($response) { return json_encode($response['body']); }
function wp_safe_remote_post($url, $args) { return fixture_http('POST', $url, $args); }
function wp_safe_remote_get($url, $args) { return fixture_http('GET', $url, $args); }
function fixture_http($method, $url, $args) {
    $GLOBALS['calls'][] = array('method' => $method, 'url' => $url, 'args' => $args);
    if (empty($GLOBALS['responses'])) { throw new Exception('Unexpected HTTP request ' . $method . ' ' . $url); }
    return array_shift($GLOBALS['responses']);
}
function response($body, $code = 200) { return array('body' => $body, 'code' => $code); }
function wc_add_notice($message, $type) { $GLOBALS['notices'][] = $message; }
function wc_get_order($id) { return $GLOBALS['orders'][$id] ?? null; }
function wc_get_orders($args) {
    foreach ($GLOBALS['orders'] as $order) {
        if ($order->get_meta($args['meta_key']) === $args['meta_value']) { return array($order); }
    }
    return array();
}
function same($expected, $actual, $label) {
    if ($expected !== $actual) {
        throw new Exception('Assertion failed: ' . $label . ' expected ' . var_export($expected, true)
            . ' got ' . var_export($actual, true));
    }
}
function yes($value, $label) { same(true, (bool) $value, $label); }
function fails($callback, $label) {
    try { $callback(); } catch (Exception $e) { return; }
    throw new Exception('Expected failure: ' . $label);
}
class TestOrder {
    public $meta = array();
    public $paid = false;
    public $notes = array();
    public function get_id() { return 101; }
    public function get_order_number() { return '101'; }
    public function get_payment_method() { return 'elepay'; }
    public function get_currency() { return 'GHS'; }
    public function get_total() { return '125.00'; }
    public function get_meta($key) { return $this->meta[$key] ?? ''; }
    public function update_meta_data($key, $value) { $this->meta[$key] = $value; }
    public function delete_meta_data($key) { unset($this->meta[$key]); }
    public function save() {}
    public function is_paid() { return $this->paid; }
    public function payment_complete($reference) { $this->paid = true; }
    public function add_order_note($note) { $this->notes[] = $note; }
}

require dirname(__DIR__) . '/includes/class-wc-elepay-api.php';
require dirname(__DIR__) . '/includes/class-wc-gateway-elepay.php';

$GLOBALS['calls'] = array();
$GLOBALS['responses'] = array();
$base = 'https://api.elepay.example';
$merchant = '11111111-2222-4333-8444-555555555555';
$client = new WC_Elepay_API('ek_live_test_fixture', 'signing-fixture-not-live', $base, $merchant);
fails(function () use ($base, $merchant) {
    (new WC_Elepay_API('api-key', '', $base, $merchant))->create_checkout(array('amount' => 100), 'stable-key');
}, 'no request signing secret must fail closed');
fails(function () use ($merchant) {
    (new WC_Elepay_API('api-key', 'secret', 'http://127.0.0.1', $merchant))
        ->create_checkout(array('amount' => 100), 'stable-key');
}, 'unsafe API transport must fail closed');

$GLOBALS['responses'][] = response(array('reference' => 'CH-101', 'payUrl' => 'https://pay.elepay.example/CH-101', 'subtotal' => 12500));
$created = $client->create_checkout(array('amount' => 12500, 'orderReference' => 'wc_101'), 'fixed-checkout-key');
same('CH-101', $created['reference'], 'checkout reference');
$call = $GLOBALS['calls'][0];
same('POST', $call['method'], 'checkout POST');
yes(strpos($call['url'], '/v1/merchants/' . $merchant . '/checkouts') !== false, 'merchant route');
same('fixed-checkout-key', $call['args']['headers']['Idempotency-Key'], 'stable checkout idempotency key');
same(12500, json_decode($call['args']['body'], true)['amount'], 'contract amount in pesewas');
yes(isset($call['args']['headers']['X-Elepay-Signature']), 'signed request');

$GLOBALS['responses'][] = response(array('reference' => 'WRONG', 'status' => 'paid', 'subtotal' => 12500));
fails(function () use ($client) { $client->get_checkout_status('CH-101'); }, 'mismatched status reference');

$order = new TestOrder();
$GLOBALS['orders'] = array(101 => $order);
$gateway = (new ReflectionClass('WC_Gateway_Elepay'))->newInstanceWithoutConstructor();
$gateway->testmode = false;
$gateway->live_api_key = 'ek_live_test_fixture';
$gateway->live_signing_secret = 'signing-fixture-not-live';
$gateway->api_base_url = $base;
$gateway->merchant_id = $merchant;

$GLOBALS['responses'][] = response(array('reference' => 'CH-101', 'payUrl' => 'https://pay.elepay.example/CH-101', 'subtotal' => 12500));
$first = $gateway->process_payment(101);
same('success', $first['result'], 'checkout first result');
same('CH-101', $order->get_meta('_elepay_checkout_reference'), 'stored reference');
same(12500, $order->get_meta('_elepay_checkout_amount'), 'immutable initial amount');
$first_key = $order->get_meta('_elepay_checkout_idempotency_key');
yes($first_key !== '', 'stored retry key');
$posts_before = count(array_filter($GLOBALS['calls'], function ($call) { return $call['method'] === 'POST'; }));

$GLOBALS['responses'][] = response(array('reference' => 'CH-101', 'status' => 'processing', 'subtotal' => 12500));
$second = $gateway->process_payment(101);
same('success', $second['result'], 'repeat returns existing checkout');
same($first_key, $order->get_meta('_elepay_checkout_idempotency_key'), 'stable key after retry');
same($posts_before, count(array_filter($GLOBALS['calls'], function ($call) { return $call['method'] === 'POST'; })), 'no duplicate POST');
yes(!$order->is_paid(), 'unconfirmed checkout stays unpaid');

$GLOBALS['responses'][] = response(array('reference' => 'CH-101', 'status' => 'paid', 'subtotal' => 9999));
$gateway->check_order_status_on_return(101);
yes(!$order->is_paid(), 'amount mismatch cannot mark paid');
$GLOBALS['responses'][] = response(array('reference' => 'CH-101', 'status' => 'paid', 'subtotal' => 12500));
$gateway->check_order_status_on_return(101);
yes($order->is_paid(), 'matching authoritative status marks paid');

$checkout = array('reference' => 'CH-101', 'status' => 'paid', 'lines' => array(
    array('line' => 0, 'amount' => 12500, 'refunded' => 0),
    array('line' => 1, 'amount' => 1, 'refunded' => 0),
));
$GLOBALS['responses'][] = response($checkout);
$pre = count($GLOBALS['calls']);
$ambiguous = $gateway->process_refund(101, '12.00', 'customer requested');
yes($ambiguous instanceof WP_Error, 'multiple allocations require manual allocation choice');
same($pre + 1, count($GLOBALS['calls']), 'ambiguous refund never posts money');

$checkout['lines'] = array(array('line' => 0, 'amount' => 12500, 'refunded' => 0));
$GLOBALS['responses'][] = response($checkout);
$GLOBALS['responses'][] = response(array(
    'reference' => 'RF-101', 'checkout' => 'CH-101', 'line' => 0,
    'amount' => 1200, 'status' => 'completed',
));
$refunded = $gateway->process_refund(101, '12.00', 'customer requested');
same(true, $refunded, 'confirmed completed refund');
$last = end($GLOBALS['calls']);
yes(strpos($last['url'], '/allocations/0/refunds') !== false, 'refund uses actual API allocation line zero');
same(1200, json_decode($last['args']['body'], true)['amount'], 'refund amount is integer pesewas');
same('', $order->get_meta('_elepay_refund_pending_key'), 'completed refund clears retry key');

$GLOBALS['responses'][] = response($checkout);
$GLOBALS['responses'][] = response(array(
    'reference' => 'RF-102', 'checkout' => 'CH-101', 'line' => 0,
    'amount' => 500, 'status' => 'pending',
));
$pending = $gateway->process_refund(101, '5.00', 'return in progress');
yes($pending instanceof WP_Error, 'pending external rail refund must not be reported complete');
yes($order->get_meta('_elepay_refund_pending_key') !== '', 'pending refund retains retry key');

$timestamp = time();
$raw = '{"event":"checkout.paid","data":{"reference":"CH-101"}}';
$sig = hash_hmac('sha256', $timestamp . '.' . $raw, 'webhook-fixture');
yes(WC_Elepay_API::verify_webhook_signature($raw, 't=' . $timestamp . ',v1=' . $sig, 'webhook-fixture'), 'good signed webhook');
yes(!WC_Elepay_API::verify_webhook_signature($raw . 'x', 't=' . $timestamp . ',v1=' . $sig, 'webhook-fixture'), 'tampered webhook fails');
yes(!WC_Elepay_API::verify_webhook_signature($raw, 't=' . ($timestamp - 600) . ',v1=' . $sig, 'webhook-fixture'), 'stale webhook fails');
echo "ELEPAY WooCommerce contract/security regression tests passed\n";
