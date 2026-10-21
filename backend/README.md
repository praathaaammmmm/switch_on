# Switch On API

Customer backend for the Switch On Android app: guest sessions, catalogue and pricing, bookings, repair tracking,
quotes, payments (recorded, not processed), invoices, warranty claims and support tickets.

Kotlin + Ktor 3 on JDK 21, PostgreSQL 16, Flyway migrations, JSON over HTTP.

## Run it locally

Needs Docker (with Compose) and JDK 21.

```sh
cd backend
./dev up        # creates .env with random secrets on first run, builds, starts Postgres + API, waits until healthy
```

- API: <http://localhost:8080/health>
- Postgres: `localhost:5433`, database `switchon`, user `switchon`, password in `backend/.env`

Other commands:

| Command | What it does |
|---|---|
| `./dev down` | Stop the stack (data is kept in the `pgdata` volume) |
| `./dev logs` | Follow the API log |
| `./dev psql` | Open `psql` on the database |
| `./dev token` | Print the internal dev-tools token |
| `./dev reset` | Delete the database volume (asks first) |
| `./dev test` | Run all tests (starts Postgres if needed) |

The API is compiled on the host (`./gradlew installDist`) and copied into a slim JRE image, so the image build needs
no network access. (On this machine containers can't resolve DNS, which rules out building inside Docker.)

## Configuration

Everything comes from environment variables. Nothing secret is committed: `.env` is gitignored and
`.env.example` holds placeholders only.

| Variable | Required | Meaning |
|---|---|---|
| `DATABASE_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://db:5432/switchon` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | yes | Database credentials |
| `APP_ENV` | no | `production` (default) or `development` |
| `INTERNAL_API_TOKEN` | no | Enables `/internal/v1` **only** when `APP_ENV=development` |
| `SEED_DEMO_DATA` | no | `true` gives each new guest the demo repairs (development only) |
| `PORT` | no | Listen port, default 8080 |

Compose also reads `POSTGRES_PASSWORD`, `DB_PORT` and `API_PORT` from `.env`.

## Database and migrations

Flyway runs on startup from `src/main/resources/db/migration`:

- `V1__schema.sql`: all tables, constraints and indexes.
- `V2__reference_data.sql`: catalogue (categories, brands, models, issues, INR base prices, turnaround),
  service areas (Bengaluru first), pickup windows, verified repair shops and pickup partners.

Add a change as a new `V3__…sql` file; never edit an applied migration.

Notes on the model:

- **Guests.** The app generates an installation UUID on first launch. `POST /v1/guest-sessions` maps it to a
  `customers` row (`kind = GUEST`) and issues a bearer token, stored only as a SHA-256 hash in `sessions`. Every
  repair, address, claim and ticket belongs to a customer, not to the installation. A future OTP login can merge a guest by
  setting `customers.merged_into_id` (sessions already follow that pointer) and re-parenting rows.
- **Money** is whole rupees. Estimates, quote totals and payment amounts are computed on the server. Request
  bodies have no price fields, and unknown fields are ignored.
- **Time** is `timestamptz` in UTC. Pickup slot dates and hours are Asia/Kolkata calendar values.
- **History** lives in `repair_status_events` and is append-only: a trigger rejects any `UPDATE` or `DELETE`. Each
  event records the timestamp, actor type (`CUSTOMER`, `SYSTEM`, `PARTNER`, `SHOP`, `INTERNAL`), the
  customer-facing message, and JSON metadata.
- **Idempotency.** Bookings, claims and tickets carry a client `clientRequestId` (unique per customer). A payment
  carries an `idempotencyKey`, and `payment_records.repair_id` is unique, so a repair can be paid only once even
  under concurrent requests.

## Repair lifecycle

```
DRAFT → PICKUP_REQUESTED → PICKUP_SCHEDULED → PARTNER_ASSIGNED → PICKED_UP → AT_SHOP → DIAGNOSED →
QUOTE_PENDING → REPAIRING → QUALITY_CHECK → RETURN_SCHEDULED → DELIVERED → PAYMENT_PENDING →
PAYMENT_COMPLETED → WARRANTY_ACTIVE
```

Side paths:

- `CANCELLED`: from any state before `PICKED_UP`.
- `PICKUP_FAILED`: back to `PICKUP_SCHEDULED` on reschedule, or `CANCELLED`.
- `QUOTE_DECLINED` and `REPAIR_NOT_POSSIBLE`: lead to `RETURN_SCHEDULED`, then `DELIVERED`, with nothing to pay.

Warranty claim jobs end at `DELIVERED` and resolve the claim. The full transition table is in `Status.kt`. Every
change goes through it, under a row lock.

Enforced rules:

- A quote is decided once: `409 quote_already_decided`.
- A payment is recorded once: the same key returns the same result, and a different key gets `409 payment_already_recorded`.
- No cancellation after pickup: `409 cancel_not_allowed`.
- Warranty claims need a paid repair (`WARRANTY_ACTIVE`) within 90 days of delivery, and only one open claim at a time: `422 not_eligible`.
- Full pickup windows: `409 slot_full`. Addresses outside service areas: `422 not_serviceable`.

## API

Every `/v1` route except `POST /v1/guest-sessions` needs `Authorization: Bearer <token>`. A request for another
customer's data returns `404`. Errors look like `{"error": {"code": "slot_full", "message": "…"}}`, and the message
is safe to show customers.

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/guest-sessions` | `{installationId}` → `{token, profile, created}` |
| GET | `/v1/me` | Profile (name and phone are filled from the first booking) |
| GET | `/v1/catalog/devices` | Categories with brands, price multipliers and models |
| GET | `/v1/catalog/issues` | Issues with per-device base price and turnaround, plus served pincode prefixes |
| POST | `/v1/estimates` | `{deviceCategory, issue, brand?}` → low, high, turnaround |
| GET | `/v1/serviceability?pinCode=560038` | `{serviceable, city}` |
| GET | `/v1/pickup-slots` | Next 3 days of 2-hour windows with remaining capacity (Asia/Kolkata) |
| GET, POST | `/v1/addresses` | List or add (serviceable pincodes only) |
| DELETE | `/v1/addresses/{id}` | Soft delete |
| POST | `/v1/addresses/{id}/default` | Make default |
| POST | `/v1/repairs` | Book. `201` when new, `200` when replaying the same `clientRequestId` |
| GET | `/v1/repairs`, `/v1/repairs/{id}` | Full repair with status events, handovers, quote, payment and warranty |
| POST | `/v1/repairs/{id}/cancel` | `{reason}` |
| POST | `/v1/repairs/{id}/reschedule` | `{slot: {date, startHour}}` |
| POST | `/v1/repairs/{id}/quote-decision` | `{decision: APPROVE or DECLINE, reason?}` |
| POST | `/v1/repairs/{id}/payment` | `{method: UPI, CARD or CASH, idempotencyKey, upiId?, cashConfirmed?}`. Simulated; amount set by the server |
| GET | `/v1/repairs/{id}/invoice` | GST tax invoice, available after payment |
| GET, POST | `/v1/warranty-claims` | Claim creates a ₹0 claim job with free pickup |
| GET, POST | `/v1/support-tickets` | `{clientRequestId, repairId?, topic, detail}` |

### Development-only routes

Mounted at `/internal/v1` only when `APP_ENV=development` and `INTERNAL_API_TOKEN` is set. Each needs the
`X-Internal-Token` header, compared in constant time. With `APP_ENV=production` they don't exist (404). They stand
in for the partner and shop apps:

| Method | Path | Does |
|---|---|---|
| POST | `/internal/v1/repairs/{id}/advance` | Next happy-path step: assigns a partner and seal, hands over to a shop, diagnoses, sends a quote, delivers |
| POST | `/internal/v1/repairs/{id}/quote` | Sends a quote with custom line items |
| POST | `/internal/v1/repairs/{id}/pickup-failed` | Marks the pickup missed |
| POST | `/internal/v1/repairs/{id}/not-possible` | Shop can't repair it |
| POST | `/internal/v1/customers/{id}/seed-demo` | Adds the five demo journeys to a customer |

```sh
curl -X POST localhost:8080/internal/v1/repairs/SO-123456/advance -H "X-Internal-Token: $(./dev token)"
```

## Tests

```sh
./dev test
```

35 tests:

- **`PricingTest`, `StatusTest`:** estimate rounding, GST split, the transition table, and cancellation only before pickup.
- **`ServicesTest`**, against a real database:
  - booking, replay and validation; slot capacity and serviceability
  - quote decided once; declined and not-possible returns; missed pickup
  - payment idempotency, including 4 concurrent payment threads
  - warranty eligibility and expiry (with a movable clock)
  - guest data isolation; the same installation keeping its history
  - append-only events; idempotent tickets; the demo seed
- **`ApiTest`:** the whole journey over HTTP: book, replay, advance, approve once, pay once, invoice, claim, ticket.
  Also auth and isolation, 4xx shapes, and internal routes being refused or absent in production.

Each database test class creates its own throwaway database (`switchon_test_<random>`) on the Postgres in
`TEST_DB_URL`, migrates it, and drops it when done. `./dev test` passes the local container's credentials.
