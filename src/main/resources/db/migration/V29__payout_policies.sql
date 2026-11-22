-- How settled merchant balances leave ELEPAY: on request (the merchant asks
-- for each withdrawal) or automatic (each available balance is paid out to the
-- merchant's verified destination once it reaches a minimum).
--
-- A marketplace (platform) decides for its sellers and delivery partners: one
-- default for all of them, optionally overridden per seller. A merchant that no
-- marketplace governs decides for itself. The merchant always chooses which of
-- its own verified destinations receives automatic withdrawals.

CREATE TABLE payout_policy (
    merchant_id    uuid PRIMARY KEY REFERENCES merchant(id),
    mode           text        NOT NULL DEFAULT 'on_request' CHECK (mode IN ('on_request', 'automatic')),
    min_amount     bigint      NOT NULL DEFAULT 0 CHECK (min_amount >= 0),
    destination_id uuid REFERENCES settlement_destination(id),
    updated_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform_payout_policy (
    marketplace_id uuid PRIMARY KEY REFERENCES merchant(id),
    mode           text        NOT NULL CHECK (mode IN ('on_request', 'automatic')),
    min_amount     bigint      NOT NULL DEFAULT 0 CHECK (min_amount >= 0),
    updated_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE platform_seller_payout_policy (
    marketplace_id uuid        NOT NULL REFERENCES merchant(id),
    seller_id      uuid        NOT NULL REFERENCES merchant(id),
    mode           text        NOT NULL CHECK (mode IN ('on_request', 'automatic')),
    min_amount     bigint      NOT NULL DEFAULT 0 CHECK (min_amount >= 0),
    updated_by     text        NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (marketplace_id, seller_id),
    FOREIGN KEY (marketplace_id, seller_id) REFERENCES marketplace_seller(marketplace_id, seller_id)
);
