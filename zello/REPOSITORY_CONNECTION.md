# zello 안에서 GitHub 저장소 연결

GitHub OAuth 로그인 후 **프로젝트 설정 → 저장소 연결**에서 개인·Organization 저장소를 조회·검색·선택한다. 저장소를 연결하기 위해 GitHub App 설치 화면으로 이동하거나 PAT를 입력하지 않는다.

## 최초 설정과 사용자 사용법

서비스 관리자는 `AUTHENTICATION.md`에 따라 OAuth App Client ID·Secret과 GITHUB_APP_TYPE=OAUTH를 설정한다. 기존 GitHub App 자격 증명을 그대로 사용할 수 없다.

사용자는 GitHub 로그인 시 요청 권한을 승인하고, zello에서 프로젝트 생성/선택 → 전체·개인·Organization 범위 선택 → 저장소 검색/선택 → **선택한 저장소 연결** 순서로 사용한다. Organization의 OAuth 정책·SSO가 제한하는 원본은 표시되거나 연결되지 않을 수 있다.

검색은 현재 불러온 페이지를 대상으로 하며 이전/다음 버튼으로 다른 페이지를 볼 수 있다. 원본 목록은 페이지당 최대 100개, 조직 목록도 페이지당 최대 100개를 조회한다. ‘전체’에는 소유 저장소와 협업·조직 권한으로 접근할 수 있는 저장소를 포함한다.

## API

인증은 서버 세션이며 모든 응답 ID는 문자열이다. POST/DELETE는 `/api/auth/csrf`에서 받은 CSRF 헤더를 전송한다.

| 요청 | 기능 |
|---|---|
| GET /api/projects | 활성 멤버십이 있는 프로젝트 목록 |
| POST /api/projects | 프로젝트와 생성자의 ADMIN 멤버십 생성; 소유자는 OWNER 표시 |
| GET /api/projects/{projectId} | 프로젝트 상세·관리 가능 여부 |
| GET /api/github/accounts?page=1 | 로그인한 개인 계정과 소속 Organization |
| GET /api/github/repositories?scope=all&page=1 | 현재 사용자가 접근 가능한 저장소 |
| GET /api/github/repositories?scope=personal&page=1 | 현재 사용자가 소유한 저장소 |
| GET /api/github/repositories?scope=organization&organization=team&page=1 | 활성 조직 멤버십 확인 후 조직 저장소 |
| GET /api/projects/{projectId}/repositories | 현재 GitHub 접근을 재검증한 연결 목록 |
| POST /api/projects/{projectId}/repositories | 원본 ID로 검증 후 연결 |
| DELETE /api/projects/{projectId}/repositories/{repositoryId} | 로컬 연결 해제 |

연결 요청은 설치 ID 없이 원본 저장소 ID만 보낸다.

```json
{"githubRepositoryId":"67890"}
```

## 데이터와 권한

- 프로젝트 소유자 또는 활성 ADMIN만 연결·해제할 수 있다. 비멤버는 404, 조회 전용 멤버의 수정은 403이다.
- 외부 호출 전에 프로젝트 권한을 검사하고, 저장 트랜잭션 안에서도 다시 검사한다. 외부 HTTP 요청 중 DB 트랜잭션을 유지하지 않는다.
- 연결 시 서버가 사용자 OAuth 토큰으로 GitHub 원본 ID·소유자·이름·기본 브랜치·공개 여부를 확인한다. 클라이언트의 이름·URL을 그대로 저장하지 않는다.
- 연결 목록에서도 현재 토큰으로 접근을 재검증한다. 권한을 잃은 비공개 저장소를 캐시만으로 반환하지 않는다.
- 토큰은 MySQL의 암호화한 세션에서만 읽는다. scope 부족·만료·다른 Client ID의 기존 세션은 재로그인을 요구한다.
- V3 마이그레이션으로 repositories.installation_id를 NULL 허용하고 authorization_method를 추가했다. OAUTH 연결은 installation_id=NULL, GITHUB_APP 연결은 실제 installation_id를 갖도록 CHECK로 보장한다. 가짜 설치 행을 만들지 않는다.
- 기존 GitHub App 연결은 삭제하지 않는다. 같은 프로젝트의 중복 원본 ID는 409이며 해제 후 재연결하면 같은 행을 사용한다.
- 연결은 zello DB에 정보를 저장한다. 이슈·PR·커밋 수집은 후속 기능이며 초기 수집 완료로 표시하지 않는다. 나머지 업무 화면은 아직 샘플 데이터다.

OAuth App의 repo scope는 비공개 저장소 읽기와 쓰기를 함께 포함한다. 이 권한 범위를 로그인 화면에서 안내하고 실제 승인은 GitHub에서 받는다. 로그인 이후 저장소 선택 UI는 zello 안에서 처리한다.

## 검증

OAuth scope·state·PKCE, 설치 없는 저장과 재연결, 위조 메타데이터 무시, 프로젝트 권한, CSRF, 조직 활성 멤버십, 페이지 탐색, 접근 취소에 따른 비공개 정보 숨김을 테스트한다. 실제 브라우저 승인과 원본 조회는 새 OAuth App 자격 증명이 있어야 검증할 수 있다.

이전 GitHub App 설치·setup callback은 호환 경로로 남아 있으나 기본 프론트에서 사용하지 않는다. OAuth App에는 Setup URL을 등록하지 않고 로그인 Callback URL만 사용한다.

공식 문서: [사용자 저장소 API](https://docs.github.com/en/rest/repos/repos#list-repositories-for-the-authenticated-user), [조직 API](https://docs.github.com/en/rest/orgs/orgs), [OAuth 승인과 조직 정책](https://docs.github.com/en/apps/oauth-apps/using-oauth-apps/authorizing-oauth-apps).


## 다중 저장소 선택 — 2026-11-01

프로젝트와 저장소 연결은 1:N이며 `UNIQUE(project_id, github_repo_id)`는 같은 프로젝트 안의 중복만 차단한다. 화면에서 계정/페이지/검색을 바꾸며 최대 50개를 선택해 한 번에 연결할 수 있다. 개별 연결 API를 순서대로 호출해 각 저장소의 현재 GitHub 접근과 프로젝트 관리 권한을 검증한다. 전역 재로그인/권한 오류는 남은 요청을 중단하며, 저장소별 실패는 성공 연결을 유지하고 실패만 재시도한다. 다른 관리자가 먼저 연결했거나 응답이 유실된 경우 409 이후 현재 연결 목록으로 확인해 중복 성공을 안전하게 처리한다.

수집은 전체 연결 처리 후 프로젝트별 한 번만 실행한다. 가입 안내에서 부분 실패 시 화면에 남아 결과를 확인하거나 연결된 저장소로 시작할 수 있다. 일반 focus 이벤트로 재조회하지 않고 조직 승인 화면에서 돌아온 경우에만 자동 갱신한다. 체크 선택은 갱신·계정·페이지 전환 시 유지하며 프로젝트 변경 때 비운다.
