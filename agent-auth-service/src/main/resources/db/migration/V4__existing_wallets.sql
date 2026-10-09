-- Existing accounts receive no new-registration promotion.
INSERT INTO billing_wallet(user_id,free_images)
SELECT public_user_id,0 FROM app_user;
