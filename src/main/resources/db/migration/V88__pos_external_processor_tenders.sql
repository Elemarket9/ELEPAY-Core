-- V88: External processor POS tender types (EXTERNAL_MOMO, EXTERNAL_CARD, EXTERNAL_HUBTEL)
-- Allows restaurants to record payments settled on their own MoMo SIM, Hubtel, or external card
-- terminals without ELEPAY holding, clearing, or settling those funds.

ALTER TABLE pos_tender_payment DROP CONSTRAINT IF EXISTS chk_pos_tender_type;
ALTER TABLE pos_tender_payment ADD CONSTRAINT chk_pos_tender_type
    CHECK (tender_type IN (
        'CASH', 'MOMO', 'CARD', 'WALLET', 'GHQR', 'BANK_TRANSFER',
        'EXTERNAL_MOMO', 'EXTERNAL_CARD', 'EXTERNAL_HUBTEL'
    ));
