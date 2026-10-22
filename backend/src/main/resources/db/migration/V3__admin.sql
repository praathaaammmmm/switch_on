-- Admin dashboard: staff accounts, sessions, an append-only audit log, price history, and the operational data the
-- dashboard manages (availability, capacity, technicians, internal notes, quote drafts, refunds, ticket messages).
-- Everything here is additive: existing customer rows and the customer API keep working unchanged.

-- ---- Staff and sessions --------------------------------------------------------------------------------------
CREATE TABLE admin_users (
    id            uuid PRIMARY KEY,
    email         text NOT NULL,
    name          text NOT NULL,
    role          text NOT NULL CHECK (role IN ('OWNER', 'OPERATIONS', 'SUPPORT', 'FINANCE', 'VIEWER')),
    -- PBKDF2-HMAC-SHA512, stored as "pbkdf2_sha512$<iterations>$<salt b64>$<hash b64>". Never the password.
    password_hash text NOT NULL,
    active        boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz
);
CREATE UNIQUE INDEX admin_users_email_key ON admin_users (lower(email));

-- Cookie sessions. Only a SHA-256 hash of the token is stored.
CREATE TABLE admin_sessions (
    token_hash   text PRIMARY KEY,
    admin_id     uuid NOT NULL REFERENCES admin_users (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz
);
CREATE INDEX admin_sessions_admin_idx ON admin_sessions (admin_id);

-- ---- Audit log: one row per admin change, never updated or deleted -----------------------------------------
CREATE TABLE admin_audit_log (
    id          bigserial PRIMARY KEY,
    admin_id    uuid REFERENCES admin_users (id),
    admin_email text NOT NULL,
    action      text NOT NULL,
    entity_type text NOT NULL,
    entity_id   text NOT NULL,
    reason      text,
    before      jsonb,
    after       jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_audit_entity_idx ON admin_audit_log (entity_type, entity_id, id DESC);
CREATE INDEX admin_audit_time_idx ON admin_audit_log (occurred_at DESC, id DESC);

CREATE FUNCTION forbid_row_changes() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER admin_audit_log_immutable
    BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_row_changes();

-- ---- Catalogue: availability, diagnosis fees and price history ---------------------------------------------
ALTER TABLE device_categories ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE service_catalog_entries
    ADD COLUMN active            boolean NOT NULL DEFAULT true,
    ADD COLUMN diagnosis_fee_inr int     NOT NULL DEFAULT 0 CHECK (diagnosis_fee_inr >= 0),
    ADD COLUMN updated_at        timestamptz NOT NULL DEFAULT now();

-- Every change to a price-affecting field, with its reason. Repairs keep the estimate they were booked with and
-- quotes keep their own line items, so history here never rewrites what a customer was told.
CREATE TABLE service_price_history (
    id            bigserial PRIMARY KEY,
    scope         text NOT NULL CHECK (scope IN ('SERVICE', 'BRAND')),
    category_code text NOT NULL REFERENCES device_categories (code),
    issue_code    text REFERENCES repair_issues (code),
    brand         text,
    field         text NOT NULL,
    old_value     text,
    new_value     text,
    reason        text NOT NULL CHECK (length(reason) >= 5),
    changed_by    uuid NOT NULL REFERENCES admin_users (id),
    changed_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX service_price_history_idx ON service_price_history (category_code, issue_code, id DESC);

CREATE TRIGGER service_price_history_immutable
    BEFORE UPDATE OR DELETE ON service_price_history
    FOR EACH ROW EXECUTE FUNCTION forbid_row_changes();

-- ---- Partners: capabilities, capacity, technicians ---------------------------------------------------------
ALTER TABLE repair_shops
    ADD COLUMN active       boolean NOT NULL DEFAULT true,
    ADD COLUMN capacity     int     NOT NULL DEFAULT 20 CHECK (capacity >= 0),
    ADD COLUMN capabilities text[]  NOT NULL DEFAULT '{PHONE,LAPTOP,TABLET,PC,WEARABLE,AUDIO}';

ALTER TABLE pickup_partners
    ADD COLUMN active         boolean NOT NULL DEFAULT true,
    ADD COLUMN daily_capacity int     NOT NULL DEFAULT 12 CHECK (daily_capacity >= 0);

CREATE TABLE technicians (
    id         uuid PRIMARY KEY,
    shop_id    uuid NOT NULL REFERENCES repair_shops (id),
    name       text NOT NULL,
    skills     text[] NOT NULL DEFAULT '{}',
    active     boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX technicians_shop_idx ON technicians (shop_id);

-- One technician per seeded shop, from the name the shop already lists.
INSERT INTO technicians (id, shop_id, name, skills)
SELECT md5('tech:' || id::text)::uuid, id, split_part(technician, ' ·', 1), capabilities FROM repair_shops;

ALTER TABLE repairs ADD COLUMN technician_id uuid REFERENCES technicians (id);

-- Staff-only notes on a repair. Never shown to the customer; append-only.
CREATE TABLE repair_internal_notes (
    id         bigserial PRIMARY KEY,
    repair_id  text NOT NULL REFERENCES repairs (id),
    admin_id   uuid NOT NULL REFERENCES admin_users (id),
    body       text NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX repair_internal_notes_idx ON repair_internal_notes (repair_id, id);
CREATE TRIGGER repair_internal_notes_immutable
    BEFORE UPDATE OR DELETE ON repair_internal_notes
    FOR EACH ROW EXECUTE FUNCTION forbid_row_changes();

-- ---- Quotes: drafts prepared by the shop before submission -------------------------------------------------
CREATE TABLE quote_drafts (
    repair_id     text PRIMARY KEY REFERENCES repairs (id),
    diagnosis     text NOT NULL DEFAULT '',
    findings      text[] NOT NULL DEFAULT '{}',
    items         jsonb NOT NULL DEFAULT '[]',
    warranty_text text NOT NULL DEFAULT '90 days on parts and labour',
    updated_by    uuid NOT NULL REFERENCES admin_users (id),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- ---- Payments: refunds and reconciliation (no gateway; records only) ---------------------------------------
ALTER TABLE payment_records
    ADD COLUMN reconciled_at   timestamptz,
    ADD COLUMN reconciled_by   uuid REFERENCES admin_users (id),
    ADD COLUMN reconciled_note text;

CREATE TABLE payment_refunds (
    id         uuid PRIMARY KEY,
    repair_id  text NOT NULL REFERENCES payment_records (repair_id),
    amount_inr int  NOT NULL CHECK (amount_inr > 0),
    reason     text NOT NULL CHECK (length(reason) >= 5),
    created_by uuid NOT NULL REFERENCES admin_users (id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_refunds_repair_idx ON payment_refunds (repair_id);
CREATE TRIGGER payment_refunds_immutable
    BEFORE UPDATE OR DELETE ON payment_refunds
    FOR EACH ROW EXECUTE FUNCTION forbid_row_changes();

-- ---- Warranty claims: review states ------------------------------------------------------------------------
ALTER TABLE warranty_claims DROP CONSTRAINT warranty_claims_status_check;
ALTER TABLE warranty_claims ADD CONSTRAINT warranty_claims_status_check
    CHECK (status IN ('OPEN', 'INFO_REQUESTED', 'APPROVED', 'RESOLVED', 'REJECTED'));
ALTER TABLE warranty_claims ADD COLUMN decision_note text;
-- Still one live claim per original repair, whichever review state it is in.
DROP INDEX warranty_claims_one_open;
CREATE UNIQUE INDEX warranty_claims_one_open ON warranty_claims (original_repair_id)
    WHERE status IN ('OPEN', 'INFO_REQUESTED', 'APPROVED');

-- ---- Support: assignment and the conversation --------------------------------------------------------------
ALTER TABLE support_tickets
    ADD COLUMN assigned_to uuid REFERENCES admin_users (id),
    ADD COLUMN updated_at  timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN closed_at   timestamptz;

CREATE TABLE ticket_messages (
    id         bigserial PRIMARY KEY,
    ticket_id  text NOT NULL REFERENCES support_tickets (id),
    admin_id   uuid REFERENCES admin_users (id),
    -- CUSTOMER replies go to the customer's app; INTERNAL notes are staff-only.
    visibility text NOT NULL CHECK (visibility IN ('CUSTOMER', 'INTERNAL')),
    body       text NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ticket_messages_idx ON ticket_messages (ticket_id, id);
CREATE TRIGGER ticket_messages_immutable
    BEFORE UPDATE OR DELETE ON ticket_messages
    FOR EACH ROW EXECUTE FUNCTION forbid_row_changes();
