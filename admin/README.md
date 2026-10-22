# Switch On Admin

The staff dashboard for Switch On: a React + TypeScript web app for running repairs, pricing, partners, pickups,
quotes, payments, warranty claims, support and the team. It uses the existing Ktor/PostgreSQL backend through the
protected `/v1/admin/*` API. There is no second database or backend.

**Stack:** React 18, TypeScript, Vite, React Router, TanStack Query; Vitest and Testing Library for tests.

## Run it locally

You need Node 20+ and the backend running.

```sh
# 1. Backend: Postgres + API on :8080. On first run it creates backend/.env with random secrets,
#    including a development owner account.
cd backend
./dev up
./dev admin        # prints the dashboard URL, owner email and password

# 2. Dashboard on :5173
cd ../admin
npm install
npm run dev
```

Open http://localhost:5173 and sign in with the details from `./dev admin`.

In development, Vite proxies `/v1` to `http://localhost:8080`, so the dashboard and API share an origin and the
session cookie stays first-party. To point it elsewhere, copy `.env.example` to `.env.local` and set
`VITE_API_PROXY_TARGET`.

| Command | Does |
|---|---|
| `npm run dev` | Dev server with hot reload on :5173 |
| `npm test` | Unit and component tests (Vitest, jsdom) |
| `npm run typecheck` | TypeScript, no emit |
| `npm run build` | Type-check, then a production build in `dist/` |
| `npm run preview` | Serve the production build locally |

Backend tests for the admin API run with the rest of the backend suite (`cd backend && ./dev test`).

## Sections

