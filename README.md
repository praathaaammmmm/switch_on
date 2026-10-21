# Switch On

An Android-first doorstep electronics repair platform for India.

Switch On helps customers understand device issues, receive transparent repair estimates, schedule doorstep pickup, track repairs, approve final quotes, pay after delivery, and manage warranty claims.

> **Status:** Student project under active development. Some integrations—including payments, partner operations, and parts of the diagnostic experience—are simulated or development-only.

## Features

- Guest-first customer experience for early access
- Guided repair booking for phones, laptops, tablets, PCs, wearables, and audio devices
- Transparent base estimates and turnaround times
- Doorstep pickup scheduling and repair tracking
- Repair timeline with custody events, shop details, and quote approval
- Simulated UPI, card, and cash payment flows
- Server-backed invoices, warranty claims, and support tickets
- Offline-first repair caching and queued booking synchronization
- Demo and production Android build flavors
- Android **Device Check** diagnostic flow, designed as a preliminary software/function check—not a replacement for physical inspection

## Tech stack

| Layer | Technology |
|---|---|
| Android app | Kotlin, Jetpack Compose |
| Architecture | ViewModel, repository pattern, offline-first local storage |
| Networking | REST API, Retrofit/OkHttp |
| Background sync | WorkManager |
| Backend | Kotlin, Ktor |
| Database | PostgreSQL |
| Migrations | Flyway |
| Local infrastructure | Docker Compose |
| Testing | JUnit, backend integration tests, Android unit tests |
| Build | Gradle, JDK 21 |

## Project structure

```text
switch-on/
├── android/             # Kotlin + Jetpack Compose customer app
├── backend/             # Ktor + PostgreSQL customer API
├── assets/              # Brand artwork and visual assets
├── index.html           # Original Switch On web concept
└── README.md
```

## Customer journey

```text
Guest entry
  → Device check or repair booking
  → Estimate and pickup slot
  → Device pickup
  → Repair tracking
  → Final quote approval
  → Repair and quality check
  → Return delivery
  → Payment
  → Invoice and 90-day warranty
```

## Run locally

### Prerequisites

- JDK 21
- Docker and Docker Compose
- Android SDK and emulator, or a physical Android device

### Start the backend

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH="$JAVA_HOME/bin:$PATH"

cd backend
./dev up
```

Run backend tests:

```bash
cd backend
./dev test
```

Stop local services:

```bash
cd backend
./dev down
```

### Run the Android app

Start the configured Android emulator:

```bash
export ANDROID_AVD_HOME="$HOME/.config/.android/avd"
"$HOME/Android/Sdk/emulator/emulator" -avd so_pixel
```

Install the production debug app:

```bash
cd android
./gradlew :app:installProdDebug
```

The emulator reaches the local API at `10.0.2.2`; this is the default debug configuration. To use the app without a backend, install the demo flavor instead:

```bash
cd android
./gradlew :app:installDemoDebug
```

## Testing

The project includes backend and Android tests for guest sessions, booking validation, repair lifecycle transitions, quote decisions, payment idempotency, warranty eligibility, offline synchronization, and error handling.

```bash
cd backend
./dev test

cd ../android
./gradlew :app:testProdDebugUnitTest
```

See [backend/README.md](backend/README.md) for API, configuration, migrations, and lifecycle details; see [android/README.md](android/README.md) for Android flavors, offline behaviour, and builds.

## Important limitations

- This is a student project and local-development platform.
- Payments are recorded in the app but are not connected to a real payment provider.
- Repair shops, couriers, status updates, and support-chat replies are simulated or development-controlled.
- Device Check results are preliminary. They cannot confirm internal hardware damage, liquid damage, motherboard faults, cable damage, component calibration, or final repair pricing.
- The production API URL must be configured before public deployment.
- Never commit customer data, credentials, API keys, database passwords, or signing keys.

## Future roadmap

- Real OTP authentication and account linking
- Production deployment and hosted PostgreSQL
- Payment gateway integration
- Device-condition photo upload and custody records
- Push, SMS, and WhatsApp repair notifications
- Partner/technician workflow
- Operations and admin dashboard
- Additional cities, languages, and iOS support

## License

This project is intended as a portfolio/student project. Add an MIT `LICENSE` file before publishing it publicly.
