# 프로젝트와 GitHub 저장소 연결

로그인한 사용자가 zello 프로젝트를 만들고, GitHub App에 설치·승인한 개인/Organization 저장소를 선택하여 프로젝트에 연결한다. 연결은 MySQL에 저장된다. 이 단계는 접근 확인과 연결 등록까지이며 이슈·PR·커밋 수집은 아직 수행하지 않는다.

## 개발 환경

기존 `AUTHENTICATION.md`의 로그인·DB 설정에 다음 값을 추가한다. slug는 GitHub App 공개 페이지 URL의 마지막 부분이다. Client ID나 App ID를 넣는 항목이 아니다.

```dotenv
GITHUB_APP_SLUG=your-github-app-slug
```

현재 로컬 App의 slug는 로컬 `.env`에 설정했으며 실제 Secret과 DB 비밀번호는 Git에 포함하지 않는다. 저장소 연결은 `GITHUB_APP_TYPE=APP`에서 지원한다.

## 사용 방법

1. `http://127.0.0.1:5173/#/login`에서 로그인한다.
2. **저장소** 또는 **프로젝트 설정 → 저장소 연결**로 이동한다.
3. zello 프로젝트를 생성하거나 기존 프로젝트를 선택한다.
4. **GitHub App 설치·저장소 선택**을 누른다. GitHub에서 설치할 개인 계정 또는 Organization과 허용할 저장소를 선택한다. 설치 승인 자체는 사용자가 수행한다.
5. zello 화면으로 돌아와 **연결 목록 새로고침**을 누른다.
6. 계정/Organization과 저장소를 선택하고 **선택한 저장소 연결**을 누른다.

App 설치 대상이 `Only on this account`이면 App 소유 계정에만 설치할 수 있다. 다른 Organization을 대상으로 설치하려면 App의 설치 가능 범위와 조직 정책을 먼저 검토해야 한다. 이 구현은 GitHub App을 공개하거나 조직 권한을 자동으로 확대하지 않는다. Organization에서 설치 승인이 필요한 경우 관리자의 승인을 기다린 뒤 목록을 새로고침한다.

설치 화면은 새 탭에서 열리며, 설치 후 수동으로 새로고침한다. 별도의 setup callback이나 사용자 입력 installation_id를 신뢰하는 흐름을 추가하지 않았다. 설치 후 복귀 URL을 App에 설정할 필요는 없고, OAuth 로그인 callback은 기존 8080 주소를 그대로 사용한다.

## API

인증은 서버 세션이며 모든 응답 ID는 문자열이다. POST/DELETE는 `GET /api/auth/csrf`의 토큰을 응답의 headerName으로 전송해야 한다.

| 요청 | 기능 |
|---|---|
| GET /api/projects | 활성 멤버십이 있는 보관되지 않은 프로젝트 목록 |
| POST /api/projects | 프로젝트와 생성자의 ADMIN 멤버십 생성; 생성자는 OWNER로 표시 |
| GET /api/projects/{projectId} | 프로젝트 상세와 저장소 관리 가능 여부 |
| GET /api/github/config | 서버 설정에서 만든 GitHub App 설치 URL |
| GET /api/github/installations?page=1 | 현재 사용자 토큰으로 접근 가능한 활성 설치 목록 |
| GET /api/github/installations/{installationId}/repositories?page=1 | 해당 설치 및 현재 사용자가 접근 가능한 원본 저장소 목록 |
| GET /api/projects/{projectId}/repositories | 현재 사용자의 GitHub 접근 권한까지 확인한 연결 목록 |
| POST /api/projects/{projectId}/repositories | 검증한 설치·원본 저장소를 프로젝트에 연결 |
| DELETE /api/projects/{projectId}/repositories/{repositoryId} | 연결 해제; GitHub 저장소와 기존 이슈/PR 행은 삭제하지 않음 |

프로젝트 생성 본문:

```json
{"name":"zello 개발","description":"협업 프로젝트"}
```

저장소 연결 본문:

```json
{"githubInstallationId":"12345","githubRepositoryId":"67890"}
```

GitHub 목록은 페이지당 100개이며 `items`, `hasNext`, `page`를 반환한다. 화면에서 이전/다음 페이지를 선택할 수 있다. 연결 검증은 최대 100페이지를 탐색하며 검증이 끝나지 않으면 연결을 허용하지 않는다.

## 권한과 데이터

- 프로젝트 소유자 또는 활성 ADMIN 멤버만 연결·해제할 수 있다. 프로젝트 외부 사용자와 탈퇴한 멤버에게는 404를 반환한다.
- 요청한 설치 ID와 저장소 ID를 GitHub의 사용자 설치·저장소 API로 확인한다. 저장소 상세의 원본 ID, 설치 소유 계정, 저장소 소유 계정을 대조한다. 클라이언트가 보낸 이름·URL을 저장하지 않는다.
- 원본 정보는 고정된 `api.github.com`으로만 요청하며 HTTP redirect를 따라가지 않는다. 외부 네트워크 요청은 DB 트랜잭션 밖에서 실행한다.
- 프로젝트 행 잠금과 `(project_id, github_repo_id)` 고유 제약으로 중복 연결을 차단한다. 이미 연결된 저장소는 409이고, 해제한 저장소의 재연결은 같은 내부 행을 재사용한다.
- 저장소 목록에서도 현재 사용자와 App에 허용된 범위를 재검증한다. GitHub 접근을 잃은 비공개 저장소 이름과 설명을 캐시만으로 반환하지 않는다. 다른 프로젝트의 멤버라는 이유만으로 GitHub 원본 접근 권한이 생기지 않는다.
- `CONNECTED`는 원본 접근을 확인하고 연결 등록이 끝났다는 뜻이다. 초기 수집이 끝났다고 표시하지 않고 `initialSyncCompleted=false`를 반환한다.
- GitHub user access token은 MySQL에 암호화한 현재 서버 세션에서만 읽는다. 토큰 만료·권한 취소 시 다시 로그인하도록 안내한다. 자동 refresh와 백그라운드 installation token 수집은 후속 구현이다.
- 현재 이슈·PR 작업 화면은 샘플 데이터다. 로그인한 사용자의 저장소 설정 화면부터 실제 API로 전환했으며 실제 프로젝트 저장소를 샘플 이슈/PR에 자동으로 합치지 않는다.

## 검증

프로젝트 생성·멤버십, 개인/Organization 연결, 중복과 재연결, 비멤버와 VIEWER 권한 차단, 위조 ID 거부, 접근 취소에 따른 비공개 정보 숨김, CSRF, 세션 토큰 만료, GitHub 페이지 탐색과 안전한 오류 응답을 테스트한다. GitHub HTTP 응답을 테스트 대역으로 제공하므로 실제 App 설치 승인과 원본 저장소 연결은 사용자가 GitHub에서 승인한 후 별도로 확인한다.

공식 문서: [사용자 설치·저장소 조회 API](https://docs.github.com/en/rest/apps/installations), [설치 후 복귀 검증](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-setup-url), [App 설치](https://docs.github.com/en/apps/using-github-apps/installing-your-own-github-app).
