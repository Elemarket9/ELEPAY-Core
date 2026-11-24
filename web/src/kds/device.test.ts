import { describe, expect, it } from "vitest";
import { canonicalV2, sign, kitchenClient } from "./device";

// Vectors computed with the server's RequestSigningFilter.computeSignatureV2, so the tablet and server agree.
describe("kitchen device request signing", () => {
  it("matches the server for a request with a query string and non-ASCII body", async () => {
    const payload = canonicalV2(["1700000000", "POST", "/v1/restaurant/kds/tickets/7e1f/reprint", "stationId=9a2b", "nonce-1", null,
      '{"from":"NEW","actionId":"Wáakye ✓"}']);
    expect(await sign("sec_abc123", payload)).toBe("686517900b61bd01a939281a4c1e178cb72595ddd6716e2c8560e8e0f015bd22");
  });

  it("matches the server for a request without query or body", async () => {
    const payload = canonicalV2(["1700000000", "POST", "/v1/restaurant/kds/tickets/7e1f/advance", null, "nonce-2", null, ""]);
    expect(await sign("sec_abc123", payload)).toBe("540de6e92ce9afc016ddce9371a7bb47d29ad1bdd187ea35c3eceb266d80535b");
  });
});

it("rejects unrelated or cross-origin kitchen device requests before fetching", async()=>{const client=kitchenClient({apiKey:"test",signingSecret:"test",deviceId:"d",merchantId:"m",merchantName:"m",stationId:null,stationName:null});for(const p of ["https://example.com","/v1/wallet","/v1/restaurant/kds/../../wallet"])await expect(client(p)).rejects.toMatchObject({code:"invalid_device_path"});});
