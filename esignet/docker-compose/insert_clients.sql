INSERT INTO esignet.client_detail (
    id, name, rp_id, logo_uri, redirect_uris, claims, acr_values, public_key, public_key_hash, grant_types, auth_methods, status, cr_dtimes
) VALUES 
('mosip-uin-deletion-rp', 'MOSIP UIN Deletion Portal', 'mosip-uin-deletion-rp', 'http://localhost:8081/logo.png', '["http://localhost:8081/delete/callback","http://localhost:3000/userprofile"]', '["openid","profile"]', '["mosip:idp:acr:generated-code"]', 'dummy_pub_key_1', 'dummy_pub_key_hash_1', '["authorization_code"]', '["private_key_jwt"]', 'ACTIVE', CURRENT_TIMESTAMP),
('_UgkpFCOsqoxsbLfywjXFuVRYZaHeYK6l0GmxMg3Rg8', 'MOSIP Relying Party', 'mock-relying-party-ui', 'http://localhost:3000/logo.png', '["http://localhost:8081/delete/callback","http://localhost:3000/userprofile"]', '["openid","profile"]', '["mosip:idp:acr:generated-code"]', '{"kty":"RSA","e":"AQAB","use":"sig","alg":"RS256","n":"ldqDC1avLKn_XeBUMJWUB-6p89SPvF6ZPXZbv5r4d0FbyYJMledt5X6BlfwJ3CCC4duwfDOi-0MsnT408w21jB1nnkR4vLv4ejpgAbpjoFL-zxY2yl5S1XlTR9v8rWKdtvkQqn6YbsBDg-pXgd7nvU67SwHl6zSkuPx2BrLyKqdf-bkpBv3q6lh0bw8oVyJMuEKir3JRgZeFtS5-leeXwVZ4CZgCISuMG0QXdt03bbRwqUD4bh2feIIZAMFCrlRybpIT_mFajqYIDem8Jwvpr57tRb6ZobKLQjDS8cks4MbFcAJ4clUtd19kUJiJ-o03L__5E0U9qy9F6xxQwxE3cQ"}', '07beeca7ee935d54c647073514547735', '["authorization_code"]', '["private_key_jwt"]', 'ACTIVE', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET 
redirect_uris = EXCLUDED.redirect_uris,
status = 'ACTIVE';
