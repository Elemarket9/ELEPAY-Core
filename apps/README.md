# ELEPAY Native Mobile & Tablet Apps (React Native / Expo)

Four dedicated, live-API-backed Android & iOS apps sharing `@elepay/mobile-core`:

1. **`apps/restaurant-pos` (`gh.elepay.pos`)** — Restaurant POS Register & Floor Plan (tablet split-pane & phone): menu catalog, category filters, dine-in/takeaway/delivery orders, table assignment, Send to Kitchen (KDS), hold/recall carts, terminal/cashier PIN/shift management, Cash (with change calculation), MoMo Push, dynamic EMVCo GhQR, Card Checkout, and receipt preview.
2. **`apps/kitchen-kds` (`gh.elepay.kitchen`)** — Kitchen Display System (KDS) for landscape kitchen tablets: 6-digit merchant/pairing code onboarding, HMAC-SHA256 v2 signed device requests, station selection, offline-first ticket sync & action queue (`AsyncStorage`), live elapsed timers, urgency borders, keep-awake, and large touch bump controls.
3. **`apps/merchant-app` (`gh.elepay.merchant`)** — ELEPAY Merchant Owner App: live Available / Pending / Reserved balances, live sales feed with receipt & refund modal, instant MoMo/GhQR payment links with native share sheet + static Shop QR, and withdrawals to verified settlement destinations with payout history.

4. **`apps/waiter-app` (`gh.elepay.waiter`)** — branch-scoped waiter ordering, kitchen status, served confirmation and bill requests. Managers assign tables via `PUT /v1/restaurant/waiter/{merchant}/tables/{table}/branch` and provision the `waiter` merchant role with a branch. Use a valid identity-provider access token over HTTPS. Waiters do not receive payment collection permissions.

## Quick Start

```bash
cd apps
npm install
npm run typecheck
npm test
```

Start any app with Expo Go or an Android/iOS simulator:

```bash
npm run start:waiter    # Waiter
npm run start:pos       # Restaurant POS
npm run start:kds       # Kitchen Display (KDS)
npm run start:merchant  # ELEPAY Merchant App
```

The apps connect directly to live ELEPAY backend APIs (no client-side mock data). Point the Server URL on the sign-in/pairing screen to your local (`http://10.0.2.2:8080` on Android emulator, `http://localhost:8080` on iOS simulator) or staging/production ELEPAY API server.

## Android push notifications

Native FCM is optional. See [the Firebase setup and verification runbook](../docs/runbooks/firebase-push.md) for Firebase app IDs, secure server credentials, native builds and acceptance tests. Existing API polling remains available without Firebase.
