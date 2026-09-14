-- Register the "Delete my UIN" page as a relying party in the local mock eSignet.
--
-- The relying party is the delete-uin PAGE (and, later, the deletion service
-- behind it) -- not the landing page. The landing page only carries a link; it
-- never talks to eSignet, so it needs no client of its own. What must be
-- registered is the origin eSignet is allowed to redirect BACK to.
--
-- The public key below is the same RSA JWK the previous portal client used, so
-- the matching private key (charts/local-dev/esignet-rp-private-key.pem) still
-- works for the private_key_jwt token exchange the deletion service will need.
--
-- Apply with:
--   Get-Content charts/local-dev/register-client.sql | docker compose -f esignet/docker-compose/docker-compose.yml exec -T database psql -U postgres -d mosip_esignet

INSERT INTO esignet.client_detail (
    id, name, rp_id, logo_uri, redirect_uris, claims, acr_values,
    public_key, public_key_hash, grant_types, auth_methods, status, cr_dtimes
) VALUES (
    'mosip-collab-delete-uin-client',
    'MOSIP Collab - Delete my UIN',
    'mosip-collab',
    'http://localhost:5500/logo.png',
    -- Every origin eSignet may redirect back to. 5501 serves the delete-uin page.
    '["http://localhost:5501/","http://127.0.0.1:5501/"]',
    -- individual_id is the claim that resolves to the resident's UIN; it is what
    -- the deletion service will read from /userinfo once it is wired up.
    '["individual_id","name","phone_number","email","gender","birthdate","address","picture"]',
    -- The mock plugin's reliable factor is the generated OTP. Requesting an ACR
    -- the client is not registered for makes eSignet reject the authorize call.
    '["mosip:idp:acr:generated-code"]',
    '{"kty":"RSA","e":"AQAB","use":"sig","alg":"RS256","n":"ldqDC1avLKn_XeBUMJWUB-6p89SPvF6ZPXZbv5r4d0FbyYJMledt5X6BlfwJ3CCC4duwfDOi-0MsnT408w21jB1nnkR4vLv4ejpgAbpjoFL-zxY2yl5S1XlTR9v8rWKdtvkQqn6YbsBDg-pXgd7nvU67SwHl6zSkuPx2BrLyKqdf-bkpBv3q6lh0bw8oVyJMuEKir3JRgZeFtS5-leeXwVZ4CZgCISuMG0QXdt03bbRwqUD4bh2feIIZAMFCrlRybpIT_mFajqYIDem8Jwvpr57tRb6ZobKLQjDS8cks4MbFcAJ4clUtd19kUJiJ-o03L__5E0U9qy9F6xxQwxE3cQ"}',
    'delete-uin-pub-key-hash',
    '["authorization_code"]',
    '["private_key_jwt"]',
    'ACTIVE',
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO UPDATE SET
    redirect_uris = EXCLUDED.redirect_uris,
    claims        = EXCLUDED.claims,
    acr_values    = EXCLUDED.acr_values,
    public_key    = EXCLUDED.public_key,
    auth_methods  = EXCLUDED.auth_methods,
    status        = 'ACTIVE';

SELECT id, status, redirect_uris FROM esignet.client_detail WHERE id = 'mosip-collab-delete-uin-client';
