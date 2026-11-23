-- Emtech Payment Service reporting (Bank of Ghana-style regulatory sandbox).
--
-- People, branches and settlement accounts store only city, ISO region and country.
-- That is the minimum needed to report origin and destination, and it avoids holding
-- precise coordinates on a person (Ghana Data Protection Act, 2012, Act 843).
-- Latitude and longitude are stored only on devices, and only because device
-- registration requires them. They are captured once when the shop or channel
-- device is registered, never from a customer's browser or handset.

ALTER TABLE customer
    ADD COLUMN city text,
    ADD COLUMN region text,
    ADD COLUMN country char(2) NOT NULL DEFAULT 'GH',
    ADD COLUMN locality_source text,
    ADD COLUMN mno_city text,
    ADD COLUMN mno_region text,
    ADD COLUMN regulatory_account_type text,
    ADD COLUMN kyc_service text,
    ADD COLUMN kyc_type text,
    ADD COLUMN kyc_status text,
    ADD COLUMN kyc_type_details text;

ALTER TABLE customer ADD CONSTRAINT customer_locality_source_check
    CHECK (locality_source IS NULL OR locality_source IN ('PROFILE', 'GHANA_CARD'));
ALTER TABLE customer ADD CONSTRAINT customer_regulatory_account_type_check
    CHECK (regulatory_account_type IS NULL OR regulatory_account_type IN
        ('CASH', 'CREDIT', 'BANK', 'MOBILE_MONEY', 'AIRTIME', 'CRYPTOCURRENCY'));
ALTER TABLE customer ADD CONSTRAINT customer_kyc_type_check
    CHECK (kyc_type IS NULL OR kyc_type IN
        ('HUMAN_VERIFIED', 'NATIONAL_ID_TEXT', 'NATIONAL_ID_IMAGE', 'MOBILE_PHOTO', 'OTHER'));
ALTER TABLE customer ADD CONSTRAINT customer_kyc_status_check
    CHECK (kyc_status IS NULL OR kyc_status IN ('VERIFIED', 'UNVERIFIED', 'NOTCHECKED'));

-- Existing customers are Ghana customers. City is not invented.
ALTER TABLE merchant
    ADD COLUMN city text,
    ADD COLUMN region text,
    ADD COLUMN country char(2) NOT NULL DEFAULT 'GH';

ALTER TABLE merchant_branch
    ADD COLUMN city text,
    ADD COLUMN region text,
    ADD COLUMN country char(2) NOT NULL DEFAULT 'GH';

ALTER TABLE settlement_destination
    ADD COLUMN city text,
    ADD COLUMN region text,
    ADD COLUMN country char(2) NOT NULL DEFAULT 'GH';

ALTER TABLE checkout ADD COLUMN channel text;
ALTER TABLE checkout ADD CONSTRAINT checkout_channel_check
    CHECK (channel IS NULL OR channel IN ('WEB', 'MOBILE_APP', 'MOBILE_USSD', 'ATM', 'KIOSK', 'POS', 'OTHER'));

ALTER TABLE wallet_operation
    ADD COLUMN channel text,
    ADD COLUMN origin_city text,
    ADD COLUMN origin_region text,
    ADD COLUMN origin_country char(2),
    ADD COLUMN origin_source text;

-- Device registration fields. connection_type, city and coordinates are left null
-- on existing terminals until the shop location is registered once. They are not guessed.
ALTER TABLE pos_terminal
    ADD COLUMN connection_type text,
    ADD COLUMN manufacturer text,
    ADD COLUMN manufacture_year integer,
    ADD COLUMN device_name text,
    ADD COLUMN device_description text,
    ADD COLUMN city text,
    ADD COLUMN region text,
    ADD COLUMN country char(2) NOT NULL DEFAULT 'GH',
    ADD COLUMN address_line text,
    ADD COLUMN location_description text,
    ADD COLUMN latitude numeric(10, 7),
    ADD COLUMN longitude numeric(10, 7),
    ADD COLUMN inservice_at timestamptz,
    ADD COLUMN device_type text NOT NULL DEFAULT 'POS';

ALTER TABLE pos_terminal ADD CONSTRAINT pos_terminal_connection_type_check
    CHECK (connection_type IS NULL OR connection_type IN ('NETWORK', 'WIRED_PHONE', 'WIRELESS_PHONE'));
ALTER TABLE pos_terminal ADD CONSTRAINT pos_terminal_device_type_check
    CHECK (device_type IN ('ATM', 'KIOSK', 'POS', 'OTHER'));
ALTER TABLE pos_terminal ADD CONSTRAINT pos_terminal_manufacture_year_check
    CHECK (manufacture_year IS NULL OR manufacture_year BETWEEN 1990 AND 2100);
