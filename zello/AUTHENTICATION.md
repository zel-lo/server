# GitHub 로그인

GitHub 사용자 승인 → 서버의 code 교환·GitHub /user 조회 → 회원 생성/갱신 → HttpOnly 서버 세션 → React 복귀를 구현했습니다. 로그인된 사용자 정보는 `GET /api/me`로 조회합니다. 저장소 접근 승인과 프로젝트 연결은 별도 후속 작업입니다.

## 1. GitHub App 등록

[GitHub App 생성 화면](https://github.com/settings/apps/new)에서 개발용 App을 만듭니다.

| 항목 | 개발 설정 |
|---|---|
| GitHub App name | 전역에서 중복되지 않는 개발용 이름 |
| Homepage URL | `http://127.0.0.1:5173` |
| Callback URL | `http://127.0.0.1:8080/login/oauth2/code/github` |
| Expire user authorization tokens | 기본 설정 유지 |
| Webhook Active | 로그인만 구현하는 현재 단계에서는 해제 |
| Permissions | 로그인에는 추가 저장소 권한이 필요하지 않음 |
| Where can this GitHub App be installed? | 개발용은 Only on this account |

생성 후 Client ID와 Generate a new client secret으로 만든 Secret을 로컬 `.env`에 입력합니다. App ID와 Client ID는 서로 다릅니다. 현재 로그인 구현에는 App private key가 필요하지 않습니다.

앱은 사용자 신원 확인과 서버 세션 생성에 사용합니다. 저장소를 임의로 연결하거나 저장소 권한을 부여하지 않습니다. GitHub App의 세밀한 권한은 등록 설정에서 관리하고, OAuth 승인 요청에 repo 같은 광범위한 scope를 넣지 않습니다. 기존 OAuth App을 사용한다면 GITHUB_APP_TYPE=OAUTH로 설정하며 read:user만 요청합니다.

공식 문서: [App 등록](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app), [사용자 토큰](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app).

## 2. 로컬 설정

`server/zello/.env.example`을 참고해 같은 폴더의 `.env`에 값을 입력합니다. 이미 `.env`가 있으면 덮어쓰지 말고 해당 항목만 설정합니다. `.env`는 Spring Boot가 properties 형식으로 읽으며 값에는 따옴표를 붙이지 않습니다. 셸/IDE 환경 변수가 파일보다 우선합니다.

- GITHUB_LOGIN_ENABLED=true
- GITHUB_CLIENT_ID와 GITHUB_CLIENT_SECRET
- DB_URL·DB_USERNAME·DB_PASSWORD: 실제 MySQL의 zello 개발 DB와 계정
- FRONTEND_BASE_URL·GITHUB_REDIRECT_URI: 위 표와 같은 주소

Client ID는 공개 식별자지만 Secret은 서버 `.env`에만 저장합니다. 실제 `.env`는 Git 추적에서 제외되며 프론트 환경 변수에 Secret을 넣지 않습니다. GITHUB_LOGIN_ENABLED=false이면 서버는 가짜 인증을 제공하지 않고 로그인 시작 요청에 503을 반환합니다.

## 3. MySQL과 실행

MySQL에 **빈 zello 개발 DB와 애플리케이션 계정**을 준비하고 해당 계정을 `.env`에 설정합니다. 예를 들어 관리자 MySQL 세션에서 아래 SQL의 비밀번호를 로컬 값으로 바꿔 실행할 수 있습니다.

```sql
CREATE DATABASE zello CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'zello'@'localhost' IDENTIFIED BY 'replace-with-your-local-password';
GRANT ALL PRIVILEGES ON zello.* TO 'zello'@'localhost';
```

이미 DB·계정이 있으면 재생성하지 않습니다. 첫 실행 시 Flyway의 V1 마이그레이션이 21개 테이블을 만들고 Hibernate validate가 매핑을 확인합니다. 기존의 테이블이 있는 DB에 자동 baseline·초기화·데이터 삭제를 수행하지 않습니다. 개발용 DB를 새로 준비하거나 기존 스키마의 마이그레이션 여부를 먼저 확인해야 합니다.

백엔드(Java 25 toolchain):

```sh
cd /Users/apple/Desktop/zello/server/zello
./gradlew bootRun
```

프론트:

```sh
cd /Users/apple/Desktop/zello/client
npm run dev -- --port 5173 --strictPort
```

`http://127.0.0.1:5173/#/login`에서 GitHub로 로그인합니다. Vite가 /api·/oauth2·/login/oauth2 요청을 8080 서버로 전달하므로 기본 개발 환경에서는 VITE_GITHUB_AUTH_URL을 비워도 됩니다. localhost와 127.0.0.1을 섞으면 세션 쿠키가 공유되지 않으므로 위 주소를 일관되게 사용합니다.

다른 포트/도메인을 사용하면 App Callback URL·GITHUB_REDIRECT_URI·FRONTEND_BASE_URL·프론트 프록시/리버스 프록시를 함께 맞춥니다. 배포 환경은 HTTPS에서 SESSION_COOKIE_SECURE=true로 설정하고 프론트에서 /api와 OAuth 시작 경로를 서버로 전달합니다.

## 4. API

| 요청 | 응답 |
|---|---|
| GET /api/auth/status | githubLoginEnabled, 인증 정보는 제외 |
| GET /oauth2/authorization/github | state·S256 PKCE를 포함한 GitHub 승인 URL로 이동 |
| GET /login/oauth2/code/github | 서버의 OAuth callback |
| GET /api/me | 현재 회원의 id·githubId·login·displayName·avatarUrl·email·theme |
| GET /api/auth/csrf | headerName·parameterName·token |
| POST /api/auth/logout | CSRF 헤더를 검증한 뒤 세션 종료, 204 |

미인증 API 호출은 JSON 401이며 GitHub로 강제 리다이렉트하지 않습니다. 로그아웃은 CSRF 토큰 없이 POST하면 403이고 GET으로는 실행되지 않습니다. 사용자 ID는 요청 본문에서 신뢰하지 않고 인증 principal에서 결정합니다.

React의 /auth/callback 화면이 /api/me를 조회한 뒤 워크스페이스로 이동합니다. 샘플 프로젝트의 작업 데이터는 계속 샘플이며, 로그인한 회원의 이름·GitHub 로그인과 로그아웃 버튼을 프로필에 표시합니다. 회원가입과 로그인은 같은 OAuth 흐름으로 처리하고 GitHub 고유 ID로 재로그인 회원을 식별합니다. 공개 이메일이 없어도 가입할 수 있습니다.

로그인 후 회원이 탈퇴 상태가 되면 후속 요청에서 세션을 종료합니다. 회원 탈퇴·프로젝트 소유권 이전 API 자체는 이번 범위에 포함하지 않았습니다.

## 5. 세션과 토큰

- HttpOnly·SameSite=Lax 쿠키, 기본 30분 서버 세션을 사용합니다. 로그인 성공 시 세션 ID를 교체합니다.
- OAuth state·PKCE·토큰 교환은 Spring Security가 처리합니다. 승인 시작과 code 교환을 프론트에서 직접 구현하지 않습니다.
- GitHub access/refresh token은 authorized client로 **서버 세션에만** 저장하고 프론트 응답·URL·localStorage로 전달하지 않습니다. 세션/서버 재시작 후에는 재로그인이 필요합니다.
- 현재 기능은 로그인 직후 사용자 조회만 수행합니다. 향후 장시간 GitHub API 호출을 추가할 때 토큰 만료·refresh·재승인과 다중 서버 세션 저장을 별도로 구현해야 합니다.
- 성공·실패 복귀 URL은 서버 설정으로 고정합니다. 사용자 입력 returnTo나 GitHub 오류 설명을 그대로 URL에 넣지 않습니다.
- CORS는 설정한 프론트 origin만 credential 요청을 허용합니다. CSRF 보호는 켜 둡니다.

## 6. 검증

```sh
./gradlew test
./gradlew build
```

실제 GitHub에 요청하지 않는 통합 테스트는 code 교환과 원본 사용자 응답만 테스트 대역으로 바꾸고, 실제 Spring Security OAuth 필터·state·PKCE·회원 DB·서버 세션·CSRF·로그아웃을 실행합니다. 첫 가입·재로그인·이름 변경·이메일 비공개·취소·state 위조·탈퇴 계정·CORS를 확인합니다. 이는 실제 앱 자격 증명으로 브라우저 승인한 결과와 구분합니다.

테스트의 H2 설정은 테스트 클래스패스에만 있으며 Flyway를 끄고 Hibernate create-drop을 사용합니다. 앱은 MySQL과 Flyway를 사용합니다. 실제 GitHub 승인과 MySQL 실행 결과는 로컬 Secret·DB 설정을 완료한 뒤 별도로 확인해야 합니다.
