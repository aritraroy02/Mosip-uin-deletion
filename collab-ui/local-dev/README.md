# Local development harness for the static-page charts

Runs [`charts/landing-page`](../landing-page) and [`charts/delete-uin`](../delete-uin)
against the mock eSignet stack in [`esignet/docker-compose`](../../esignet/docker-compose),
so clicking **Delete my UIN** performs a real OIDC redirect.

Neither chart is modified. Their `values.yaml` files still hold the production
Collab values; everything here is a local overlay.

## Why the page was stuck on "Redirecting to eSignet"

The pages are Helm templates. Their configuration arrives as `{{ .Values.x.y }}`
placeholders that Helm substitutes at install time. Opened straight from the
repo, nothing is substituted, and `delete-uin-index.html` notices:

```js
var UNRENDERED = looksUnrendered(CFG.esignet.authorizeUrl);
...
if (UNRENDERED) { CFG.ui.demoMode = true; }
```

In demo mode `goToESignet()` takes its demo branch and never calls
`window.location.assign()`. The page was working as designed — it simply had no
real authorize URL to redirect to. `render.py` supplies one.

## Setup

### 1. Start the eSignet stack

```powershell
docker compose -f esignet\docker-compose\docker-compose.yml up -d
```

Wait for the `esignet` container to report healthy (its healthcheck polls
`/v1/esignet/actuator/health`).

### 2. Register the relying party

```powershell
Get-Content charts\local-dev\register-client.sql | docker compose -f esignet\docker-compose\docker-compose.yml exec -T database psql -U postgres -d mosip_esignet
```

This creates the OIDC client `mosip-collab-delete-uin-client`. It is idempotent
— re-running it updates the existing row rather than failing.

**The relying party is the delete-uin page, not the landing page.** The landing
page only carries a link and never talks to eSignet, so it needs no client of
its own. What has to be registered is the origin eSignet is permitted to
redirect back to: `http://localhost:5501/`.

### 3. Create a resident to log in as

The OTP login resolves an identity in mock-identity-system. Add one through its
API (see [`esignet/postman-collection`](../../esignet/postman-collection),
*User Mgmt → Mock → Create User*), or reuse `1234567890`, which
[`init.sql`](../../esignet/docker-compose/init.sql) seeds with phone and email
verified claims.

### 4. Render and serve

```powershell
cd charts\local-dev
.\serve.ps1
```

Then open **http://localhost:5500/** and click **Delete my UIN**.

Serve over `http://localhost`, never `file://` — eSignet redirects back to a
registered origin, and a `file://` page has no origin to return to.

## What should happen

1. The landing page's header button goes to `http://localhost:5501/`.
2. That page shows the "Redirecting to eSignet" interstitial and **redirects**
   to `http://localhost:3000/authorize?...` with `client_id`, `state`, `nonce`,
   `redirect_uri`, `acr_values` and the `claims` request.
3. eSignet asks for a UIN/VID and sends a one-time password. The mock OTP is
   `111111`.
4. After consent, eSignet redirects to `http://localhost:5501/?code=...&state=...`.
5. The page validates `state`, then calls the deletion service's `start`
   endpoint.

**Step 5 is expected to fail right now** — the deletion service does not
implement `/v1/delete-uin/start` yet, so the page lands on "Something went
wrong" with the network error shown beneath it (`ui.showTechnicalErrors` is
`true`). That failure is the confirmation that the eSignet leg worked: reaching
it at all means the redirect, the login and the callback all succeeded.

To see the authorization code, open DevTools before clicking — the page scrubs
it from the address bar with `history.replaceState` as soon as it is read.

## Files

| File | Purpose |
| --- | --- |
| `values-local.json` | Local overrides substituted into the chart placeholders |
| `render.py` | Does what Helm would do; writes `dist/` (stdlib only, no Helm) |
| `serve.ps1` | Renders, then serves 5500 and 5501 in their own windows |
| `register-client.sql` | Registers the OIDC client in `esignet.client_detail` |
| `esignet-rp-private-key.pem` | RSA private key matching the client's registered JWK |
| `dist/` | Rendered output — regenerated on every run, do not edit |

## About the keypair

`register-client.sql` registers the same RSA public JWK the previous portal
client used, so `esignet-rp-private-key.pem` here is its matching private key.
The client is registered with `auth_methods: ["private_key_jwt"]`, which means
the deletion service will authenticate to the token endpoint by signing a JWT
with that key rather than sending a shared secret. Keeping the existing pair
means that step is already prepared.

This is a mock-environment development key that was already committed to this
repository's history. It is not a production secret, but do not reuse it in one.

## Local settings that differ from the chart defaults, and why

| Value | Chart default | Local | Reason |
| --- | --- | --- | --- |
| `esignet.acrValues` | OTP + biometrics + static-code | OTP only | eSignet rejects an authorize request for an ACR the client is not registered for; the mock plugin's reliable factor is the generated OTP |
| `esignet.usePkce` | `true` | `false` | Keeps the first integration to one moving part. The token exchange would otherwise also have to carry the `code_verifier`. Turn it back on once the deletion service handles it |
| `esignet.maxAge` | `21` | *(empty)* | 21 seconds is long enough for the Figma flow but not for typing an OTP by hand |
| `esignet.claims` | `name` only | adds `individual_id` (essential) | `individual_id` is the claim that resolves to the resident's UIN — the input the deletion service needs |
| `ui.demoMode` | `false` | `false` | Unchanged; it was only ever forced on by the un-rendered placeholders |