ALTER TABLE pos_terminal ADD CONSTRAINT pos_terminal_coordinates_check
    CHECK ((latitude IS NULL AND longitude IS NULL)
        OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180));

-- Stable virtual device per merchant and channel (web, app, USSD), or one platform
-- device per channel for wallet transfers that have no merchant. Coordinates here are
-- the registered channel device (a shop terminal's coordinates, or a public city
-- reference point), not a customer's location.
CREATE TABLE regulatory_virtual_device (
    id uuid PRIMARY KEY,
    device_id text NOT NULL UNIQUE,
    scope_type text NOT NULL CHECK (scope_type IN ('merchant', 'platform')),
    merchant_id uuid REFERENCES merchant (id),
    channel text NOT NULL CHECK (channel IN ('WEB', 'MOBILE_APP', 'MOBILE_USSD', 'OTHER')),
    city text NOT NULL,
    region text NOT NULL,
    country char(2) NOT NULL,
    address_line text,
    location_description text,
    latitude numeric(10, 7) NOT NULL,
    longitude numeric(10, 7) NOT NULL,
    connection_type text NOT NULL CHECK (connection_type IN ('NETWORK', 'WIRED_PHONE', 'WIRELESS_PHONE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((scope_type = 'merchant') = (merchant_id IS NOT NULL)),
    CHECK (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
);
CREATE UNIQUE INDEX regulatory_virtual_device_merchant
    ON regulatory_virtual_device (merchant_id, channel) WHERE merchant_id IS NOT NULL;
CREATE UNIQUE INDEX regulatory_virtual_device_platform
    ON regulatory_virtual_device (channel) WHERE merchant_id IS NULL;

CREATE TABLE device_event (
    id uuid PRIMARY KEY,
    device_id text NOT NULL,
    terminal_id uuid REFERENCES pos_terminal (id),
    virtual_device_id uuid REFERENCES regulatory_virtual_device (id),
    health text NOT NULL CHECK (health IN ('OPERATIONAL', 'FAILED', 'IMPAIRED')),
    status text NOT NULL CHECK (status IN (
        'HARDWARE_FAILURE', 'SOFTWARE_FAILURE', 'NETWORK_FAILURE', 'CASH_EMPTY',
        'MAINTENANCE', 'ONLINE', 'OTHER')),
    description text,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX device_event_device ON device_event (device_id, occurred_at);

CREATE TABLE consumer_complaint (
    id uuid PRIMARY KEY,
    customer_id uuid REFERENCES customer (id),
    user_id text NOT NULL,
    complaint_type text NOT NULL CHECK (complaint_type IN (
        'FRAUD', 'DISPUTE', 'SERVICE_ERROR', 'SYSTEM_DOWNTIME', 'MISCONDUCT OTHER')),
    type_description text,
    status text NOT NULL CHECK (status IN (
        'OPENED', 'CLOSED', 'REOPENED', 'PENDING_RESOLUTION', 'RESOLVED')),
    status_at timestamptz NOT NULL DEFAULT now(),
    resolution_description text,
    dispute_id uuid REFERENCES dispute (id),
    opened_by text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX consumer_complaint_dispute ON consumer_complaint (dispute_id) WHERE dispute_id IS NOT NULL;

-- City-level place derived for a checkout at pay time. No IP address and no coordinates.
-- Coarse IP is resolved in memory and discarded; only the resulting city is kept.
CREATE TABLE emtech_place_snapshot (
    checkout_id uuid PRIMARY KEY,
    origin_city text,
    origin_region text,
    origin_country char(2),
    origin_source text,
    dest_city text,
    dest_region text,
    dest_country char(2),
    dest_source text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Transactional outbox for Emtech. One row per reportable change, idempotent on
-- idempotency_key, claimed with SKIP LOCKED, dead-lettered when a report cannot succeed.
CREATE TABLE emtech_submission (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    idempotency_key text NOT NULL UNIQUE,
    resource text NOT NULL CHECK (resource IN ('DEVICE', 'TRANSACTION', 'DEVICE_EVENT', 'COMPLAINT')),
    source_type text NOT NULL,
    source_id text NOT NULL,
    fact jsonb NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENDING', 'SUBMITTED', 'DEAD')),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    locked_at timestamptz,
    last_error text,
    last_status integer,
    last_code text,
    submitted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX emtech_submission_pending ON emtech_submission (next_attempt_at, sequence) WHERE status = 'PENDING';
CREATE INDEX emtech_submission_dead ON emtech_submission (created_at) WHERE status = 'DEAD';
