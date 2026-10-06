# delete-uin

The **Delete my UIN** page for the MOSIP Collab environment, built from the
"Delete My UIN" page of the Collab revamp Figma file.

A resident clicks **Delete my UIN** in the Collab header, verifies with eSignet
(UIN/VID/Handle plus a one-time password), and this page follows the deletion job
to completion. It is static HTML served by nginx from a ConfigMap — the same
pattern as `collab-ui/landing-page`.

## Flow

```
collab.mosip.net                delete-uin.collab.mosip.net           eSignet          deletion service
      |                                    |                             |                    |
 "Delete my UIN"  ─────────────────────▶  Redirecting to eSignet         |                    |
 (hover tooltip explains                   | ── GET /authorize ───────▶  OTP on UIN/VID/      |
  what deletion costs)                     |    state + nonce + PKCE     Handle                |
                                           ◀── redirect ?code&state ──── |                    |
                                           |                                                  |
                                           | ──── POST startEndpoint ────────────────────────▶ |
                                    Deleting your identity data              (job created)     |
                                      ring advances 0→100%                                     |
                                           | ──── GET statusEndpoint (poll) ─────────────────▶ |
                                           |                                                   |
                    ┌──────────────────────┼───────────────────────┐                           |
              Deletion complete    Something went wrong    Retry window expired                 |
              (green ring,         (Retry deletion +       (Back to MOSIP Collab)               |
               all data removed)    countdown) ── POST retryEndpoint ─────────────────────────▶ |
```

Two separate backends are involved and they are **not** the same service:

- **eSignet** authenticates the resident. The page only ever talks to its
  `/authorize` endpoint, in the browser, by redirect.
- **The deletion service** does the work. It receives the authorization code,
  exchanges it for a token (using the client secret / private key that only it
  holds), resolves the subject, and runs the deletion as a **resumable job**.

The page never sees a token and never holds a secret. Everything under
`.Values.esignet` is rendered into a ConfigMap and served to the browser, so only
public OIDC parameters belong there.

## Screens

| Status from the backend | What the resident sees |
| --- | --- |
| *(before the redirect)* | "Redirecting to eSignet" interstitial |
| `IN_PROGRESS` | "Deleting your identity data" — orange ring at N%, "Deletion in progress…" |
| `COMPLETED` | "Deletion complete" — green ring at 100%, green panel, **Back to MOSIP Collab** |
| `FAILED` | "Something went wrong" — amber panel, **Retry deletion** + "Retry window closes in mm:ss" |
| `EXPIRED` | "Retry window expired" — amber panel with a clock, **Back to MOSIP Collab** |

Aliases are accepted so the contract is not brittle: `COMPLETE`/`DELETED`/`SUCCESS`
map to `COMPLETED`, `ERROR`/`INTERRUPTED` map to `FAILED`, and
`RETRY_WINDOW_EXPIRED` maps to `EXPIRED`.

The `EXPIRED` screen is reused for every "you need to authenticate again"
situation — eSignet cancelled, `state` mismatch, no session in this browser, a
round trip older than 30 minutes, or a `401`/`403` from the deletion service —
because its copy ("click *Delete my UIN* again on the Collab homepage") is exactly
the right instruction in all of them.

## What the deletion service has to expose

Every call carries `Content-Type: application/json` and
`X-Request-Source: <deleteService.requestSource>`. CORS must allow the page's
origin (`https://delete-uin.collab.mosip.net`) for `GET` and `POST` plus those
headers.

### `POST {{ deleteService.startEndpoint }}`

Called once, as soon as eSignet redirects back. Exchanges the code and creates
the job.

```jsonc
// request
{ "code": "...", "state": "...", "redirectUri": "https://delete-uin.collab.mosip.net/", "codeVerifier": "..." }

// response 200
{
  "transactionId": "txn-8f21...",
  "maskedUin": "UIN •••• •••• 9134",   // or "uin": "...", which the page masks itself
  "status": "IN_PROGRESS",
  "progress": 0,                        // 0-100
  "retryExpiresAt": null                // ISO-8601, only meaningful once status is FAILED
}
```

A `401`/`403` here means the eSignet proof was rejected — the page shows the
"retry window expired" screen and sends the resident back to the homepage.

### `GET {{ deleteService.statusEndpoint }}?transactionId=<id>`

Polled every `deleteService.pollIntervalSeconds` while the job is running.
Returns the same shape as `start`. A transient failure is not fatal — the page
keeps polling. A `404`/`410` means the job is gone, and the resident is told to
start again.

### `POST {{ deleteService.retryEndpoint }}`

```jsonc
// request
{ "transactionId": "txn-8f21..." }

// response 200 — same shape as start/status
```

Resumes a failed job from where it stopped. The panel copy promises that "data
already deleted stays deleted", so this must be idempotent and must not restart
from zero.

