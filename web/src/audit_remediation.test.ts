import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";
import { formatSplitReceipt, safeRedirectUrl } from "./Checkout";
import { formatDualControlOutcome } from "./Ops";
import { sanitizeCsvCell, transactionStatusClass, decodePendingTransfer, confirmationExpired, statementWindow, searchWalletHistory } from "./Wallet";
import { safeReturnPath } from "./auth";

describe("Audit Remediation Tests", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  describe("api.ts non-JSON and error handling", () => {
    it("throws ApiError instead of SyntaxError when response is non-JSON HTML", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockImplementation(() =>
          Promise.resolve(
            new Response("<html><body>502 Bad Gateway</body></html>", {
              status: 502,
              statusText: "Bad Gateway",
              headers: { "Content-Type": "text/html" },
            })
          )
        )
      );

      await expect(api("/v1/wallet", { anonymous: true })).rejects.toMatchObject({
        name: "Error",
        status: 502,
        code: "invalid_response",
        message: "Bad Gateway",
      });

      try {
        await api("/v1/wallet", { anonymous: true });
      } catch (e) {
        expect(e).toBeInstanceOf(ApiError);
      }
    });

    it("preserves FEATURE_NOT_ENABLED code on 404 JSON response", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({ code: "FEATURE_NOT_ENABLED", detail: "This module is disabled in Stage 1" }),
            { status: 404, statusText: "Not Found", headers: { "Content-Type": "application/json" } }
          )
        )
      );

      await expect(api("/v1/wallet", { anonymous: true })).rejects.toMatchObject({
        status: 404,
        code: "FEATURE_NOT_ENABLED",
        message: "This module is disabled in Stage 1",
      });
    });
  });

  describe("Checkout.tsx redirectUrl validation and receipt formatting", () => {
    it("accepts only https: and http: redirect URLs and rejects javascript:/data: schemes", () => {
      expect(safeRedirectUrl("https://3ds.example.com/challenge")).toBe("https://3ds.example.com/challenge");
      expect(safeRedirectUrl("http://localhost:8080/callback")).toBe("http://localhost:8080/callback");
      expect(safeRedirectUrl("http://external.example/pay")).toBeNull();
      expect(safeRedirectUrl("https://user:password@example.com/pay")).toBeNull();
      expect(safeRedirectUrl("javascript:alert(1)")).toBeNull();
      expect(safeRedirectUrl("data:text/html,<script>alert(1)</script>")).toBeNull();
      expect(safeRedirectUrl("vbscript:msgbox(1)")).toBeNull();
      expect(safeRedirectUrl("")).toBeNull();
      expect(safeRedirectUrl(null)).toBeNull();
    });

    it("formats split receipt without false GRA e-VAT compliance claim", () => {
      const receipt = formatSplitReceipt(
        {
          reference: "chk_test_123",
          collector: "Accra Fresh Market",
          description: "Groceries",
          status: "paid",
          lines: [{ merchant: "Vendor A", kind: "sale", description: "Produce", amount: 5000 }],
          subtotal: 5000,
          methods: [{ method: "momo", fee: 50, total: 5050 }],
          expiresAt: "2026-11-24T12:00:00Z",
          paidAt: "2026-11-24T01:00:00Z",
        },
        50,
        5050
      );

      expect(receipt).toContain("chk_test_123");
      expect(receipt).toContain("GH₵50.50");
      expect(receipt).not.toMatch(/GRA\s+e-VAT/i);
      expect(receipt).not.toContain("Ghana Revenue Authority");
      expect(receipt).not.toContain("Authorized by Bank of Ghana");
    });
  });

  it("rejects external API paths before fetching", async () => {
    const fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock);
    for (const path of ["https://other.example/api", "//other.example/api", "/\\\\other.example/api"]) {
      await expect(api(path, { anonymous: true })).rejects.toMatchObject({ code: "invalid_api_path" });
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("accepts internal callback routes only", () => {
    expect(safeReturnPath("/pay/abc?x=1")).toBe("/pay/abc?x=1");
    expect(safeReturnPath("//other.example")).toBe("/");
    expect(safeReturnPath("/\\\\other.example")).toBe("/");
    expect(safeReturnPath("https://other.example")).toBe("/");
    expect(safeReturnPath("/app/callback")).toBe("/");
    expect(safeReturnPath("/a/..//other.example")).toBe("/");
  });

  it("does not label uncertain or failed wallet operations as completed", () => {
    expect(transactionStatusClass("succeeded")).toBe("active");
    expect(transactionStatusClass("failed")).toBe("failed");
    expect(transactionStatusClass("unknown")).toBe("pending");
  });

  it("preserves the exact transfer recovery key and amount", () => {
    const p = { key: "idem-123", confirmationId: "11111111-1111-1111-1111-111111111111", amount: 5000 };
    expect(decodePendingTransfer(JSON.stringify(p))).toEqual(p);
    expect(decodePendingTransfer(null)).toBeNull();
    expect(() => decodePendingTransfer(JSON.stringify({...p, amount: -5}))).toThrow();
    expect(() => decodePendingTransfer("{")).toThrow();
  });
  it("rejects expired or malformed confirmation timestamps", () => {
    const now = Date.parse("2026-11-24T12:00:00Z");
    expect(confirmationExpired("2026-11-24T12:00:01Z", now)).toBe(false);
    expect(confirmationExpired("2026-11-24T12:00:00Z", now)).toBe(true);
    expect(confirmationExpired("invalid", now)).toBe(true);
  });
  it("uses an inclusive Ghana calendar range of at most 31 days", () => {
    expect(statementWindow("2026-11-01", "2026-11-30")).toEqual({from:"2026-11-01T00:00:00.000Z",to:"2026-12-01T00:00:00.000Z"});
    expect(statementWindow("2026-10-01", "2026-11-01")).toBeNull();
    expect(statementWindow("2026-02-30", "2026-03-01")).toBeNull();
  });
  it("filters history by text and status without changing the underlying records", () => {
    const rows = [{reference:"TOP-1",kind:"topup",amount:100,status:"completed",counterparty:"Ama",createdAt:"2026-11-24"},{reference:"PAY-2",kind:"payout",amount:200,status:"returned",counterparty:null,createdAt:"2026-11-24"}];
    expect(searchWalletHistory(rows,"ama","active")).toEqual([rows[0]]);
    expect(searchWalletHistory(rows,"","failed")).toEqual([rows[1]]);
    expect(rows).toHaveLength(2);
    expect(sanitizeCsvCell("  =1+1")).toContain("'  =1+1");
  });

  describe("Ops.tsx dual-control proposal handling", () => {
    it("formats pending_approval and PENDING responses as dual-control notices", () => {
      expect(
        formatDualControlOutcome(
          { code: "pending_approval", detail: "A different operations administrator must approve proposal p-123" },
          "Dispute resolved"
        )
      ).toBe("Pending dual-control approval: A different operations administrator must approve proposal p-123");

      expect(
        formatDualControlOutcome({ status: "PENDING" }, "Dispute resolved")
      ).toBe("Pending dual-control approval: Awaiting second operator approval in Maker-Checker.");

      expect(
        formatDualControlOutcome({ id: "d-1", status: "merchant_won" }, "Dispute resolved")
      ).toBe("Dispute resolved");
    });
  });

  describe("Wallet.tsx CSV formula injection sanitization", () => {
    it("prefixes dangerous formula characters with single quote and escapes double quotes", () => {
      expect(sanitizeCsvCell("=CMD|' /C calc'!A0")).toBe(`"'=CMD|' /C calc'!A0"`);
      expect(sanitizeCsvCell("+1234")).toBe(`"'+1234"`);
      expect(sanitizeCsvCell("-500")).toBe(`"'-500"`);
      expect(sanitizeCsvCell("@SUM(A1:A2)")).toBe(`"'@SUM(A1:A2)"`);
      expect(sanitizeCsvCell("\t=1+1")).toBe(`"'\t=1+1"`);
      expect(sanitizeCsvCell("\r=1+1")).toBe(`"'\r=1+1"`);
      expect(sanitizeCsvCell('Ama "VIP" Mensah')).toBe(`"Ama ""VIP"" Mensah"`);
      expect(sanitizeCsvCell(null)).toBe(`""`);
      expect(sanitizeCsvCell(1250)).toBe(`"1250"`);
    });
  });
});
