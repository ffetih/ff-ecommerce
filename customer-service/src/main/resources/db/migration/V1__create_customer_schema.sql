CREATE TABLE customers (
    id                uuid           PRIMARY KEY,
    email             varchar(320)   NOT NULL,
    full_name         varchar(200)   NOT NULL,
    shipping_address  varchar(500)   NOT NULL,
    status            varchar(16)    NOT NULL CHECK (status IN ('ACTIVE', 'DEACTIVATED')),
    created_at        timestamptz    NOT NULL,
    updated_at        timestamptz    NOT NULL,
    version           bigint         NOT NULL DEFAULT 0,
    -- Emails are stored normalized (trimmed, lower case), so a plain unique constraint is enough.
    CONSTRAINT customers_email_unique UNIQUE (email)
);
