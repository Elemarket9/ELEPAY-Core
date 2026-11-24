import { describe, expect, it } from "vitest";
import { formatMoney, normalizeGhanaPhone, parseMoney } from "./money";

describe("money", () => {
  it("formats pesewas exactly", () => {
    expect(formatMoney(15000)).toBe("GH₵150.00");
    expect(formatMoney(5)).toBe("GH₵0.05");
    expect(formatMoney(4096, "USD")).toBe("US$40.96");
  });
  it("parses cedi input without floating point", () => {
    expect(parseMoney("80")).toBe(8000);
    expect(parseMoney("0.1")).toBe(10);
    expect(parseMoney("19.99")).toBe(1999);
    expect(parseMoney("1.999")).toBeNull();
    expect(parseMoney("abc")).toBeNull();
  });
  it("treats local and +233 forms as the same number", () => {
    expect(normalizeGhanaPhone("024 123 4567")).toBe("233241234567");
    expect(normalizeGhanaPhone("+233 24 123 4567")).toBe("233241234567");
    expect(normalizeGhanaPhone("12345")).toBeNull();
  });
});

it("rejects invalid monetary values and malformed separators",()=>{expect(formatMoney(NaN)).toBe("Unavailable");expect(formatMoney(1.5)).toBe("Unavailable");expect(parseMoney("1,000.50")).toBe(100050);expect(parseMoney("1,2")).toBeNull();expect(normalizeGhanaPhone("abc0241234567")).toBeNull();});
