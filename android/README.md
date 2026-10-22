# Switch On — Android customer app (prototype)

Native Kotlin + Jetpack Compose, with two flavors:

- **`prod`** (default): talks to the Switch On API in `../backend`. Repairs, quotes, payments, claims and tickets
  are stored on the server, so a guest sees the same history after restarting the app.
- **`demo`**: runs on in-memory sample data with no backend. Nothing persists. Installs alongside prod as `com.switchon.customer.demo`.

## Build and install

```sh
cd android
./gradlew :app:installProdDebug       # API build onto a phone or emulator (backend must be running: ../backend/dev up)
./gradlew :app:installDemoDebug       # sample-data build, no backend needed
./gradlew :app:assembleProdRelease    # minified, debug-signed: app/build/outputs/apk/prod/release/app-prod-release.apk
./gradlew :app:testProdDebugUnitTest  # view-model, repository and validation tests (JVM, no device needed)
```

Needs JDK 17+ (JDK 21 used here) and the Android SDK (platform 35). Point `local.properties` at your SDK
(`sdk.dir=...`) or set `ANDROID_HOME`.

### Local settings (`local.properties`, gitignored)

| Key | Default | Used by |
|---|---|---|
| `switchon.apiBaseUrl` | `http://10.0.2.2:8080/` | Debug builds (10.0.2.2 is the emulator's alias for the host). For a phone, use your computer's LAN IP |
| `switchon.internalToken` | empty | Debug builds: shows "Next update" in the tracker, which calls the backend's dev route. Get it with `../backend/dev token` |
| `switchon.releaseApiBaseUrl` | `https://api.switchon.example/` | Release builds (a placeholder until there's a real deployment) |

Cleartext HTTP is allowed only in debug builds, and only to `10.0.2.2` and `localhost` (`src/debug`).

## How the prod build works

- **Guest identity:** on first launch the app generates an installation UUID and keeps it, with the session token,
  in EncryptedSharedPreferences. After "Continue as guest" the app opens straight on Home next time.
- **Data:** Retrofit and OkHttp call `/v1`. On a 401 the app re-opens the session for the same installation, so it's
  the same customer and history. The server's catalogue replaces the bundled prices at startup, and the server
  computes every estimate and charge itself.
- **Offline:** each repairs response is cached in Room. With no connection, the last known repairs, addresses and
  profile still show, marked "Showing your last saved copy".
- **Queued actions:** a booking or support report made offline is saved and sent by WorkManager once there's a
  connection. Home shows it under "Waiting to send", including the reason if the server refuses it (e.g. the slot
  filled up). Each carries the request id the server de-duplicates on.
- **Never queued or retried automatically:** quote decisions and payments. A manual retry of a payment reuses its
  idempotency key, so the server can't record it twice.
- **Time:** the server stores UTC; the app always shows Asia/Kolkata, whatever the phone's zone.

## Home and the Repairs hub

- **Home** is a compact welcome. The black hero leads with the "Know the fault. Know the price." headline, sized to
  the screen width. Below it are a dark status card (a small round technician avatar, pickup availability for the
  default address, and the estimate, quote and tracking promises), then *Start a repair*, *Run device check* and
  the trust strip. Queued items and active repairs follow straight after, then a link
  into the Repairs hub.
- **Repairs** (bottom nav) is the hub, with jump links to four sections:
  - **My repairs:** Active, Completed and Cancelled tabs with every tracker, quote, payment, invoice, warranty and
    support entry point
  - **Repair a device:** Phone, Laptop, Tablet, PC, Wearable, Audio, each with its "from" price
  - **Popular services:** base price and turnaround
  - **How it works:** pickup, diagnosis, quote approval, delivery and warranty
- Every entry point goes through `ui/BookingEntry.kt`. That starts a fresh draft and the one booking flow, on the
  device step (blank), the issue step (a device, where phones and tablets get the check offer), or details (a popular
  service).

## Device Check (preliminary diagnostics)

An optional check customers can run on an Android phone or tablet before booking, from Home ("Run device check") or
right after choosing Phone or Tablet in booking. For other devices booking explains that guided checks come later and
carries on. It never blocks booking and never changes the estimate.

- **Automatic** (step 1), all through public APIs with no extra permissions:
  - memory: available and total, with a warning only when Android flags low memory or under 10% is available
  - battery: charge, state, power source, temperature and voltage; Android's reported health; and charge cycles
    (Android 14+), charge remaining and current where the phone exposes them. Battery health is never estimated.
  - storage: free and total, with a warning under 10% or 2 GB free
  - sensors: which exist (including rotation where provided), plus a 3-second response check at normal rate
  - connection: online/offline, network type, whether Android confirmed internet access, and Wi-Fi signal (from
    `NetworkCapabilities`, Android 10+, without location permission)
  - device information, never counted as a check:
    - software: Android version, security patch, build type, uptime
    - display: resolution, density, refresh rate, brightness
    - camera hardware: facing, flash, support level, largest preview size (read without opening the camera)
    - audio hardware: current output, built-in speaker and microphone, media volume
- **Guided** (steps 2–8), each confirmed by the customer:
  - full-screen touch grid
  - black, white, red, green and blue display screens
  - a generated speaker tone
  - vibration
  - a 3-second microphone clip, played back from memory and never saved or uploaded
  - a camera preview, front and rear where available
  - watching for charging while the customer's charger is connected
- **Permissions:** camera and microphone are requested only when those checks start. A refusal skips the check
  ("Permission not granted"). The only permissions added are `CAMERA`, `RECORD_AUDIO` and `VIBRATE`.
- **Report:** *At a glance* (checks completed, attention items, skipped or not available, time), a repair hand-off
  summary, and the groups *Working normally*, *Needs a closer look*, *Not checked / not available* and *Device
  information*. Each group keeps its readings.
- **Wording:** anything the phone doesn't expose is "Not available on this device",
  never "failed". Findings say "may indicate" and "needs physical confirmation". A suggested issue is capped at medium
  confidence and stays editable. "Check coverage" (low, medium or high) describes how complete the check was.
- **Booking hand-off:**
  - The report attaches to the booking draft. Review shows its hand-off summary with *View report*.
  - The booking API (`CreateRepairRequest`) has no field for report data, and none was invented. So the full report
    is saved on the phone (`SavedReports`, app-private, not backed up), keyed by the booking's request id.
  - The report is linked to the repair id once the server accepts the booking. That happens immediately, or later
    when the offline queue sends it.
  - The tracker then shows the summary and the full report. Nothing about it changes the estimate or the quote.
- **Code:** `data/diagnostics/`
  - `DiagnosticRules`: pure interpretation, unit-tested
  - `AndroidDiagnosticEngine`: the framework calls; it releases sensors and audio on cancel
  - `DiagnosticsViewModel`: flow state
  - The screens live in `ui/screens/Diagnostics.kt`.

## Trying it

- **Enter the app:** choose **Continue as guest**. OTP sign-in is intentionally deferred for the phase-one prototype.
- **Booking contact:** the pickup step asks for a name and mobile number. The number has an always-visible
  on-screen keypad, so it works even where no keyboard reaches the app.
- **Quote approval:** Home → *Review quote* on the iPhone 13 repair.
- **Tracking:** in any tracker, *Next update* moves the order one step (demo build, or a prod debug build with
  `switchon.internalToken` set). It stands in for the partner and shop apps.
- **Payment:** after delivery, pay by UPI, card or cash.
- **Warranty claim:** Repairs → Completed → Dell Inspiron → Warranty.
- **Edge states:** Account → *Prototype controls* has switches for offline and a failed request. The demo build can
  also empty or reset its sample data.

Demo repairs are dated relative to today, both in the demo build and in the backend's development seed.

## Layout

```
app/src/main/java/com/switchon/customer/
  SwitchOnApp.kt   Application + AppGraph: picks the backend by flavor
  data/            Models, Catalog, Validation, AppViewModel (screen state, duplicate-action guard), SyncWorker
  data/backend/    RepairBackend interface; MockBackend (demo build)
  data/remote/     RemoteBackend (prod), Retrofit API + DTOs, mapping to the domain, session store, auth
  data/local/      Room cache of server data and the offline queue
  ui/theme/        Brand tokens shared with ../index.html, type scale (Archivo condensed, Instrument Sans, JetBrains Mono)
  ui/components/   Paper.kt (torn dockets, stamps, scan lines), Kit.kt (buttons, chips, fields, nav, states)
  data/diagnostics/ Device Check: models, rules, Android engine, view model, reports saved on the phone
  ui/BookingEntry.kt Entry points into booking (hero, device categories, popular services) and the starting step
  ui/screens/      One file per area: Auth, Home, Booking, Tracker, Quote (+ pay, invoice), Repairs, Warranty, Support,
                   Account, Diagnostics
```

Decorative motion (scan lines, stamp entry, timeline pulse) is skipped when the system "Remove animations" setting is on.
