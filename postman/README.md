# Postman collections

Two collections for checking every API in the flow.

- **esignet.postman_collection.json** — the full eSignet OIDC flow (csrf →
  oauth-details → send-otp → authenticate → auth-code → token → userinfo)
- **deletion-service.postman_collection.json** — the deletion service: the
  page API (start, status, retry) and the token-secured direct API (check, execute)

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

Both read their keys and URLs from `postman/.env` (template:
[.env.example](.env.example)); without it they use the local-development keys,
which are git-ignored — see the main README to restore them on a fresh clone.

## eSignet collection — run 1 → 7 top to bottom

Each step feeds the next through collection variables (`txn`, `odHash`, `code`,
`accessToken`). Set `uin` to a seeded UIN first. OTP is **111111**. Only step 6
needs `clientAssertion` (above).

- After **step 5** you have an authorization `code` — copy it into the deletion
  collection's `code` variable to drive the page API (B below). Don't run steps
  6–7 first: the code is single-use.
- **Step 7 userinfo** returns a signed JWT; the test script decodes its claims to
  the Postman console (**View → Show Postman Console**). It carries the plain
  UIN as `individual_id`, while `sub` stays a pairwise pseudonym.

**If a POST returns 403:** it's the CSRF cookie. eSignet sets an `XSRF-TOKEN`
cookie that must accompany the token header. In Postman, open **Cookies** (under
the Send button) and make sure `localhost` is allowed to store cookies.

## Deletion collection — two ways to test

**A. Directly against the deletion service (8096)** — paste a `jwt` from
`mint_jwt.py <uin>`, then run:
- `check` → AVAILABLE / NO_DATA_AVAILABLE / ALREADY_DELETED for that UIN
- `execute` → deletes and returns the per-module status (permanent)
- `execute WITHOUT token` → 401 (shows the service is locked down)

**B. Through the page API (8096), the real path** — paste a fresh eSignet
`code` (from eSignet step 5) into `code`, then run:
- `start` → the service does the token exchange + userinfo, resolves the UIN,
  deletes, and returns `{transactionId, status, maskedUin}` (auto-saved
  `transactionId`)
- `status` / `retry` → poll or re-run within the 5-minute window

## Notes

- Deletions are permanent per UIN; after one, that UIN reports `ALREADY_DELETED`.
  Use UINs from `../seed/manifest.json` you haven't consumed.
- Everything must be running: `..\start-all.ps1` brings the whole stack up.
