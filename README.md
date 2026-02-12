# Zero

Mobile automation platform monorepo — server, landing page, and Android client.

## Structure

| App | Path | Tech | Deploy |
|-----|------|------|--------|
| **Server** | `apps/server/` | FastAPI (Python 3.11) | Railway |
| **Landing** | `apps/landing/` | Next.js 16 (TypeScript) | Vercel |
| **Android** | `apps/android/` | Java 17, Android SDK 34 | Manual APK |

## Quick Start

### Server

```bash
cd apps/server
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8080
```

### Landing

```bash
cd apps/landing
npm install
npm run dev
```

### Android

Open `apps/android` in Android Studio and build.

## Deployment

| App | Platform | Setting |
|-----|----------|---------|
| Server | Railway | Root Directory → `apps/server` |
| Landing | Vercel | Root Directory → `apps/landing` |
| Android | Manual | Build APK from Android Studio |

## License

Private Project
