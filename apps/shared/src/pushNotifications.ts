import { useEffect, useRef } from "react";
import { AppState, Platform } from "react-native";
import * as Notifications from "expo-notifications";
import * as SecureStore from "expo-secure-store";
import { ElepayClient, newKey } from "./api";
import type { DeviceCredential } from "./types";

export type PushApp = "WAITER" | "POS" | "KDS" | "MERCHANT";
const CHANNEL = "elepay_updates";
const INSTALLATION_KEY = "elepay_push_installation";
type Scope = { client: ElepayClient; merchantId: string; app: PushApp; device?: DeviceCredential; stationId?: string | null };

let installation: Promise<string> | undefined;
function installationId(): Promise<string> {
  if (!installation) {
    installation = (async () => {
      const previous = await SecureStore.getItemAsync(INSTALLATION_KEY);
      if (previous) return previous;
      const id = newKey();
      await SecureStore.setItemAsync(INSTALLATION_KEY, id);
      return id;
    })().catch((e: unknown) => { installation = undefined; throw e; });
  }
  return installation;
}

// Serialize register/unregister operations to avoid a late token refresh undoing logout.
let queue: Promise<void> = Promise.resolve();
function enqueue(task: () => Promise<void>): Promise<void> {
  const next = queue.then(task, task);
  queue = next.catch(() => {});
  return next;
}

async function send(scope: Scope, action: "register" | "unregister", body: object): Promise<void> {
  const opts = { method: "POST", body, timeoutMs: 7000 };
  if (scope.app === "KDS") {
    if (!scope.device) return;
    await scope.client.callAsKitchenDevice<void>(scope.device, "/v1/restaurant/kds/push/" + action, opts);
  } else {
    await scope.client.call<void>("/v1/notifications/push/" + action, opts);
  }
}

export function useFcmRegistration(
  client: ElepayClient | null,
  app: PushApp,
  merchantId: string | null,
  device?: DeviceCredential | null,
  stationId?: string | null,
) {
  const stop = useRef<(() => Promise<void>) | null>(null);
  useEffect(() => {
    if (Platform.OS !== "android" || !client || !merchantId || (app === "KDS" && !device)) return;
    const scope: Scope = { client, merchantId, app, device: device ?? undefined, stationId };
    let active = true;
    let registrationStarted = false;
    let lastToken: string | null = null;
    let lastRenewal = 0;
    let requested = false;
    let running = false;
    let pendingToken: string | null = null;

    // Notification contents are generic; the existing authenticated screens remain authoritative.
    Notifications.setNotificationHandler({
      handleNotification: async () => ({
        shouldShowAlert: active,
        shouldPlaySound: active && app !== "KDS",
        shouldSetBadge: false,
      }),
    });

    async function register(token: string, force: boolean) {
      await enqueue(async () => {
        if (!active) return;
        if (!force && lastToken === token && Date.now() - lastRenewal < 15 * 60_000) return;
        const id = await installationId();
        if (!active) return;
        registrationStarted = true;
        await send(scope, "register", {
          merchantId: scope.merchantId, installationId: id, app: scope.app,
          token, stationId: scope.app === "KDS" ? scope.stationId ?? null : null,
        });
        lastToken = token;
        lastRenewal = Date.now();
      });
    }

    async function refresh(prompt: boolean) {
      if (!active || running) return;
      running = true;
      try {
        await Notifications.setNotificationChannelAsync(CHANNEL, {
          name: "ELEPAY updates",
          importance: Notifications.AndroidImportance.HIGH,
          lockscreenVisibility: Notifications.AndroidNotificationVisibility.PRIVATE,
          sound: "default",
        });
        let permission = await Notifications.getPermissionsAsync();
        if (!permission.granted && prompt && !requested && permission.canAskAgain) {
          requested = true;
          permission = await Notifications.requestPermissionsAsync();
        }
        if (!active) return;
        if (!permission.granted) {
          if (registrationStarted || lastToken) {
            await enqueue(async () => {
              if (!active) return;
              await send(scope, "unregister", {
                merchantId: scope.merchantId, installationId: await installationId(), app: scope.app,
              });
              lastToken = null;
              registrationStarted = false;
            });
          }
          return;
        }
        // Native Android FCM token, not an Expo Push Service token.
        const nativeToken = await Notifications.getDevicePushTokenAsync();
        if (nativeToken.type === "android" && typeof nativeToken.data === "string" && nativeToken.data) {
          await register(nativeToken.data, false);
        }
      } catch {
        // An offline phone, missing Firebase build config or a disabled backend must not break POS.
        // Do not log tokens or credentials; foreground activation and renewal retry the registration.
      } finally {
        running = false;
        const token = pendingToken;
        pendingToken = null;
        if (active && token) void register(token, true).catch(() => {});
      }
    }

    const tokenSubscription = Notifications.addPushTokenListener((token) => {
      if (!active || token.type !== "android" || typeof token.data !== "string") return;
      if (running) { pendingToken = token.data; return; }
      void register(token.data, true).catch(() => {});
    });
    const appStateSubscription = AppState.addEventListener("change", (state) => {
      if (state === "active") void refresh(false);
    });
    const timer = setInterval(() => {
      if (AppState.currentState === "active") void refresh(false);
    }, 15 * 60_000);

    let stopping: Promise<void> | null = null;
    const deactivate = () => {
      if (stopping) return stopping;
      active = false;
      clearInterval(timer);
      tokenSubscription.remove();
      appStateSubscription.remove();
      // Always attempt removal, including a registration restored from an earlier process.
      stopping = enqueue(async () => {
        const id = await SecureStore.getItemAsync(INSTALLATION_KEY);
        if (id) await send(scope, "unregister", {
          merchantId: scope.merchantId, installationId: id, app: scope.app,
        });
      }).catch(() => {});
      return stopping;
    };
    stop.current = deactivate;
    void refresh(true);
    return () => {
      if (stop.current === deactivate) stop.current = null;
      void deactivate();
    };
  }, [client, app, merchantId, device, stationId]);

  return async () => { await stop.current?.(); };
}
