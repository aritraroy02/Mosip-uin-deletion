INSERT INTO mockidentitysystem.verified_claim (
    id, individual_id, claim, trust_framework, detail, cr_by, cr_dtimes, is_active
) VALUES 
('claim_phone_1234567890', '1234567890', 'phone', 'mosip', '{"phone": "+919876543210"}', 'admin', CURRENT_TIMESTAMP, true),
('claim_email_1234567890', '1234567890', 'email', 'mosip', '{"email": "user@example.com"}', 'admin', CURRENT_TIMESTAMP, true)
ON CONFLICT (id) DO UPDATE SET 
detail = EXCLUDED.detail,
is_active = true;
