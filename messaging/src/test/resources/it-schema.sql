-- Stand-in for a consumer's own business table in the inbox tests.
CREATE TABLE IF NOT EXISTS it_effects (
    id          bigserial PRIMARY KEY,
    event_id    uuid      NOT NULL
);
