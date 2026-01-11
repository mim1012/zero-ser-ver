# Zero Server

Zero 모바일 자동화 시스템을 위한 백엔드 API 서버입니다.

## 기술 스택

- **언어/프레임워크**: Python 3.11 / FastAPI
- **데이터베이스**: MySQL
- **배포**: Railway (Docker 기반)

## 주요 기능

- 기기 관리 (Device Management)
- 작업 관리 (Keyword/Task Management)
- 계정 관리 (Account Management)
- 쿠키 관리 (Cookie Management)
- 순위 추적 (Rank Tracking)

## API 엔드포인트

### 기기 관리
- `POST /zero/api/v1/devices` - 기기 등록
- `GET /zero/api/v1/devices` - 기기 목록 조회
- `GET /zero/api/v1/devices/{device_id}` - 특정 기기 조회
- `PUT /zero/api/v1/devices/{device_id}` - 기기 정보 업데이트
- `DELETE /zero/api/v1/devices/{device_id}` - 기기 삭제
- `POST /zero/api/v1/devices/{device_id}/heartbeat` - 하트비트

### 작업 관리
- `POST /zero/api/v1/keywords` - 작업 생성
- `GET /zero/api/v1/keywords` - 작업 목록 조회
- `GET /zero/api/v1/keywords/{keyword_id}` - 특정 작업 조회
- `PUT /zero/api/v1/keywords/{keyword_id}` - 작업 정보 업데이트
- `DELETE /zero/api/v1/keywords/{keyword_id}` - 작업 삭제
- `GET /zero/api/v1/keywords/device/{device_id}/pending` - 대기 중인 작업 조회
- `POST /zero/api/v1/keywords/{keyword_id}/complete` - 작업 완료 처리

### 계정 관리
- `POST /zero/api/v1/accounts` - 계정 생성
- `GET /zero/api/v1/accounts` - 계정 목록 조회
- `GET /zero/api/v1/accounts/{account_id}` - 특정 계정 조회
- `PUT /zero/api/v1/accounts/{account_id}` - 계정 정보 업데이트
- `DELETE /zero/api/v1/accounts/{account_id}` - 계정 삭제

## 로컬 개발 환경 설정

### 1. 의존성 설치

```bash
pip install -r requirements.txt
```

### 2. 환경 변수 설정

`.env` 파일을 생성하고 다음 내용을 추가합니다:

```env
DATABASE_URL=mysql+pymysql://mim1012:password@localhost:3306/zero_db
```

### 3. 서버 실행

```bash
uvicorn app.main:app --reload --port 8080
```

서버가 실행되면 다음 URL에서 API 문서를 확인할 수 있습니다:
- Swagger UI: http://localhost:8080/docs
- ReDoc: http://localhost:8080/redoc

## Railway 배포 가이드

### 1. Railway 프로젝트 생성

1. [railway.app](https://railway.app)에 접속하여 GitHub 계정으로 로그인합니다.
2. `New Project` 버튼을 클릭합니다.
3. `Deploy from GitHub repo`를 선택하고, 이 리포지토리를 선택합니다.
4. `Deploy Now`를 클릭합니다.

### 2. MySQL 데이터베이스 추가

1. 프로젝트 대시보드에서 `+ New` 버튼을 클릭합니다.
2. `Database` > `Add MySQL`을 선택합니다.
3. Railway가 자동으로 MySQL 인스턴스를 생성하고 환경 변수를 주입합니다.

### 3. 환경 변수 설정

Railway는 MySQL 데이터베이스를 생성하면 다음 환경 변수를 자동으로 주입합니다:
- `DATABASE_URL`
- `MYSQLHOST`
- `MYSQLPORT`
- `MYSQLUSER`
- `MYSQLPASSWORD`
- `MYSQLDATABASE`

추가 환경 변수가 필요한 경우, 서비스의 `Variables` 탭에서 수동으로 추가할 수 있습니다.

### 4. 도메인 확인

배포가 완료되면, `Settings` > `Networking`에서 자동 생성된 도메인을 확인할 수 있습니다.

예: `https://zero-server-production.up.railway.app`

## 클라이언트 연동

Android 클라이언트의 `build.gradle`에 다음과 같이 서버 URL을 추가합니다:

```gradle
buildConfigField("String", "SERVER_URL", "\"https://zero-server-production.up.railway.app/zero/api/\"")
```

## 라이선스

Private Project
