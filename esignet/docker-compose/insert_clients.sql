INSERT INTO esignet.client_detail (
    id, name, rp_id, logo_uri, redirect_uris, claims, acr_values, public_key, public_key_hash, grant_types, auth_methods, status, cr_dtimes
) VALUES 
('mosip-uin-deletion-rp', 'MOSIP UIN Deletion Portal', 'mosip-uin-deletion-rp', 'http://localhost:8081/logo.png', '["http://localhost:8081/delete/callback","http://localhost:3000/userprofile"]', '["openid","profile"]', '["mosip:idp:acr:generated-code"]', 'dummy_pub_key_1', 'dummy_pub_key_hash_1', '["authorization_code"]', '["private_key_jwt"]', 'ACTIVE', CURRENT_TIMESTAMP),
('_UgkpFCOsqoxsbLfywjXFuVRYZaHeYK6l0GmxMg3Rg8', 'MOSIP Relying Party', 'mock-relying-party-ui', 'http://localhost:3000/logo.png', '["http://localhost:8081/delete/callback","http://localhost:3000/userprofile"]', '["openid","profile"]', '["mosip:idp:acr:generated-code"]', 'dummy_pub_key_2', 'dummy_pub_key_hash_2', '["authorization_code"]', '["private_key_jwt"]', 'ACTIVE', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO UPDATE SET 
redirect_uris = EXCLUDED.redirect_uris,
status = 'ACTIVE';
