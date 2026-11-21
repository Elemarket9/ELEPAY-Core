CREATE TABLE ledger_operation (
    id uuid PRIMARY KEY,
    request jsonb NOT NULL
);

CREATE TABLE ledger_move (
    operation_id uuid NOT NULL REFERENCES ledger_operation(id),
    line integer NOT NULL,
    source text NOT NULL,
    destination text NOT NULL,
    pesewas bigint NOT NULL CHECK (pesewas > 0),
    PRIMARY KEY (operation_id, line),
    CHECK (source <> destination)
);

CREATE VIEW ledger_balance AS
SELECT account, sum(delta) AS pesewas
FROM (
    SELECT destination AS account, pesewas AS delta FROM ledger_move
    UNION ALL
    SELECT source AS account, -pesewas AS delta FROM ledger_move
) entries
GROUP BY account;
