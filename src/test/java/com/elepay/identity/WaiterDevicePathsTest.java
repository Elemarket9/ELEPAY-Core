package com.elepay.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaiterDevicePathsTest {
    private static final String M = "/v1/restaurant/waiter/2b7e1c3a-1111-4222-8333-444455556666";
    private static final String O = "9f0e1d2c-aaaa-4bbb-8ccc-ddddeeeeffff";

    @Test
    void waiterPhoneReachesOnlyTheWaiterFlow() {
        assertTrue(WaiterDevicePaths.allowed("GET", M + "/tables"));
        assertTrue(WaiterDevicePaths.allowed("GET", M + "/menu"));
        assertTrue(WaiterDevicePaths.allowed("GET", M + "/orders"));
        assertTrue(WaiterDevicePaths.allowed("GET", M + "/me"));
        assertTrue(WaiterDevicePaths.allowed("POST", M + "/orders"));
        assertTrue(WaiterDevicePaths.allowed("POST", M + "/orders/" + O + "/served"));
        assertTrue(WaiterDevicePaths.allowed("POST", M + "/orders/" + O + "/bill"));
        assertTrue(WaiterDevicePaths.allowed("POST", M + "/orders/" + O + "/close-table"));
    }

    @Test
    void everythingElseIsRefused() {
        assertFalse(WaiterDevicePaths.allowed("POST", M + "/orders/" + O + "/cashier"));
        assertFalse(WaiterDevicePaths.allowed("GET", M + "/bill-requests"));
        assertFalse(WaiterDevicePaths.allowed("PUT", M + "/settings"));
        assertFalse(WaiterDevicePaths.allowed("POST", M + "/devices/pairing-codes"));
        assertFalse(WaiterDevicePaths.allowed("DELETE", M + "/devices/" + O));
        assertFalse(WaiterDevicePaths.allowed("PUT", M + "/tables/" + O + "/branch"));
        assertFalse(WaiterDevicePaths.allowed("DELETE", M + "/orders"));
        assertFalse(WaiterDevicePaths.allowed("GET", "/v1/merchants"));
        assertFalse(WaiterDevicePaths.allowed("POST", "/v1/payments"));
        assertFalse(WaiterDevicePaths.allowed("GET", M + "/tables/../../payouts"));
        assertFalse(WaiterDevicePaths.allowed("GET", M + "/tables/"));
        assertFalse(WaiterDevicePaths.allowed(null, M + "/tables"));
    }
}
