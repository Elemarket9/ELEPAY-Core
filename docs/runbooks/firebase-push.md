# Firebase Cloud Messaging (Android)

ELEPAY sends native FCM notifications directly to Android devices. Firebase is a notification transport, not a source of truth for orders or financial state. Existing polling, KDS sync, SMS and email are unchanged. No Firebase Authentication, Firestore, Expo push relay or payment provider is required.

## Enable on a staging VPS

1. In Firebase Console, create a project and enable the Firebase Cloud Messaging API (HTTP v1).
2. Register these Android application IDs in that **same project**:
   - `gh.elepay.waiter`
   - `gh.elepay.pos`
   - `gh.elepay.kitchen`
   - `gh.elepay.merchant`
3. Download each application's `google-services.json` into its corresponding directory under `apps/`. These files are intentionally ignored by Git. Build native Android apps with those files present (Expo prebuild / native build or EAS with secure build-file injection). Expo Go is not the acceptance environment. Rebuild after changing Firebase project configuration.
4. Provide Application Default Credentials to the backend. Prefer a workload identity where available; on a standalone VPS mount a restricted service account credential file read-only outside the repository and container image. Grant only permissions required to send FCM messages. Do not paste the private key into source code, app config, CI logs or chat.
5. Configure the following environment variables in the backend's deployment environment:

```dotenv
ELEPAY_PUSH_ENABLED=true
ELEPAY_PUSH_PROJECT_ID=your-firebase-project-id
GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase-service-account.json
ELEPAY_PUSH_FINANCIAL_ENABLED=false
ELEPAY_PUSH_REGISTRATION_HOURS=168
ELEPAY_PUSH_POLL_MS=2000
```

The default is disabled and no Firebase credentials are loaded while disabled. Financial checkout notifications are separately disabled by default. Do not enable financial features merely to enable restaurant push. Restart the service after changing these settings. Flyway V87 adds subscription and delivery tables and restaurant enqueue triggers.

## Event and authorization model

- KDS: new kitchen ticket, only for the corresponding merchant and station (or authorized all-stations device). Unrouted items notify eligible stations in the order's branch, including global stations.
- Waiter: a ticket becomes READY, only for its assigned waiter and branch.
- POS: bill requested, only for the merchant's eligible cashier/manager installations.
- Merchant: committed checkout-paid event, only for merchants with positive paid allocations and eligible merchant roles, and only when financial push is enabled.
- Android notifications contain generic text, an opaque delivery ID and event kind. They contain no payment amount, customer name, order details, credentials or PII. Opening the app uses its existing authenticated API and polling. No payment or order mutation can be triggered by a push.
- Registrations are installation scoped, renewed on foreground and token rotation, removed on sign-out/unpair when connected, and expire after the lease. Offline sign-out cannot guarantee immediate server removal; generic notifications may still arrive until the lease expires or the token is invalidated.
- Delivery is at least once, with bounded retries, expiry and invalid-token cleanup. Claims commit before FCM network I/O and are fenced by attempt number. Token rotation invalidates old registration IDs without locking order transactions; pending alerts are revalidated before send. Android notification tags suppress repeated tray entries for the same delivery. Checkout events older than 15 minutes are not replayed into the push queue. FCM tokens are capped at 1024 ASCII characters to keep the PostgreSQL unique index safe. A crash or ambiguous provider acknowledgement can duplicate a notification. Never interpret Firebase acceptance as delivery or as an order acknowledgement.

## Acceptance checklist

- Install real native builds on Android devices with Google Play Services. Grant Android notification permission.
- Pair kitchen devices for two stations; sign in a waiter, branch cashier and merchant. Create a ticket, bump it to READY and request its bill.
- Verify only intended devices receive each notification in foreground, background and locked-screen conditions. Confirm normal operation with permission denied, absent Firebase configuration and an offline device.
- Repeat an idempotent order request; verify no duplicate queue event for an existing subscription. Revoke a kitchen credential and remove staff membership; queued delivery must be cancelled before sending. Exercise token rotation and online sign-out.
- Test provider failure/invalid tokens, queue expiry and process restart. Check `push_delivery` state counts without exporting or logging device tokens.
- Confirm restaurant polling and checkout behavior are unchanged when FCM is disabled.
- With financial push disabled, ensure no checkout notification is queued. Only test financial notifications using authorized sandbox payments after explicitly enabling that channel.

For installation verification run `cd apps && npm ci && npm run typecheck && npm test` and `mvn -B clean verify` from the repository root. Real FCM acceptance requires project credentials and physical Android devices; CI does not prove device delivery. An already accepted FCM message cannot be recalled if staff sign out or a device is revoked while delivery is in flight. iOS requires a separate APNs/FCM client integration and is not enabled by this change.

### Dependency isolation

The server uses only Firebase Admin messaging. Firestore and Cloud Storage are excluded from the Maven dependency graph; the classpath regression test checks messaging initialization without those optional services. No vulnerability suppression or security threshold changes are used.
