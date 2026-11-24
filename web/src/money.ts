/** Formats integer minor units without floating point. */
export function formatMoney(minor: number, currency = "GHS"): string {
  if(!Number.isSafeInteger(minor))return "Unavailable";
  const negative = minor < 0;
  const abs = Math.abs(Math.trunc(minor));
  const major = Math.floor(abs / 100).toLocaleString("en-GH");
  const cents = String(abs % 100).padStart(2, "0");
  const symbol = currency === "GHS" ? "GH₵" : currency === "USD" ? "US$" : currency + " ";
  return `${negative ? "-" : ""}${symbol}${major}.${cents}`;
}

/** Parses a user-entered cedi amount ("12", "12.5", "12.50") into pesewas, or null if invalid. */
export function parseMoney(input: string): number | null {
  const raw=input.trim();
  if(raw.includes(",")&&!/^\d{1,3}(,\d{3})+(\.\d{1,2})?$/.test(raw))return null;
  const trimmed = raw.replace(/,/g, "");
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(trimmed)) return null;
  const [whole, frac = ""] = trimmed.split(".");
  return Number(whole) * 100 + Number(frac.padEnd(2, "0"));
}

/** Normalizes a Ghana phone number to 233XXXXXXXXX, matching the server's rule. */
export function normalizeGhanaPhone(input: string): string | null {
  if(!/^\+?[\d\s()-]+$/.test(input.trim()))return null;
  let digits = input.replace(/\D/g, "");
  if (digits.startsWith("0") && digits.length === 10) digits = "233" + digits.slice(1);
  return /^233\d{9}$/.test(digits) ? digits : null;
}
