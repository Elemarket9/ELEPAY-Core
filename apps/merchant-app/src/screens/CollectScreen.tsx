import React, { useEffect, useState } from "react";
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  Share,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import QRCode from "react-native-qrcode-svg";
import {
  colors,
  ElepayClient,
  formatMoney,
  MerchantView,
  createAttemptKeys,
  parseMoney,
  radius,
  spacing,
} from "@elepay/mobile-core";

interface Props {
  client: ElepayClient;
  merchant: MerchantView;
}

export function CollectScreen({ client, merchant }: Props) {
  const [amount, setAmount] = useState("");
  const [payUrl, setPayUrl] = useState<string | null>(null);
  const [createdAmount, setCreatedAmount] = useState<number | null>(null);
  const [shopQrPayload, setShopQrPayload] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    client
      .call<{ payload?: string; qrPayload?: string }>(`/v1/merchants/${merchant.id}/qr`)
      .then((r) => setShopQrPayload(r.qrPayload ?? r.payload ?? null))
      .catch(() => setShopQrPayload(null));
  }, [client, merchant.id]);

  const attemptKeys = React.useRef(createAttemptKeys()).current;

  async function createPaymentLink() {
    if (busy) return;
    setError(null);
    const pesewas = parseMoney(amount);
    if (!pesewas || pesewas <= 0) {
      setError("Enter a valid amount, e.g. 25.00");
      return;
    }
    setBusy(true);
    try {
      const res = await client.call<{ payUrl: string }>(`/v1/merchants/${merchant.id}/checkouts`, {
        idempotencyKey: attemptKeys.keyFor(`link:${pesewas}`),
        body: { amount: pesewas },
      });
      attemptKeys.reset();
      setPayUrl(res.payUrl);
      setCreatedAmount(pesewas);
      setAmount("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function shareLink(url: string) {
    try {
      await Share.share({
        message: `Pay ${merchant.tradingName}${createdAmount ? ` (${formatMoney(createdAmount)})` : ""} via ELEPAY: ${url}`,
        url,
      });
    } catch {
      // User dismissed share sheet
    }
  }

  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
      {/* Instant Payment Link */}
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Request a Payment</Text>
        <Text style={styles.cardSub}>
          Generate a live checkout link for MTN MoMo, Telecel Cash, AT Money, GhQR, Card, or ELEPAY Wallet.
        </Text>

        <Text style={styles.label}>Amount (GH₵)</Text>
        <TextInput
          style={styles.amountInput}
          keyboardType="decimal-pad"
          value={amount}
          onChangeText={setAmount}
          placeholder="0.00"
          placeholderTextColor={colors.subtle}
        />

        {error && <Text style={styles.errorText}>{error}</Text>}

        <Pressable style={[styles.primaryBtn, busy && styles.disabledBtn]} disabled={busy} onPress={() => void createPaymentLink()}>
          {busy ? <ActivityIndicator color="#fff" /> : <Text style={styles.primaryBtnText}>Create Payment Link</Text>}
        </Pressable>

        {payUrl && (
          <View style={styles.linkBox}>
            <Text style={styles.linkBadge}>
              LINK READY{createdAmount ? ` · ${formatMoney(createdAmount)}` : ""}
            </Text>
            <View style={styles.qrWrap}>
              <QRCode value={payUrl} size={170} backgroundColor="#fff" color={colors.ink} />
            </View>
            <Text style={styles.linkUrl} selectable>{payUrl}</Text>
            <Pressable style={styles.shareBtn} onPress={() => void shareLink(payUrl)}>
              <Text style={styles.shareBtnText}>Share Link via WhatsApp / SMS</Text>
            </Pressable>
          </View>
        )}
      </View>

      {/* Static Shop GhQR */}
      <View style={[styles.card, { alignItems: "center" }]}>
        <Text style={styles.cardTitle}>Shop QR Code</Text>
        <Text style={styles.cardSub}>
          Merchant Code <Text style={{ fontWeight: "800", color: colors.ink }}>{merchant.merchantCode}</Text>
        </Text>
        {shopQrPayload ? (
          <View style={styles.qrWrap}>
            <QRCode value={shopQrPayload} size={200} backgroundColor="#fff" color={colors.ink} />
          </View>
        ) : (
          <Text style={styles.mutedText}>Shop QR unavailable</Text>
        )}
        <Text style={styles.mutedText}>Customers can scan with any Ghana bank or MoMo app.</Text>
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.bg },
  content: { padding: spacing.md, gap: spacing.md, paddingBottom: 32 },
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    borderColor: colors.line,
  },
  cardTitle: { fontSize: 17, fontWeight: "800", color: colors.ink },
  cardSub: { fontSize: 13, color: colors.muted, marginTop: 4, marginBottom: spacing.md },
  label: { fontSize: 12, fontWeight: "700", color: colors.inkSecondary, marginBottom: 6 },
  amountInput: {
    borderWidth: 1,
    borderColor: colors.lineStrong,
    borderRadius: radius.sm,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 22,
    fontWeight: "800",
    color: colors.ink,
    backgroundColor: colors.surfaceAlt,
  },
  errorText: { color: colors.danger, fontSize: 13, fontWeight: "600", marginTop: 8 },
  primaryBtn: {
    marginTop: spacing.md,
    backgroundColor: colors.green,
    paddingVertical: 14,
    borderRadius: radius.sm,
    alignItems: "center",
  },
  disabledBtn: { opacity: 0.6 },
  primaryBtnText: { color: "#fff", fontSize: 15, fontWeight: "700" },
  linkBox: {
    marginTop: spacing.lg,
    backgroundColor: colors.okBg,
    borderRadius: radius.md,
    padding: spacing.md,
    borderWidth: 1,
    borderColor: colors.okBorder,
    alignItems: "center",
  },
  linkBadge: { fontSize: 11, fontWeight: "800", color: colors.ok, letterSpacing: 0.6, marginBottom: 10 },
  qrWrap: {
    backgroundColor: "#fff",
    padding: 14,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.line,
    marginVertical: 8,
  },
  linkUrl: { fontSize: 12, color: colors.inkSecondary, textAlign: "center", marginVertical: 8 },
  shareBtn: {
    marginTop: 6,
    backgroundColor: colors.greenDeep,
    paddingVertical: 11,
    paddingHorizontal: 18,
    borderRadius: radius.sm,
  },
  shareBtnText: { color: "#fff", fontSize: 13, fontWeight: "700" },
  mutedText: { fontSize: 12, color: colors.muted, textAlign: "center", marginTop: 6 },
});