| Section | What staff can do |
|---|---|
| Overview | See bookings, active repairs, pickups today, out for delivery, quotes awaiting customers, drafts, delayed repairs, revenue (today/7/30 days), refunds, unreconciled payments, open claims and tickets. Lists delayed repairs and recent admin activity. |
| Repairs | Search by ID, name, phone, brand or model. Filter by status, the Active or Delayed views, device and booking dates; filters live in the URL. Each repair shows a job card, the customer-visible timeline, the next statuses the lifecycle allows, assignment (partner, shop, technician), reschedule, cancel, internal notes, quote, payment, claim, tickets and its audit trail. |
| Customers | Search guests and registered customers. See profile, repair history, addresses (including deleted ones), claims and tickets. |
| Services & Pricing | Edit base price, diagnosis fee, turnaround, label and availability per device and repair. Offer new repairs, show or hide devices, edit issue labels, set brand multipliers, and view price history with estimate previews. |
| Partners | Manage repair shops (capabilities, capacity, active), technicians (shop, skills) and pickup partners (daily capacity). Each one's active jobs are listed. |
| Pickups & Areas | See each day's window load, pickups and deliveries. Manage service areas (pincode prefixes), window capacity and blackout dates. |
| Quotes | Browse drafts, quotes awaiting the customer, approved and declined quotes. Drafts are written on the repair (diagnosis, findings, part/labour/fee lines, warranty), then submitted. |
| Payments & Invoices | Payment records with totals, the GST invoice, refunds (capped at what's left to refund) and reconciliation. |
| Warranty Claims | Approve, request information, reject (which cancels or returns the claim job within the lifecycle), and resolve. |
| Support | Ticket queue (open/closed, mine/unassigned), assignment, customer-visible replies and internal notes. |
| Team & Access | Owner only: add staff, change roles, deactivate, set passwords. |
| Audit Log | Every admin change, filterable by record, staff member and action, with before and after values. |

## Roles

The server checks every change. The dashboard hides controls using the same permission list from `GET /v1/admin/me`.

| Role | Can change |
|---|---|
| Owner | Everything, including the team |
| Operations | Repairs (status, assignment, reschedule, cancel), quotes, pricing, partners, pickups and areas, claims, support |
| Support | Tickets, claims, internal notes on repairs |
| Finance | Refunds and reconciliation |
| Viewer | Nothing (read-only) |

Every role can read every section except Team & Access. The team always has at least one active owner, and nobody can
change their own role or deactivate themselves.

## Rules the backend enforces

- **Lifecycle.** Status changes go through the same transition table as the customer API (`backend/.../Status.kt`).
  The dashboard offers only next statuses the table allows, and some of those need something first:
  - **Partner assigned:** a pickup partner must be assigned.
  - **At repair shop:** a shop must be assigned.
  - **Quote sent:** only by submitting a quote.
  - **Approve or decline a quote:** only the customer can do this.
  - **Payment:** recorded only when the customer pays.
  - **Side effects:** each step's side effects (seal codes, handovers, diagnosis, delivery → payment) are recorded as
    they are for the partner and shop.
- **Prices.**
  - A change to a price, fee, turnaround, availability or brand multiplier needs a reason of at least 5 characters.
    Each changed field writes its own row to `service_price_history`, which is append-only.
  - Repairs keep the estimate they were booked with, and quotes keep their own line items, so past estimates and
    approved quotes never change.
  - Inactive services and hidden devices disappear from the customer catalogue and estimates.
- **Audit.**
  - Every admin change, sign-in, sign-out and failed sign-in writes a row to `admin_audit_log`, in the same
    transaction as the change.
  - A database trigger rejects `UPDATE` and `DELETE` on that table, on price history, internal notes, ticket
    messages and refunds.
- **Capacity.** Assignment checks the partner's daily capacity and the shop's capacity, capability and activity.
  Rescheduling checks blackouts and window capacity.

## Security

- **Passwords:** hashed with PBKDF2-HMAC-SHA512 (210,000 iterations, random salt; the JDK implementation). At least
  12 characters. Never logged or returned.
- **Sessions:**
  - A random 256-bit token in an `HttpOnly`, `SameSite=Strict` cookie scoped to `/v1/admin`, marked `Secure` in
    production.
  - Only its SHA-256 hash is stored. Sessions end after 12 hours, on sign-out, on deactivation, on a role change and
    on a password change.
- **CSRF:** besides `SameSite=Strict`, every change must carry `X-Requested-With: switchon-admin`. Other sites can't
  set that header without a CORS preflight, and the API allows none.
- **Throttling:** after 5 failed sign-ins in 15 minutes, further attempts for that email or client address are
  refused. Unknown emails take the same time and get the same answer as wrong passwords.
- **Caching:** admin responses are sent with `Cache-Control: no-store`.
- **Development owner:**
  - Created only when `APP_ENV=development` and both `ADMIN_OWNER_EMAIL` and `ADMIN_OWNER_PASSWORD` are set in
    `backend/.env`.
  - It is never overwritten, so a password changed in the dashboard sticks.
  - Production never seeds an account. Create the first owner with a one-off script or SQL, using a hash from
    `Passwords.hash`.
- **Never commit** `.env`, `.env.local`, passwords, tokens, database dumps, build output (`dist/`), `node_modules/`,
  APKs or signing keys. The root `.gitignore` covers these; `.env.example` files hold placeholders only.

## Production

Serve `dist/` and the API on one origin, for example behind a reverse proxy that sends `/v1/` to Ktor and
everything else to the static files (with an SPA fallback to `index.html`). Use HTTPS; the cookie is `Secure`
whenever `APP_ENV=production`. No CORS configuration is needed or wanted.

## Tests

`npm test` runs 17 tests:

- `lib/filters.test.ts`: URL ↔ filter round-trip, the active/delayed views, paging, resetting to page 1.
- `auth/roles.test.tsx`:
  - Owners see Team & Access and every control; viewers get neither.
  - Finance can refund but not change prices.
  - Sidebar attention counts.
- `pages/Login.test.tsx`: a wrong password shows the server's message and clears the field, and the dashboard header
  is sent. Signing in leaves the page.
- `pages/Repairs.test.tsx`: debounced search, the delayed view sent as its own flag, and filters read from the URL.
- `pages/Pricing.test.tsx`:
  - A price change stays disabled until there's a 5-character reason, and the reason is sent.
  - The form is read-only for roles without pricing access.
- `pages/Audit.test.tsx`: who, what and why, with expandable before and after values.

The backend's `AdminServicesTest`, `AdminApiTest` and `DelaysTest` cover these against a real database and over HTTP:

- login, cookies, CSRF, throttling, roles and revocation
- repair filtering
- lifecycle-checked status changes
- assignment rules
- price updates and historical pricing
- audit immutability
- refunds, claims, ticket replies, and the last-owner rule
