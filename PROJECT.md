# Project: ELEPAY-Core — Deep Frontend+Backend Code-Linking Remediation

## Architecture
- **Backend**: Java 25 / Spring Boot 4.2.0 / Spring Modulith (29 modules, 38 controllers, 303 endpoints) in `src/main/java/com/elepay/` and `src/main/resources/`.
- **Web Frontend**: React + TypeScript + Vite SPA in `web/` (50 Vitest tests, served via Spring Boot `SpaRoutes` in production).
- **Mobile Monorepo**: npm workspaces Expo / React Native in `apps/` (`apps/shared` `@elepay/mobile-core`, `apps/restaurant-pos`, `apps/kitchen-kds`, `apps/merchant-app`).

## Code Layout & Exclusive File Ownership (Wave 2 Workers)
- **`worker_backend`**: Owns `src/main/java/com/elepay/**`, `src/main/resources/**`, `src/test/java/com/elepay/**`.
- **`worker_web`**: Owns `web/**`.
- **`worker_apps`**: Owns `apps/**`.
*(Strictly non-overlapping — no two concurrent Workers may write the same file.)*

## Feature / Defect Inventory (Synthesized from 10 Wave-1 Subagents)

### Track A — Backend (`worker_backend`)
| # | Sev | Defect | Source | Milestone |
|---|-----|--------|--------|-----------|
| B1 | P0 | `RequestSigningFilter` rejects OIDC JWT browser mutations on `/v1/ops/**` in prod (`401 missing_signature`). Keep HMAC v2 mandatory for API keys / non-JWT callers, exempt authenticated OIDC `JwtAuthenticationToken` browser sessions on `/v1/ops/**`. | deep_link_8, deep_link_2 | M1-Backend |
| B2 | P0 | `SecurityConfig` enables `.cors(cors -> {})` with no `CorsConfigurationSource` bean. Add configurable `@Bean CorsConfigurationSource` via `elepay.cors.allowed-origins` allowing all required methods/headers. | deep_link_8, deep_link_4 | M1-Backend |
| B3 | P0 | `SpaRoutes.java:9` omits `"/developer", "/developer/**"` → 404 in prod Docker image. | deep_link_8, deep_link_2, deep_link_9, deep_link_10 | M1-Backend |
| B4 | P0 | `POST /v1/restaurant/kds/tickets` needs real `pos_order_item.id`s. Add `GET /v1/pos/orders/{orderId}/items` returning `List<PosOrderItem>` (with `viewAccess`). | deep_link_1, deep_link_3, deep_link_5, deep_link_10 | M1-Backend |
| B5 | P1 | `SecurityConfig.java:99-102` CSP `connect-src 'self'` blocks external OIDC issuer (`spring.security.oauth2.resourceserver.jwt.issuer-uri`); `isProd` uses `getActiveProfiles()` instead of `acceptsProfiles(Profiles.of("prod","production"))`. | deep_link_8, deep_link_2, deep_link_10 | M1-Backend |
| B6 | P1 | All 5 security filters write `{"code","message"}` without `"detail"`. Emit both `"detail"` and `"message"` (plus `"code"`, `"status"`). | deep_link_8, deep_link_2, deep_link_1 | M1-Backend |
| B7 | P1 | `ApiExceptionHandler` misses `IllegalArgumentException` (400), `IllegalStateException` (409), `NoSuchElementException` (404), `ConstraintViolationException` (400); `InventoryController.java:251,259` `.orElseThrow()` → `ApiException.notFound(...)`. | deep_link_8 | M1-Backend |
| B8 | P1 | Jackson 2 vs Jackson 3 split in `DistributedSessionService`, `RuleEngineService`, `SyntheticIdentityService`, `GradientBoostedFraudModel` — migrate to `tools.jackson.databind.ObjectMapper` (Spring-managed bean). | deep_link_8 | M1-Backend |
| B9 | P1 | `PosController.java:135-189` RBAC: `listTerminals`/`getActiveShift` should use `viewAccess` (filtering by `branchId` when branch-scoped); `authenticateCashier`/`openShift`/`closeShift` should use `operateAccess` with `requireBranch`. | deep_link_1, deep_link_3, deep_link_5 | M1-Backend |
| B10 | P1 | `PosTerminal.pairingCode` has `@JsonIgnore` (`PosTerminalService.java:64`), preventing UI from showing pairing code on registration. Return `RegisterTerminalResponse` (same fields + `pairingCode` + `pairingCodeExpiresAt`) from `POST /v1/pos/terminals/register`. | deep_link_1, deep_link_3 | M1-Backend |
| B11 | P1 | Add `POST /v1/pos/orders/{orderId}/void` (`operateAccess`) to void unpaid `PENDING` POS orders and cancel any open `pos_checkout_link`. | deep_link_1, deep_link_5 | M1-Backend |
| B12 | P1 | `PayrollController.java:99-109` returns `void` from `submit`/`approve`, closing `MerchantTools.tsx` panel. Return updated `PayrollRun`. | deep_link_3 | M1-Backend |
| B13 | P1 | `KitchenDeviceService.Paired` omits `hasPrinter` boolean (causes `Kitchen.tsx` and `apps/kitchen-kds` to hardcode `hasPrinter: false`). Include `hasPrinter` based on station's `output`/`printerAddress`. | deep_link_2, deep_link_6 | M1-Backend |
| B14 | P2 | `KillSwitchController.java:185` ignores request body `{ active, reason }` when deactivating; accept optional reason. `ReadWriteDataSourceConfig`: wrap in `LazyConnectionDataSourceProxy`. `TraceContextFilter`: sanitize incoming `X-Trace-Id`. | deep_link_8, deep_link_2 | M1-Backend |

