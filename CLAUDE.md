# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Zero Server is a FastAPI-based backend system for managing 1500+ Android devices performing mobile automation tasks. It implements **Option C: Server-based Dynamic Configuration Management** - a centralized system where all devices pull their configuration (headers, User-Agents, WebView settings) from JSON files on the server, eliminating the need for APK redeployment when browser fingerprints need updating.

## Architecture

### Two-Tier System Design

**Server (Python/FastAPI):**
- Serves dynamic JSON configurations via REST API
- Manages device registration with automatic group assignment (8 devices per group)
- Assigns roles: first device in group = "leader" (대장봇), others = "followers" (쫄병봇)
- Tracks device heartbeats and task assignments
- Dual database: MySQL for general data, Supabase for device/group management

**Client (Android/Java):**
- `ConfigManager.java`: Downloads and caches server configurations (1-hour refresh)
- `CustomWebViewClient.java`: Applies server-provided headers to WebView requests
- `WebViewHelper.java`: Initializes WebView with dynamic settings
- Integration pattern shown in `android/INTEGRATION_EXAMPLE.java`

### Critical Workflow

1. Android device calls `POST /zero/api/v1/devices/register` with device_id
2. Server assigns device to group (or creates new group if all groups full)
3. Server returns: `{group_id, role: "leader"|"follower", group_name}`
4. Device periodically fetches `/zero/api/v1/config/full?device_model=SM-G998N&chrome_version=143`
5. Device applies headers/UA to all WebView traffic
6. When Chrome updates (e.g., v143 → v144), admin updates `app/config/headers.json` and pushes to GitHub
7. Railway auto-deploys → All 1500 devices get new config within 1 hour (no APK rebuild)

## Development Commands

### Local Development
```bash
# Install dependencies
pip install -r requirements.txt

# Set environment variables (create .env file)
DATABASE_URL=mysql+pymysql://user:pass@localhost:3306/zero_db
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_KEY=your-supabase-service-role-key

# Run development server
uvicorn app.main:app --reload --port 8080

# API documentation
# Swagger UI: http://localhost:8080/docs
# ReDoc: http://localhost:8080/redoc
```

### Docker
```bash
# Build image
docker build -t zero-server .

# Run container
docker run -p 8080:8080 \
  -e DATABASE_URL=mysql+pymysql://user:pass@host:3306/zero_db \
  -e SUPABASE_URL=https://your-project.supabase.co \
  -e SUPABASE_KEY=your-key \
  zero-server
```

### Railway Deployment
- Push to `master` branch triggers automatic deployment
- Railway injects MySQL environment variables: `MYSQLHOST`, `MYSQLPORT`, `MYSQLUSER`, `MYSQLPASSWORD`, `MYSQLDATABASE`
- Set custom variables in Railway dashboard: `SUPABASE_URL`, `SUPABASE_KEY`
- Health check: `GET /health` (returns MySQL + Supabase connection status)

## Configuration Management (Core Feature)

### JSON Configuration Files (app/config/)

**headers.json**
- Chrome version profiles: `chrome_143`, `chrome_142`, etc.
- Contains: `sec-ch-ua`, `sec-ch-ua-platform`, `accept`, `accept-encoding`, etc.
- **Update workflow**: Edit file → commit → push → Railway deploys → devices refresh

**user_agents.json**
- Device model mappings: `"SM-G998N": [{"chrome_version": "143", "user_agent": "..."}]`
- Supports multiple UA strings per device model

**webview_config.json**
- Per-device WebView version management
- Pattern: `{"devices": {"SM-G906": {"target_version": "143.0.7498.103"}}}`
- Used by `WebviewUpdatePatternMessage.java` to control updates

**webview_settings.json**
- Generic WebView JavaScript settings (cache, DOM storage, etc.)

### API Endpoints for Configs

```python
# Get all configs in one call (client uses this)
GET /zero/api/v1/config/full?device_model=SM-G998N&chrome_version=143

# Individual endpoints
GET /zero/api/v1/config/headers?profile=chrome_143
GET /zero/api/v1/config/user-agents?device_model=SM-G998N
GET /zero/api/v1/config/webview?model=SM-G906

# Admin update endpoints (POST)
POST /zero/api/v1/config/headers
POST /zero/api/v1/config/user-agents
POST /zero/api/v1/config/webview
```

## Database Architecture

