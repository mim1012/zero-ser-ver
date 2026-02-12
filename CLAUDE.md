# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Zero is a **monorepo** managing a mobile automation platform with 1500+ Android devices. It implements **Option C: Server-based Dynamic Configuration Management** — a centralized system where all devices pull their configuration from the server.

## Monorepo Structure

```
zero/
├── apps/
│   ├── server/      ← FastAPI backend (Railway)
│   ├── landing/     ← Next.js landing page (Vercel)
│   └── android/     ← Android APK v2 (manual build)
├── docs/            ← Documentation
├── .gitignore
├── CLAUDE.md
└── README.md
```

## apps/server — FastAPI Backend

**Tech:** Python 3.11, FastAPI, Uvicorn, SQLAlchemy (MySQL), Supabase (PostgreSQL)
**Deploy:** Railway (Root Directory: `apps/server`)

### Development Commands

```bash
cd apps/server
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8080

# Docker
docker build -t zero-server .
docker run -p 8080:8080 zero-server
```

### Key Paths

- `app/main.py` — FastAPI app, router registration, /health endpoint
- `app/api/v1/` — REST API endpoints (devices, config, scenario, traffic, dashboard)
- `app/config/*.json` — Dynamic config files (headers, user_agents, webview)
- `app/database/supabase_client.py` — Supabase singleton client
- `app/schemas/schemas.py` — Pydantic schemas
- `Dockerfile` — Railway deployment

### Environment Variables

- `DATABASE_URL` or (`MYSQLHOST`, `MYSQLPORT`, `MYSQLUSER`, `MYSQLPASSWORD`, `MYSQLDATABASE`)
- `SUPABASE_URL`, `SUPABASE_KEY`

### Critical Notes

1. **Never delete app/config/*.json files** — 1500 devices depend on these
2. **Test config changes locally first** — Invalid JSON breaks all device registrations
3. Config refresh interval: 1 hour (Android ConfigManager)
4. Group size limit: 8 devices per group (devices_supabase.py)

## apps/landing — Next.js Landing Page

**Tech:** Next.js 16, TypeScript, React 18, Supabase
**Deploy:** Vercel (Root Directory: `apps/landing`, Region: icn1)

### Development Commands

```bash
cd apps/landing
npm install
npm run dev
```

### Key Paths

- `app/page.tsx` — Home page (shopping review blog)
- `app/r/[slug]/route.ts` — Redirect route handler (Naver search URL generation)
- `lib/supabase.ts` — Supabase client configuration

### Environment Variables

- `NEXT_PUBLIC_SUPABASE_URL`, `NEXT_PUBLIC_SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_KEY`

## apps/android — Android APK v2

**Tech:** Java 17, Android SDK 34 (min 24), OkHttp, AndroidX
**Build:** Android Studio → `apps/android` folder

### Key Packages

- `com.zero.traffic.engine/` — Scenario execution engine (ScriptEngine, ActionExecutor, ScenarioRunner)
- `com.zero.traffic.server/` — API client and task manager
- `com.zero.traffic.network/` — Chrome manager, WiFi, hotspot control
- `com.zero.traffic.service/` — Foreground TrafficService, AutoInstallService

### Build

Open `apps/android` in Android Studio. Server URL is configured in `app/build.gradle`.

## Architecture

### Two-Tier System

1. **Server** manages configs, assigns tasks, groups devices (8 per group, leader/follower roles)
2. **Android** pulls configs hourly, executes scenarios via ScriptEngine, reports results
3. **Landing** handles redirect URLs for Naver search traffic

### Database

- **MySQL (Railway):** Tasks, keywords, accounts, cookies, ranks
- **Supabase (PostgreSQL):** Device registration, groups, landing_redirects