### Track B — Web Frontend (`worker_web`)
| # | Sev | Defect | Source | Milestone |
|---|-----|--------|--------|-----------|
| W1 | P0 | `MerchantTools.tsx:311-338` `sendToKitchen` sends `c.variantId` as `orderItemId` (always 404), wrong fields (`orderNumber`/`tableNumber`/`stationId`/`modifiersJson`/`notes`), omits `tableId`/`diningMode` (`TAKEAWAY`→`TAKEOUT`)/`clientRef`, and swallows error in `catch {}`. Fetch `GET /v1/pos/orders/${ord.id}/items`, map real `orderItemId`, send exact `KdsService` DTO, surface errors. | deep_link_1, deep_link_3, deep_link_10 | M2-Web |
| W2 | P0 | `PosTenderPanel.tsx:127-137` ignores `railStatus: "failed"` when `checkout.status` resets to `"open"` → polls forever. | deep_link_1 | M2-Web |
| W3 | P0 | `PosTenderPanel.tsx:90-119,457-461` "Verify Now" swallows confirm error, clears pending/QR state, and calls `onOrderPaid()` on an unpaid order. Keep polling and show inline notice if still unpaid. | deep_link_1, deep_link_3 | M2-Web |
| W4 | P1 | `PosTerminalBar.tsx`: display one-time `pairingCode` on terminal registration; clear `shift` and surface non-404 errors in `loadShift()`. | deep_link_1 | M2-Web |
| W5 | P1 | `MerchantTools.tsx` + `posHelpers.ts`: seat `DINE_IN` table via `POST /v1/restaurant/tables/${tableId}/seat?orderId=${created.id}` after creating order; remove dead `tableId` from `buildPosOrderPayload`; add "Void / New Order" button calling `POST /v1/pos/orders/${order.id}/void`; rename `SeatSplitGroup.items[].lineTotalPesewas` → `totalPesewas`; add `"SETTLED"` to `isTerminalStatusPaid()`. | deep_link_1, deep_link_3, deep_link_10 | M2-Web |
| W6 | P1 | `Checkout.tsx`: use `status.fee`/`status.total`/`status.method` when paid/processing (fixes post-reload receipt totals); clear/rotate `localStorage` idempotency key on method/phone/network change, `paid`, or `railStatus === "failed"`; fix OIDC redirect race & dev `prompt()` cancel; relabel static `showQr` toggle ("Open on another device") and render `qrPayload` if present during `ghqr` processing. | deep_link_1 | M2-Web |
| W7 | P1 | `PosTenderPanel.tsx:273-280`: remove broken `/qr?link=${dc.reference}` fallback (use `dc.payUrl` / `qrPayload`). Validate cash against remaining balance `ord.totalPesewas - ord.paidPesewas`. | deep_link_1, deep_link_3, deep_link_10 | M2-Web |
| W8 | P1 | `Merchant.tsx`: fix `OrdersAndReceipts` `"pending"` filter (`pending_settlement`, `processing`, `awaiting_delivery`) & surface load errors; handle empty `merchants === []` cleanly instead of infinite loading; include `delivery` kind in `BusinessAccounting` gross sales if applicable. | deep_link_1, deep_link_3 | M2-Web |
| W9 | P1 | `Kitchen.tsx`: use `hasPrinter` from paired credential / station instead of hardcoding `false`. `Ops.tsx`: include `netByCurrency` check in `ledgerHealthy`; pass `b.currency` to `formatMoney` at `:1323`; add `.sev-low` in `styles.css`. `api.ts:43`: read `data?.detail ?? data?.message ?? (response.statusText || \`HTTP ${response.status}\`)`. | deep_link_2, deep_link_8 | M2-Web |
| W10 | P1 | Update/add Vitest unit tests in `web/src/pos/posHelpers.test.ts` covering `"SETTLED"`, `totalPesewas`, `buildPosOrderPayload`, and rail failure status detection. | deep_link_1 | M2-Web |

