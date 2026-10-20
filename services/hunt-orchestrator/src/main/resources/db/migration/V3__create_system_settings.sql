-- Runtime settings changed from the Admin panel (tech doc section 5); application.yaml holds the defaults.
CREATE TABLE system_settings (
    key        TEXT PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
