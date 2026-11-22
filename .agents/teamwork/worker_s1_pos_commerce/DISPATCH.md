## 2026-11-22T04:40:41Z
You are Worker 6 (Stream 1: In-Store Commerce & POS).
Working directory: /home/antigravity/Elemarket9/ELEPAY-Core
State folder: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/worker_s1_pos_commerce
Original request: /home/antigravity/Elemarket9/ELEPAY-Core/.agents/teamwork/ORIGINAL_REQUEST.md
Project plan: /home/antigravity/Elemarket9/ELEPAY-Core/PROJECT.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. Integrity violations WILL be detected and your work WILL be rejected.

EXCLUSIVE SCOPE:
- Java packages: `src/main/java/com/elepay/pos/`, `src/main/java/com/elepay/inventory/`, `src/main/java/com/elepay/restaurant/`
- Test packages: `src/test/java/com/elepay/pos/`, `src/test/java/com/elepay/inventory/`, `src/test/java/com/elepay/restaurant/`
- Migration: `src/main/resources/db/migration/V22__pos_inventory_restaurant.sql`

TASKS:
1. Domain 1 (ELEPAY POS):
   - Terminal registration & pairing, cashier PIN session auth, quick-sale cart, item selection, multi-tender payment (Cash, MoMo, Card, Wallet), split bills, digital & printed receipts, offline sync queue with conflict resolution.
2. Domain 3 (Inventory & Warehouse Management):
   - Multi-location warehouse support, product catalog with variations (size/color), SKU & Barcode (EAN-13/UPC) scanner lookup, real-time stock levels, stock transfer orders between branches, purchase orders (PO), low-stock alerts, lot/batch tracking with expiry dates.
3. Domain 4 (Restaurant Management & KDS):
   - Table and floor plan management, dine-in / takeout / delivery modes, course management (appetizer/main/dessert), item modifiers & combo options, Kitchen Display System (KDS) live order ticket state machine (New, Preparing, Ready, Served), kitchen ticket bump bar, split bill by seat or item, food recipe ingredient costing linked to inventory deductions.
4. Database & Verification:
   - Create Flyway migration V22, write comprehensive unit and integration tests, verify compilation and tests pass, commit and push to origin/main immediately. Report handoff when done.

## 2026-11-22T05:02:05Z
[Message] sender=8bad7bfffd7b87726ff5069de7fe43f4
Heartbeat check on Stream 1 (POS & Commerce). Status of POS, Inventory, Restaurant/KDS implementation, V22 migration, and tests.
Report current status.