### Track C — Mobile Apps (`worker_apps`)
| # | Sev | Defect | Source | Milestone |
|---|-----|--------|--------|-----------|
| A1 | P0 | All 3 Expo apps (`restaurant-pos`, `kitchen-kds`, `merchant-app`) fail Metro bundling (`ConfigError: Cannot resolve entry file`) due to `"main": "node_modules/expo/AppEntry.js"` in hoisted workspaces + missing `metro.config.js`. Add `index.ts` (`registerRootComponent(App)`), set `"main": "index.ts"`, and add workspace-aware `metro.config.js` to all 3 apps. | deep_link_4, deep_link_6, deep_link_7, deep_link_10 | M3-Apps |
| A2 | P0 | `apps/restaurant-pos/src/screens/PosRegisterScreen.tsx:195-213` `sendToKitchen` sends `c.variantId` as `orderItemId` (always 404) and wrong DTO fields. Fetch `GET /v1/pos/orders/${ord.id}/items`, map real `orderItemId`, send exact `KdsService` payload (`tableId`, `diningMode` with `TAKEOUT`, `clientRef`, `modifiers`), surface errors. | deep_link_5, deep_link_10 | M3-Apps |
| A3 | P1 | `apps/shared/src/`: (1) `posHelpers.ts`: rename `SeatSplitResult.seatGroups[].items[].lineTotalPesewas` → `totalPesewas`; add `"SETTLED"` to `isTerminalStatusPaid()`; remove dead `tableId`/`notes` from `buildPosOrderPayload`. (2) `api.ts`: fix `response.statusText || \`HTTP ${response.status}\`` + `ProblemDetail.errors[]` in `call` & `callAsKitchenDevice`; fix `callAsKitchenDevice` to send & sign `options.idempotencyKey`, uppercase `method`, and include `baseUrl` path prefix in `canonicalV2`; add fetch timeout (`AbortController`). (3) `money.ts`: tighten `normalizeGhanaPhone` to `/^233[1-9]\d{8}$/`. (4) `types.ts`: add `status`, `coolingOffUntil`, `oobVerificationStatus` to `SettlementDestination` and `hasPrinter?: boolean` to `DeviceCredential`. (5) `kdsEngine.ts`: revert optimistic ticket state on 4xx rejection in `kdsSettle` (or trigger full resync with `since=0`). | deep_link_4, deep_link_6, deep_link_7 | M3-Apps |
| A4 | P1 | `apps/restaurant-pos`: (1) `TenderModal.tsx`: fix "Verify Now" swallowing 409; detect `railStatus === "failed"`; cancel pending digital checkout on modal close (or warn/cancel); remove broken `/qr?link=${dc.reference}` fallback; await receipt before closing. (2) `PosRegisterScreen.tsx`: seat `DINE_IN` table via `POST /v1/restaurant/tables/${tableId}/seat?orderId=${created.id}`; add "Void / New Order" button (`POST /v1/pos/orders/${order.id}/void`). (3) `App.tsx`: preserve `PosRegisterScreen` state across Register/Floor Plan tab switches (display toggle instead of unmount); reset state on merchant switch (`key={merchant.id}`); add `.catch()` on `SecureStore`; use `SafeAreaProvider`/`SafeAreaView` from `react-native-safe-area-context`. | deep_link_5, deep_link_4 | M3-Apps |
| A5 | P1 | `apps/kitchen-kds`: (1) `KitchenBoardScreen.tsx`: await AsyncStorage hydration BEFORE starting live `sync` loop + add `if (stopped) return;` guards after every `await`; trigger immediate `flush()` kick after bump/cancel; guard `toLocaleTimeString` with try/catch for Android Hermes; handle 4xx rejection by reverting optimistic state / resyncing; use `hasPrinter` from paired credential/station; fix `ticketCard` width overflow. (2) `StationSelectScreen.tsx`: add loading/retry/cancel guard. (3) `App.tsx`: use `SafeAreaProvider`/`SafeAreaView` from `react-native-safe-area-context`. | deep_link_6, deep_link_4 | M3-Apps |
| A6 | P1 | `apps/merchant-app`: (1) `SalesScreen.tsx`: fix `"pending"` filter (`pending_settlement`, `processing`, `awaiting_delivery`); only show Refund form for refundable statuses (`settled`, `pending_settlement`, `partially_refunded`) and non-cashier roles. (2) `CollectScreen.tsx`: render `r.qrPayload ?? r.payload` for Shop GhQR. (3) `App.tsx`: add `key={merchant.id}` on tab screens; add empty-merchants state instead of infinite loading; add `.catch()` on `SecureStore`; use `SafeAreaProvider`/`SafeAreaView` from `react-native-safe-area-context`. (4) `HomeScreen.tsx`: handle `/balances` 403 for cashiers without blanking `/transactions`. (5) `WithdrawScreen.tsx`: show destination `coolingOffUntil`/`oobVerificationStatus` warnings and handle HTTP 202 `DualControl` (`pending_approval`) cleanly. | deep_link_7, deep_link_4 | M3-Apps |
| A7 | P1 | Update/add unit tests in `apps/shared/src/__tests__/core.test.ts` covering `totalPesewas`, `"SETTLED"`, `normalizeGhanaPhone` (`2330...` rejected), `callAsKitchenDevice` idempotency/uppercase, and `kdsSettle` 4xx rollback. | deep_link_4, deep_link_6 | M3-Apps |

