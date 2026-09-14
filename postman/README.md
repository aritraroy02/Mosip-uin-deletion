# Postman collections

Two collections for checking every API in the flow.

- **esignet.postman_collection.json** — the full eSignet OIDC flow (csrf →
  oauth-details → send-otp → authenticate → auth-code → token → userinfo)
- **deletion-service.postman_collection.json** — the JWT-secured deletion
  service (check, execute) plus the auth-gateway front door (start, status, retry)

Import both: Postman → **Import** → select the two files.

## The one thing Postman can't do: RS256 signatures

Two requests need a JWT signed with a private key, which Postman can't produce
in-app. Generate each with a helper and paste it into the matching collection
variable (both are short-lived — regenerate if you get 401/expired):

```powershell
# eSignet /token  ->  paste into the eSignet collection's `clientAssertion`
python postman\mint_client_assertion.py

# deletion service ->  paste into the deletion collection's `jwt`
python postman\mint_jwt.py 8617031759      # any seeded UIN
```

(They need Python with `cryptography`: `pip install cryptography`.)

## eSignet collection — run 1 → 7 top to bottom

Each step feeds the next through collection variables (`txn`, `odHash`, `code`,
`accessToken`). Set `uin` to a seeded UIN first. OTP is **111111**. Only step 6
needs `clientAssertion` (above).

- After **step 5** you have an authorization `code` — copy it into the deletion
  collection's `code` variable to drive the gateway.
- **Step 7 userinfo** returns a signed JWT; the test script decodes its claims to
  the Postman console (**View → Show Postman Console**). With the mock it holds
  only `sub` (a pseudonym) and `name`, *not* the UIN — that is expected, and is
  why the gateway maps the pseudonym back to the UIN.

**If a POST returns 403:** it's the CSRF cookie. eSignet sets an `XSRF-TOKEN`
cookie that must accompany the token header. In Postman, open **Cookies** (under
the Send button) and make sure `localhost` is allowed to store cookies. The
whole flow is also scripted and known-working in
[`scratchpad esignet_flow.py`] if you'd rather run it headless — see the repo's
`esignet` reference.

## Deletion collection — two ways to test

**A. Directly against the deletion service (8096)** — paste a `jwt` from
`mint_jwt.py <uin>`, then run:
- `check` → AVAILABLE / NO_DATA_AVAILABLE / ALREADY_DELETED for that UIN
- `execute` → deletes and returns the per-module status (permanent)
- `execute WITHOUT token` → 401 (shows the service is locked down)

**B. Through the gateway (8095), the real path** — paste a fresh eSignet `code`
(from eSignet step 5) into `code`, then run:
- `start` → the gateway does token+userinfo, resolves the UIN, mints the JWT,
  calls the deletion service, and returns `{transactionId, status, maskedUin}`
  (auto-saved `transactionId`)
- `status` / `retry` → poll or re-run within the 5-minute window

## Notes

- Deletions are permanent per UIN; after one, that UIN reports `ALREADY_DELETED`.
  Use UINs from `../seed/manifest.json` you haven't consumed.
- Everything must be running: `..\start-all.ps1` brings the whole stack up.
