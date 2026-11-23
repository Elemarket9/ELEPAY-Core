import { describe, expect, it } from "vitest";
import { encodeCode128B } from "./Barcode128";

describe("Code 128 B payment barcodes", () => {
  it("uses Start B, weighted checksum and the 13-module stop", () => {
    const bits = encodeCode128B("A");
    expect(bits).toBe("11010010000" + "10100011000" + "10001011000" + "1100011101011");
    expect(bits.length).toBe(46);
  });
  it("rejects invalid characters instead of producing unscannable bars", () => {
    expect(() => encodeCode128B("")).toThrow();
    expect(() => encodeCode128B("ABC\n")).toThrow();
    expect(() => encodeCode128B("é")).toThrow();
  });
  it("produces stable scannable payment payload bits", () => {
    const input = "ELEPAY-PAY:AB123-2026";
    expect(encodeCode128B(input)).toBe(encodeCode128B(input));
    expect(encodeCode128B(input).length).toBe((input.length + 2) * 11 + 13);
  });
});
