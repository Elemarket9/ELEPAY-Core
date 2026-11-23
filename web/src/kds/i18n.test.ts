import { describe, expect, it } from "vitest";
import { KDS_LANGUAGES, kdsText } from "./i18n";
describe("KDS menu language support", () => {
  it("has nine distinct selectable language entries", () => {
    expect(new Set(KDS_LANGUAGES.map(([id]) => id)).size).toBe(9);
  });
  it("has a non-empty translation for all critical kitchen actions", () => {
    for (const [id] of KDS_LANGUAGES) {
      for (const word of ["orders", "ready", "start", "served", "offline"] as const)
        expect(kdsText(id, word).trim().length).toBeGreaterThan(0);
    }
  });
});
