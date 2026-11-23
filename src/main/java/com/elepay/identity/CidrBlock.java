package com.elepay.identity;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * An IPv4 or IPv6 address block such as {@code 196.201.0.0/16} or a single address. Parsing accepts only
 * literal addresses, never host names, so configuration can never trigger a DNS lookup.
 */
record CidrBlock(BigInteger network, BigInteger mask, int bytes) {

    static CidrBlock parse(String spec) {
        String s = spec.trim();
        int slash = s.indexOf('/');
        String addr = slash < 0 ? s : s.substring(0, slash);
        byte[] raw = literal(addr);
        if (raw == null) throw new IllegalArgumentException("Not an IP address literal: " + spec);
        int bits = raw.length * 8;
        int prefix = slash < 0 ? bits : Integer.parseInt(s.substring(slash + 1));
        if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("Bad prefix length: " + spec);
        BigInteger all = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        BigInteger mask = all.shiftRight(prefix).xor(all);
        return new CidrBlock(new BigInteger(1, raw).and(mask), mask, raw.length);
    }

    boolean contains(String address) {
        byte[] raw = literal(address);
        return raw != null && raw.length == bytes && new BigInteger(1, raw).and(mask).equals(network);
    }

    /** Parses an IP literal without DNS; returns null for anything else. */
    static byte[] literal(String address) {
        if (address == null || address.isBlank()) return null;
        String a = address.trim();
        if (a.startsWith("[") && a.endsWith("]")) a = a.substring(1, a.length() - 1);
        boolean ipv4 = a.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean ipv6 = a.indexOf(':') >= 0 && a.matches("[0-9A-Fa-f:.]+(%[A-Za-z0-9]+)?");
        if (!ipv4 && !ipv6) return null;
        try {
            return InetAddress.getByName(a).getAddress(); // literal only: no resolution is performed
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
