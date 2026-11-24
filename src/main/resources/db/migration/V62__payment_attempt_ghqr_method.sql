-- Allow 'ghqr' as a payment method on payment_attempt and checkout.
ALTER TABLE payment_attempt
    DROP CONSTRAINT IF EXISTS payment_attempt_method_check;

ALTER TABLE payment_attempt
    ADD CONSTRAINT payment_attempt_method_check
        CHECK (method IN ('wallet', 'momo', 'card', 'ghqr'));

ALTER TABLE checkout
    DROP CONSTRAINT IF EXISTS checkout_payment_method_check;

ALTER TABLE checkout
    ADD CONSTRAINT checkout_payment_method_check
        CHECK (payment_method IS NULL OR payment_method IN ('wallet', 'momo', 'card', 'ghqr'));
