-- Register the "Delete my UIN" page as a relying party in the local mock eSignet.
--
-- The relying party is the delete-uin page and the deletion service behind it
-- (which redeems the code) -- not the landing page. The landing page only carries a link; it
-- never talks to eSignet, so it needs no client of its own. What must be
-- registered is the origin eSignet is allowed to redirect BACK to.
--
-- This file is a TEMPLATE: the public_key placeholder below is replaced by the
-- public JWK of your local client key. Don't apply it directly. Run
--   python collab-ui/local-dev/local_keys.py
-- which creates the local keys (once) and writes the filled-in copy to
-- collab-ui/local-dev/keys/register-client.sql, then apply that copy:
--   Get-Content collab-ui/local-dev/keys/register-client.sql | docker compose -f esignet/docker-compose/docker-compose.yml exec -T database psql -U postgres -d mosip_esignet
-- start-all.ps1 does both.

INSERT INTO esignet.client_detail (
    id, name, rp_id, logo_uri, redirect_uris, claims, acr_values,
    public_key, public_key_hash, grant_types, auth_methods, status, cr_dtimes
) VALUES (
    'mosip-collab-delete-uin-client',
    -- Shown on the eSignet login screen: "<name> is requesting authentication".
    'MOSIP Collab',
    'mosip-collab',
    -- Shown beside the eSignet logo. eSignet fits it into a small rounded square,
    -- so the square MOSIP mark is used rather than the wide wordmark; it is the
    -- same image the Collab pages already load.
    'https://raw.githubusercontent.com/mosip/documentation/1.2.0/docs/_images/mosip-favicon.png',
    -- Every origin eSignet may redirect back to. 5501 serves the delete-uin page.
    '["http://localhost:5501/","http://127.0.0.1:5501/"]',
    -- individual_id is the claim that carries the resident's UIN; it is what the
    -- deletion service reads from /userinfo.
    '["individual_id","name","phone_number","email","gender","birthdate","address","picture"]',
    -- The mock plugin's reliable factor is the generated OTP. Requesting an ACR
    -- the client is not registered for makes eSignet reject the authorize call.
    '["mosip:idp:acr:generated-code"]',
    -- Public JWK of the client key; private_key_jwt proves possession of it.
    '__RP_PUBLIC_JWK__',
    'delete-uin-pub-key-hash',
    '["authorization_code"]',
    '["private_key_jwt"]',
    'ACTIVE',
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO UPDATE SET
    name          = EXCLUDED.name,
    logo_uri      = EXCLUDED.logo_uri,
    redirect_uris = EXCLUDED.redirect_uris,
    claims        = EXCLUDED.claims,
    acr_values    = EXCLUDED.acr_values,
    public_key    = EXCLUDED.public_key,
    auth_methods  = EXCLUDED.auth_methods,
    status        = 'ACTIVE';

SELECT id, status, redirect_uris FROM esignet.client_detail WHERE id = 'mosip-collab-delete-uin-client';
