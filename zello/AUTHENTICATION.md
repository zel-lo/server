# GitHub OAuth 로그인

기본 방식은 **OAuth App**이다. GitHub 로그인에서 프로필·저장소·조직 접근을 한 번 승인받고, 이후 zello 안에서 저장소를 조회·검색·선택한다. GitHub App 설치와 PAT 입력은 기본 사용자 흐름에 포함하지 않는다.

## 1. 서비스 관리자의 최초 설정

[OAuth App 등록 화면](https://github.com/settings/applications/new)에 다음 값을 입력한다. 사용자마다 등록하는 앱이 아니라 서비스 공통 앱이다. 로컬 개발과 운영은 별도 OAuth App으로 관리하는 것을 권장한다.

| 항목 | 로컬 개발 값 |
|---|---|
| Application name | zello (local) |
| Homepage URL | `http://127.0.0.1:5173` |
| Redirect URI | `http://127.0.0.1:8080/login/oauth2/code/github` |
| Expire user access tokens | 기본 설정 유지 |
| Device Flow / wildcard redirect | 사용하지 않음 |

OAuth App은 기존 `zello-dev` GitHub App과 다른 등록이다. 기존 GitHub App의 Client ID·Secret은 재사용할 수 없다. 새 앱에서 발급한 값을 서버의 Git에 포함되지 않는 `.env`에 설정한다. 값에 따옴표를 붙이지 않는다.

```dotenv
GITHUB_LOGIN_ENABLED=true
GITHUB_APP_TYPE=OAUTH
GITHUB_CLIENT_ID=your-oauth-app-client-id
GITHUB_CLIENT_SECRET=your-oauth-app-client-secret
GITHUB_REDIRECT_URI=http://127.0.0.1:8080/login/oauth2/code/github
FRONTEND_BASE_URL=http://127.0.0.1:5173
```

DB_URL·DB_USERNAME·DB_PASSWORD는 실제 MySQL 개발 계정을 사용한다. SESSION_ENCRYPTION_KEY는 `openssl rand -base64 32`로 한 번 만든 32바이트 키를 계속 유지한다. 기존 `.env`를 예시 파일로 덮어쓰지 않는다. Client Secret과 암호화 키는 프론트 환경 변수·채팅·Git에 넣지 않는다.

## 2. 사용자 흐름과 권한

1. zello의 **GitHub로 로그인**을 누른다.
2. GitHub에서 서비스와 요청 권한을 확인해 승인한다.
3. GitHub가 서버 callback으로 code를 보내고 서버가 토큰을 교환한다.
4. GitHub 고유 ID로 회원을 저장·갱신하고 서버 세션을 만든다.
5. React가 `/api/me`로 실제 회원을 확인한다.
6. 프로젝트 설정의 저장소 화면에서 개인·Organization 저장소를 검색해 연결한다.

요청 scope는 `read:user`, `repo`, `read:org`이다. 비공개 저장소를 포함하려면 OAuth App의 `repo` scope가 필요하며, 이 scope는 GitHub 정책상 읽기와 쓰기가 함께 포함된다. 앱의 현재 저장소 목록·연결 API는 원본 데이터를 읽고 zello DB에 연결 정보를 저장한다. GitHub 이슈 생성 등 외부 쓰기는 후속 기능에서 별도의 사용자 액션으로 처리한다.

Organization이 OAuth App 접근을 제한하면 조직 관리자 승인이나 SSO 인증이 필요할 수 있다. OAuth App도 GitHub가 허용하지 않은 조직·비공개 저장소에 접근할 수 없다. 로그인 승인 화면 자체는 GitHub에서 처리하며 그 이후의 저장소 선택·연결은 zello 안에서 수행한다.

## 3. DB와 실행

빈 `zello` 개발 DB와 앱 계정을 준비한다. 기존 DB가 있으면 재생성하지 않는다. Flyway V1은 업무용 21개 테이블, V2는 서버 세션 테이블, V3는 설치 없는 OAuth 저장소 연결을 지원한다. Hibernate는 validate로 검증하며 기존 스키마를 자동 삭제하지 않는다.

```sh
cd /Users/apple/Desktop/zello/server/zello
./gradlew bootRun
```

```sh
cd /Users/apple/Desktop/zello/client
npm run dev -- --strictPort
```

개발 주소는 `http://127.0.0.1:5173/#/login`, 서버는 8080이다. Vite가 API와 OAuth 요청을 프록시한다. localhost와 127.0.0.1을 섞지 않는다.

## 4. 로그인 유지와 보안

- HttpOnly·SameSite=Lax의 JSESSIONID 쿠키를 사용하고 로그인 성공 시 세션 ID를 교체한다.
- 기본 30분의 비활동 만료 시간을 갖는 Spring Session JDBC를 사용한다. 회원과 세션은 MySQL에 저장된다.
- OAuth access/refresh token을 포함한 세션 속성은 AES-256-GCM으로 암호화한다. 같은 DB와 키를 사용하는 서버 재시작 후에도 유효한 로그인이 유지된다.
- 토큰·code를 프론트 응답이나 localStorage에 저장하지 않는다. OAuth state, PKCE S256, code 교환은 Spring Security가 처리한다.
- POST/DELETE는 CSRF 토큰을 검증한다. 로그아웃은 서버 세션을 DB에서 삭제한다. 탈퇴한 계정의 기존 세션도 차단한다.
- 토큰 만료·취소 또는 scope 부족은 재로그인으로 안내한다. refresh token은 암호화해 보관하되 자동 refresh는 아직 구현하지 않았다.
- 기존 GitHub App 세션 또는 다른 Client ID로 만든 세션의 토큰을 새 OAuth App용으로 재사용하지 않는다.
- 홈·사이드바에는 실제 사용자와 로그인 상태를 표시하고, 탭 재활성화 및 다른 탭의 로그아웃을 반영한다.

운영에서는 서비스 도메인으로 Homepage와 Callback URL, FRONTEND_BASE_URL, GITHUB_REDIRECT_URI를 함께 변경하고 HTTPS에서 SESSION_COOKIE_SECURE=true를 설정한다. 운영 DB·Secret·암호화 키는 환경별 비밀 설정으로 관리한다.

## 5. API와 검증

| API | 기능 |
|---|---|
| GET /api/auth/status | 로그인 설정 여부 |
| GET /oauth2/authorization/github | OAuth 로그인·권한 승인 시작 |
| GET /login/oauth2/code/github | 서버 callback |
| GET /api/me | 실제 로그인 회원; ID는 문자열 |
| GET /api/auth/csrf | CSRF 헤더 이름과 토큰 |
| POST /api/auth/logout | 세션 삭제, 204 |

`./gradlew build`로 로그인·세션·권한·ERD 계약을 검증한다. PersistentLoginTests는 JDBC 세션을 실제 사용하고, 외부 GitHub HTTP 응답만 테스트 대역으로 제공한다. 실제 OAuth App의 브라우저 승인과 저장소 조회는 새 자격 증명 설정 후 별도로 확인한다.

기존 GitHub App 경로는 이미 저장된 연결과 테스트를 위한 호환 경로로 남아 있다. 기본 프론트는 설치 URL·설치 callback을 사용하지 않는다. 이전 App으로 돌아가는 설정은 GITHUB_APP_TYPE=APP이지만 신규 사용자 흐름은 OAUTH를 사용한다.

공식 문서: [OAuth App 등록](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app), [scope](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps), [사용자 저장소 조회](https://docs.github.com/en/rest/repos/repos#list-repositories-for-the-authenticated-user).
