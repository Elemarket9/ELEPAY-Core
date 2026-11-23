export const KDS_LANGUAGES = [
  ["en", "English"], ["tw", "Akan Twi"], ["ga", "Ga"],
  ["ee", "Ewe"], ["ha", "Hausa"], ["fr", "Français"],
  ["pt", "Português"], ["es", "Español"], ["ar", "العربية"],
] as const;
export type KdsLanguage = (typeof KDS_LANGUAGES)[number][0];
export type KdsTerm = "orders" | "ready" | "start" | "served" | "offline" | "local" |
  "language" | "settings" | "reconnect" | "amount" | "pending" | "kitchen";
const WORDS: Record<KdsLanguage, Record<KdsTerm, string>> = {
  en: { orders:"Orders", ready:"Ready", start:"Start", served:"Served", offline:"Offline", local:"Local",
    language:"Language", settings:"Settings", reconnect:"Reconnect", amount:"Amount", pending:"Pending", kitchen:"Kitchen" },
  tw: { orders:"Akrataa", ready:"Ayɛ krado", start:"Fi ase", served:"Wɔde ama", offline:"Intanɛt nni hɔ", local:"Ɛha",
    language:"Kasa", settings:"Nhyehyɛe", reconnect:"San ka ho", amount:"Sika dodow", pending:"Ɛretwɛn", kitchen:"Aduannoa dan" },
  ga: { orders:"Order", ready:"Ehewalɛ", start:"Kɛ ji", served:"Etsɔɔ", offline:"Internet be", local:"Nɛɛ",
    language:"Wiemɔ", settings:"Nhyehyɛmɔ", reconnect:"Kɛ ekɛ", amount:"Sika", pending:"Ehewalɛ kɛ", kitchen:"Kuk" },
  ee: { orders:"Dɔwɔwɔ", ready:"Le klalo", start:"Dze egɔme", served:"Wotsɔe na", offline:"Internet mele o", local:"Afima",
    language:"Gbe", settings:"Ɖoɖowɔwɔ", reconnect:"Ga do ka", amount:"Ga", pending:"Le mɔ kpɔm", kitchen:"Nuɖaƒe" },
  ha: { orders:"Oda", ready:"A shirye", start:"Fara", served:"An kai", offline:"Babu intanet", local:"Na gida",
    language:"Harshe", settings:"Saituna", reconnect:"Sake haɗawa", amount:"Adadi", pending:"Ana jira", kitchen:"Kicin" },
  fr: { orders:"Commandes", ready:"Prêt", start:"Commencer", served:"Servi", offline:"Hors ligne", local:"Local",
    language:"Langue", settings:"Paramètres", reconnect:"Reconnecter", amount:"Montant", pending:"En attente", kitchen:"Cuisine" },
  pt: { orders:"Pedidos", ready:"Pronto", start:"Iniciar", served:"Servido", offline:"Sem rede", local:"Local",
    language:"Idioma", settings:"Configurações", reconnect:"Reconectar", amount:"Valor", pending:"Pendente", kitchen:"Cozinha" },
  es: { orders:"Pedidos", ready:"Listo", start:"Empezar", served:"Servido", offline:"Sin conexión", local:"Local",
    language:"Idioma", settings:"Ajustes", reconnect:"Reconectar", amount:"Importe", pending:"Pendiente", kitchen:"Cocina" },
  ar: { orders:"الطلبات", ready:"جاهز", start:"ابدأ", served:"تم التقديم", offline:"غير متصل", local:"محلي",
    language:"اللغة", settings:"الإعدادات", reconnect:"أعد الاتصال", amount:"المبلغ", pending:"قيد الانتظار", kitchen:"المطبخ" },
};
export function kdsText(locale: KdsLanguage, term: KdsTerm): string {
  return WORDS[locale]?.[term] ?? WORDS.en[term];
}
export function readKdsLocale(): KdsLanguage {
  try {
    const selected = localStorage.getItem("elepay.kds.lang") as KdsLanguage;
    return KDS_LANGUAGES.some(([id]) => id === selected) ? selected : "en";
  } catch { return "en"; }
}