## Interface Contracts (Synchronized Across All 3 Tracks)
1. `GET /v1/pos/orders/{orderId}/items` → `200 OK` JSON array of `PosOrderItem` (`id`, `orderId`, `variantId`, `name`, `quantity`, `unitPricePesewas`, `discountPesewas`, `totalPesewas`, `seatNumber`, `course`, `modifiers`, `notes`).
2. `POST /v1/pos/orders/{orderId}/void` → `200 OK` `PosOrder` (status `VOIDED`, cancels any active `pos_checkout_link`).
3. `POST /v1/pos/terminals/register` → `200 OK` JSON containing all `PosTerminal` fields PLUS `pairingCode` (6-digit string) and `pairingCodeExpiresAt` (ISO-8601).
4. `POST /v1/payroll/runs/{id}/submit` and `POST /v1/payroll/runs/{id}/approve` → `200 OK` `PayrollRun`.
5. `POST /v1/public/kitchen/pair` → `200 OK` `Paired` including `hasPrinter: boolean`.
6. Security filter error JSON → `{"code":"...","detail":"...","message":"...","status":...}`.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 0 | Wave 1: 10-Subagent Deep Challenge | 8 Explorers + 2 Challengers across entire stack | none | DONE |
| 1 | M1-Backend | Backend P0/P1/P2 fixes (`worker_backend`) | M0 | DONE |
| 2 | M2-Web | Web Frontend P0/P1/P2 fixes (`worker_web`) | M0 | DONE |
| 3 | M3-Apps | Mobile Monorepo P0/P1/P2 fixes (`worker_apps`) | M0 | DONE |
| 4 | Wave 3: Verification Gate | 2 Reviewers + 2 Challengers + 1 Forensic Auditor (Iter 2 PASS) | M1, M2, M3 | DONE |
