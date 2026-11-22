-- Marketplace commission grid (Jumia-style), release on delivery, and fees held
-- until settlement (BoG Notice BG/GOV/SEC/2026/43).
--
-- A marketplace client sets a commission per product category (percentage with
-- an optional minimum) and a fixed processing fee per item. ELEPAY deducts both
-- from each seller line and holds them for the marketplace until the line is
-- delivered, so commission is earned only on delivered items. ELEPAY's own
-- merchant fee is held in deferred revenue and recognised at settlement.

CREATE TABLE marketplace_fee_schedule (
    marketplace_id        uuid PRIMARY KEY REFERENCES merchant(id),
    -- A paid line that is never confirmed delivered, refunded or disputed is
    -- treated as delivered after this many days, so funds never sit unsettled.
    delivery_confirm_days integer     NOT NULL DEFAULT 14 CHECK (delivery_confirm_days BETWEEN 1 AND 60),
    updated_by            text        NOT NULL,
    updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE marketplace_fee_rule (
    marketplace_id   uuid    NOT NULL REFERENCES marketplace_fee_schedule(marketplace_id) ON DELETE CASCADE,
    category         text    NOT NULL CHECK (category ~ '^(\*|[a-z0-9][a-z0-9_-]{0,63})$'),
    commission_bps   integer NOT NULL CHECK (commission_bps BETWEEN 0 AND 5000),
    commission_min   bigint  NOT NULL DEFAULT 0 CHECK (commission_min >= 0),
    processing_fee   bigint  NOT NULL DEFAULT 0 CHECK (processing_fee >= 0),
    PRIMARY KEY (marketplace_id, category)
);

ALTER TABLE checkout ADD COLUMN delivery_deadline timestamptz;

ALTER TABLE checkout_allocation
    ADD COLUMN category              text,
    ADD COLUMN commission            bigint  NOT NULL DEFAULT 0 CHECK (commission >= 0),
    ADD COLUMN processing_fee        bigint  NOT NULL DEFAULT 0 CHECK (processing_fee >= 0),
    ADD COLUMN refunded_platform_fee bigint  NOT NULL DEFAULT 0 CHECK (refunded_platform_fee >= 0),
    ADD COLUMN disputed_fee          bigint  NOT NULL DEFAULT 0 CHECK (disputed_fee >= 0),
    ADD COLUMN disputed_platform_fee bigint  NOT NULL DEFAULT 0 CHECK (disputed_platform_fee >= 0),
    -- Marketplace share still held in the marketplace's pending bucket.
    ADD COLUMN platform_pending      bigint  NOT NULL DEFAULT 0 CHECK (platform_pending >= 0),
    ADD COLUMN platform_settled      bigint  NOT NULL DEFAULT 0 CHECK (platform_settled >= 0),
    -- ELEPAY fee still held in deferred revenue, and the part already recognised.
    ADD COLUMN fee_deferred          bigint  NOT NULL DEFAULT 0 CHECK (fee_deferred >= 0),
    ADD COLUMN fee_recognized        bigint  NOT NULL DEFAULT 0 CHECK (fee_recognized >= 0),
    -- Marketplace seller and delivery lines wait for delivery before settling.
    ADD COLUMN hold_until_delivered  boolean NOT NULL DEFAULT false,
    ADD COLUMN delivered_at          timestamptz,
    ADD COLUMN delivery_confirmed_by text;

ALTER TABLE checkout_allocation
    ADD CONSTRAINT checkout_allocation_fees_within_amount
        CHECK (merchant_fee + commission + processing_fee <= amount),
    ADD CONSTRAINT checkout_allocation_platform_refund_within
        CHECK (refunded_platform_fee + disputed_platform_fee <= commission + processing_fee),
    ADD CONSTRAINT checkout_allocation_fee_refund_within
        CHECK (refunded_fee + disputed_fee <= merchant_fee);

CREATE INDEX checkout_allocation_awaiting_delivery ON checkout_allocation (checkout_id)
    WHERE hold_until_delivered AND delivered_at IS NULL;

-- What a dispute hold took from each party, so the resolution returns exactly that.
ALTER TABLE dispute
    ADD COLUMN seller_part   bigint NOT NULL DEFAULT 0 CHECK (seller_part >= 0),
    ADD COLUMN fee_part      bigint NOT NULL DEFAULT 0 CHECK (fee_part >= 0),
    ADD COLUMN platform_part bigint NOT NULL DEFAULT 0 CHECK (platform_part >= 0),
    ADD COLUMN refund_id     uuid REFERENCES refund(id);

ALTER TABLE refund ADD COLUMN platform_fee_returned bigint NOT NULL DEFAULT 0 CHECK (platform_fee_returned >= 0);

-- Existing paid lines: their ELEPAY fee was already booked as revenue at payment.
UPDATE checkout_allocation a SET fee_recognized = a.merchant_fee - a.refunded_fee
FROM checkout c WHERE c.id = a.checkout_id AND c.status = 'paid';
