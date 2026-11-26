CREATE TABLE marketplace_seller_directory (
    marketplace_id uuid NOT NULL,
    external_id varchar(128) NOT NULL,
    seller_id uuid NOT NULL,
    managed boolean NOT NULL,
    evidence_reference varchar(200),
    PRIMARY KEY (marketplace_id, external_id),
    UNIQUE (marketplace_id, seller_id),
    FOREIGN KEY (marketplace_id, seller_id) REFERENCES marketplace_seller(marketplace_id, seller_id)
);