**`retryExpiresAt`** drives the "Retry window closes in mm:ss" countdown. When it
reaches zero the page moves itself to the `EXPIRED` screen without another call.

## eSignet client registration

Register an OIDC client whose redirect URI matches `esignet.redirectUri`
**exactly**. The page sends `response_type=code` with `state`, `nonce` and — when
`esignet.usePkce` is true and the page is served over HTTPS — a PKCE `S256`
challenge. `acr_values`, `claims`, `claims_locales`, `display`, `prompt`,
`max_age` and `ui_locales` all come from values and are only sent when non-empty.

PKCE degrades gracefully: on a non-secure origin `crypto.subtle` is unavailable,
so the page logs a warning and proceeds without a challenge. Serve over HTTPS.

## Safety behaviour worth knowing about

- `state` is generated per attempt, kept in `sessionStorage`, and compared on
  return. A mismatch, a missing session, or a round trip older than 30 minutes
  aborts before any backend call is made.
- The PKCE `code_verifier` stays in `sessionStorage` and is only ever sent to the
  deletion service, never to eSignet.
- The query string is scrubbed with `history.replaceState` as soon as the code is
  read, so the authorization code does not sit in the address bar or in history.
- The `transactionId` is kept in `sessionStorage` too, so a refresh mid-deletion
  **resumes the job** instead of sending the resident back through eSignet.
- `<meta name="robots" content="noindex, nofollow">` keeps the page out of search
  results.

## Previewing without a backend

Set `ui.demoMode: true` to walk the whole flow with no backend at all:

| URL | Screen |
| --- | --- |
| `/` | interstitial → progress → **Deletion complete** |
| `/?preview=failed` | progress → **Something went wrong** with a live countdown |
| `/?preview=expired` | progress → **Retry window expired** |

It logs a console warning while on. **`ui.demoMode` must be `false` in every real
environment.**

### Opening the chart file directly

`delete-uin-index.html` is a Helm template, but it is written so that it is still
**valid JavaScript before substitution** — every `{{ .Values.x }}` inside
`<script>` is quoted, and the booleans/numbers are coerced at runtime. Open it
straight from the repo (VS Code Live Preview, `file://`, any static server) and
the page detects that nothing was substituted, neutralises the placeholder
links, forces demo mode, and shows a "Template preview" badge in the corner.
`?preview=failed` and `?preview=expired` work there too.

If you add a new value to the script block, quote it — otherwise the whole
inline script fails to parse un-rendered and the page freezes on the
"Redirecting to eSignet" interstitial.

## Configuration

| Value | Default | Purpose |
| --- | --- | --- |
| `collab.homepageUrl` | `https://collab.mosip.net` | Header logo + "Back to MOSIP Collab" |
| `esignet.authorizeUrl` | `https://esignet.collab.mosip.net/authorize` | eSignet OIDC authorize page |
| `esignet.clientId` | `mosip-collab-delete-uin-client` | Registered OIDC client |
| `esignet.redirectUri` | `https://delete-uin.collab.mosip.net/` | Must match the client exactly |
| `esignet.acrValues` | OTP, biometrics, static code | Auth factors, most preferred first |
| `esignet.usePkce` | `true` | Send an S256 code challenge |
| `deleteService.startEndpoint` | `…/v1/delete-uin/start` | Creates the job |
| `deleteService.statusEndpoint` | `…/v1/delete-uin/status` | Polled for progress |
| `deleteService.retryEndpoint` | `…/v1/delete-uin/retry` | Resumes a failed job |
| `deleteService.pollIntervalSeconds` | `2` | Poll cadence |
| `ui.showTechnicalErrors` | `true` | Expose the raw error payload |
| `ui.demoMode` | `false` | Run the flow with no backends |
| `istio.host` | `delete-uin.collab.mosip.net` | Ingress host |

The remaining values (image, probes, resources, metrics, affinity) are the
standard nginx-from-ConfigMap deployment options shared by the other static
page charts in this repo.

## Install

```sh
helm install delete-uin mosip/delete-uin \
  --namespace collab \
  --set esignet.clientId=<oidc-client-id> \
  --set esignet.redirectUri=https://delete-uin.collab.mosip.net/ \
  --set deleteService.startEndpoint=https://api.collab.mosip.net/v1/delete-uin/start
```

The Collab landing page links here from the header button and the mobile menu via
`collab.deleteUinUrl` in `collab-ui/landing-page/values.yaml`.

## Known deviations from the Figma

- The header's faint wave texture is a 687 KB PNG in Figma. The page reproduces
  the navy gradient and the blue radial highlight in CSS and omits the texture,
  rather than inlining that much data into the ConfigMap.
- The "Deletion complete" mock still shows `54%` on a green ring; the page shows
  `100%`, since the label next to it reads "All data removed".
- The ring percentage is specified as Inter Semibold. The page uses Montserrat at
  the same size and colour rather than loading a second font family.
