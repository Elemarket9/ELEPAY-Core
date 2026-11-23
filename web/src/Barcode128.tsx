import { useMemo } from "react";

/** Standard Code 128 module patterns; immutable symbol data (ISO/IEC 15417). */
const PATTERNS = [
  "11011001100", "11001101100", "11001100110", "10010011000", "10010001100", "10001001100", "10011001000", "10011000100", "10001100100", "11001001000", "11001000100", "11000100100", "10110011100", "10011011100", "10011001110", "10111001100", "10011101100", "10011100110", "11001110010", "11001011100", "11001001110", "11011100100", "11001110100", "11101101110", "11101001100", "11100101100", "11100100110", "11101100100", "11100110100", "11100110010", "11011011000", "11011000110", "11000110110", "10100011000", "10001011000", "10001000110", "10110001000", "10001101000", "10001100010", "11010001000", "11000101000", "11000100010", "10110111000", "10110001110", "10001101110", "10111011000", "10111000110", "10001110110", "11101110110", "11010001110", "11000101110", "11011101000", "11011100010", "11011101110", "11101011000", "11101000110", "11100010110", "11101101000", "11101100010", "11100011010", "11101111010", "11001000010", "11110001010", "10100110000", "10100001100", "10010110000", "10010000110", "10000101100", "10000100110", "10110010000", "10110000100", "10011010000", "10011000010", "10000110100", "10000110010", "11000010010", "11001010000", "11110111010", "11000010100", "10001111010", "10100111100", "10010111100", "10010011110", "10111100100", "10011110100", "10011110010", "11110100100", "11110010100", "11110010010", "11011011110", "11011110110", "11110110110", "10101111000", "10100011110", "10001011110", "10111101000", "10111100010", "11110101000", "11110100010", "10111011110", "10111101110", "11101011110", "11110101110", "11010000100", "11010010000", "11010011100", "1100011101011"
] as const;

/** Encode printable ASCII into Code 128 subset B with its modulo-103 check symbol. */
export function encodeCode128B(value: string): string {
  if (!value || value.length > 128) throw new Error("Barcode must have 1-128 characters");
  const codes: number[] = [104]; // Start B
  let checksum = 104;
  for (let i = 0; i < value.length; i++) {
    const ascii = value.charCodeAt(i);
    if (ascii < 32 || ascii > 126) throw new Error("Code 128 B supports printable ASCII only");
    const code = ascii - 32;
    codes.push(code);
    checksum += code * (i + 1);
  }
  codes.push(checksum % 103, 106); // Modulo-103 checksum and stop
  return codes.map((code) => PATTERNS[code]).join("");
}

function runs(bits: string) {
  const bars: { x: number; width: number }[] = [];
  let from = -1;
  for (let x = 0; x <= bits.length; x++) {
    if (bits[x] === "1" && from < 0) from = x;
    if (bits[x] !== "1" && from >= 0) { bars.push({ x: from, width: x - from }); from = -1; }
  }
  return bars;
}

/** Self-contained SVG: USB/Bluetooth scanner-compatible 1D payment-reference barcode. */
export function Code128Barcode({ value }: { value: string }) {
  const bits = useMemo(() => encodeCode128B(value), [value]);
  const quiet = 12;
  return (
    <svg className="payment-barcode" role="img" aria-label={`Code 128 payment reference ${value}`}
      viewBox={`0 0 ${bits.length + quiet * 2} 85`} preserveAspectRatio="xMidYMid meet">
      <rect x="0" y="0" width={bits.length + quiet * 2} height="85" fill="#fff" />
      {runs(bits).map((bar) => <rect key={bar.x} x={quiet + bar.x} y="4" width={bar.width} height="77" fill="#000" />)}
    </svg>
  );
}