### MySQL (SQLAlchemy)
- Models in `app/models/models.py`
- Tables: `devices`, `keywords`, `accounts`, `cookies`, `ranks`
- Connection handling: `app/database/database.py`
- Railway auto-injects `DATABASE_URL` or constructs from `MYSQL*` env vars

### Supabase (PostgreSQL)
- Used exclusively for device/group management
- Tables: `devices`, `device_groups`, `mobile_headers`
- Singleton client: `app/database/supabase_client.py`
- Group assignment logic in `app/api/v1/devices_supabase.py`

**Why Two Databases?**
- MySQL: Legacy system for task/keyword management
- Supabase: Added for real-time device grouping with 8-device limit per group

## API Route Structure

```
app/
├── main.py                          # FastAPI app, router registration, /health endpoint
├── api/v1/
│   ├── devices_supabase.py          # Device registration, heartbeat, group info
│   ├── config.py                    # Config management (headers, UA, WebView)
│   ├── keywords.py                  # Task assignment (pending tasks per device)
│   ├── accounts.py                  # Account CRUD
│   ├── devices.py                   # Legacy MySQL device management
│   ├── traffic.py                   # Traffic analytics
│   ├── dashboard.py                 # Web dashboard API
│   └── headers.py                   # Header management (legacy)
├── database/
│   ├── database.py                  # MySQL SQLAlchemy setup
│   └── supabase_client.py           # Supabase singleton client
├── models/models.py                 # SQLAlchemy ORM models
├── schemas/schemas.py               # Pydantic schemas
└── config/                          # JSON config files (VERSION CONTROLLED)
    ├── headers.json
    ├── user_agents.json
    ├── webview_config.json
    └── webview_settings.json
```

## Key Implementation Details

### Device Registration Flow (devices_supabase.py:45-146)
1. Check if device exists in Supabase `devices` table
2. If exists: update `last_heartbeat`, `current_ip`, `status='active'`
3. If new: find active group with <8 devices, or create new group
4. Assign role: first device in group = `leader`, rest = `follower`
5. If leader, update `device_groups.leader_device_id`
6. Return: `{device_id, group_id, group_name, role, message}`

### Config Merging Logic (config.py:152-198)
- `/config/full` combines all JSON configs into single response
- Selects Chrome version profile from `headers.json`
- Matches device model to User-Agent list
- Returns merged config for Android app's `ConfigManager.java`

### Android Integration Points
- **ConfigManager.java:91-130**: Downloads `/config/full`, caches to SharedPreferences
- **CustomWebViewClient.java**: Intercepts `shouldInterceptRequest()`, injects headers from cache
- **WebViewHelper.java**: One-time WebView initialization with server settings
- All classes designed to work offline using cached configs

## Environment Variables

**Required:**
- `DATABASE_URL` or (`MYSQLHOST`, `MYSQLPORT`, `MYSQLUSER`, `MYSQLPASSWORD`, `MYSQLDATABASE`)
- `SUPABASE_URL` - Supabase project URL
- `SUPABASE_KEY` - Supabase service role key

**Optional:**
- `PORT` - Server port (default: 8080, Railway overrides this)

**Note:** Supabase credentials are currently hardcoded in `supabase_client.py:9-10` for development. Move to environment variables before production deployment.

## Critical Operational Notes

1. **Never delete app/config/*.json files** - 1500 devices depend on these
2. **Test config changes locally first** - Invalid JSON will break all device registrations
3. **Chrome version updates**: Add new profile to `headers.json` (keep old profiles for gradual rollout)
4. **Group size limit**: Hardcoded to 8 devices per group in `devices_supabase.py:102`
5. **Config refresh interval**: Android devices check every 1 hour (ConfigManager.java:34)
6. **Heartbeat frequency**: Not specified in server code - check Android implementation

## Adding New Chrome Version

```bash
# 1. Edit headers.json
vim app/config/headers.json
# Add new profile: "chrome_144": { "sec-ch-ua": "...", ... }

# 2. Edit user_agents.json (if needed)
vim app/config/user_agents.json
# Add new UA with "chrome_version": "144"

# 3. Commit and push
git add app/config/headers.json app/config/user_agents.json
git commit -m "Add Chrome 144 headers"
git push origin master

# 4. Railway auto-deploys in ~2 minutes
# 5. Devices refresh config within 1 hour
```

## CORS Configuration

Currently allows all origins (`allow_origins=["*"]` in main.py:29). Restrict to specific domains in production:

```python
allow_origins=[
    "https://your-admin-dashboard.com",
    "https://your-android-app-domain.com"
]
```
