# zello 백엔드 개발 계획

기준일: 2026-10-30. Spring의 21개 엔티티와 MySQL/Flyway 초기화, GitHub 로그인·현재 사용자·로그아웃 API 및 React 세션 연동을 구현했다. 로컬 MySQL에 V1 마이그레이션과 Hibernate validate를 확인했다. 프로젝트 작업 데이터는 아직 샘플이며, 다음 구현 대상은 프로젝트 생성과 GitHub App 설치 범위에서의 개인/Organization 저장소 연결이다.

아래 단계 중 로그인 API는 구현되어 있고, 프로젝트·저장소 및 후속 API 경로는 설계안이다. GitHub App의 설치 권한 승인과 실제 사용자 토큰을 이용한 저장소 접근 검증은 다음 단계에서 진행한다.

## 1. 첫 번째 목표

**GitHub 로그인 → 프로젝트 생성 → 실제 저장소 연결 → 이슈 저장·목록·상세 → GitHub 발행**을 하나의 흐름으로 완성한다.

사용자가 말한 Git 등록은 다음 세 가지 작업으로 구분한다.

1. **로그인**: 현재 사용자의 GitHub 신원을 확인한다.
2. **GitHub App 설치·승인**: zello가 접근할 수 있는 개인/조직 저장소 범위를 확보한다.
3. **프로젝트에 저장소 연결**: 승인된 저장소 중 하나를 zello 프로젝트에 등록한다.

로그인만 성공했다고 비공개 저장소 접근을 허용하지 않고, URL이나 installation_id를 받았다는 이유만으로 연결을 완료하지 않는다.

## 2. 초기 기술 결정

- 서비스 로그인은 **Spring Security + 서버 세션 + HttpOnly 쿠키**로 시작한다. React의 API 요청·로그아웃·CSRF 처리를 함께 구현한다. 서비스 인증용 JWT나 Redis는 현재 필수 항목이 아니다.
- GitHub 인증은 **GitHub App의 사용자 OAuth 승인 흐름을 이용해 로그인과 사용자 권한 연동을 묶는 방향**을 추천한다. 별도 OAuth App을 만든 뒤 App 사용자 승인을 추가하는 중복 구성을 피할 수 있다. 이 방식도 GitHub OAuth 로그인이라는 제품 요구를 충족한다.
- 사용자 입력으로 이슈·PR을 발행하는 요청은 사용자 권한에 맞는 GitHub App user access token을 이용하는 방향으로 잡는다. 백그라운드 수집은 installation access token을 사용하고, 수집한 정보의 열람은 현재 사용자의 원본 접근 권한을 따로 검사한다.
- OAuth access/refresh token은 서버에서 관리한다. 초기 단일 서버에서는 서버 세션 기반 authorized-client 저장소로 시작할 수 있다. 서버 재시작 후 재인증 정책과 refresh를 명시하고, 영속 저장이 필요해지면 암호화된 자격 증명 저장소를 별도 설계한다. 기존 users에 평문 토큰 컬럼을 추가하지 않는다.
- MySQL 스키마는 **Flyway 마이그레이션 + Hibernate validate**로 관리한다. 이미 있는 MySQL DDL 계약을 초기 마이그레이션의 출발점으로 사용하고 실제 MySQL에서 검증한다.
- API 응답은 엔티티를 노출하지 않는 DTO다. ID는 프론트에서 문자열로 취급하고, enum의 대소문자·null 담당자·원본 번호 유무를 변환하는 규칙을 정한다.
- localStorage를 한꺼번에 교체하지 않는다. 완성한 화면부터 API로 바꾸고, 한 화면의 조회·저장 데이터 출처를 통일한다.

GitHub App은 설치 권한과 사용자 권한을 구분하며 사용자 대신 동작할 때 user access token을 사용할 수 있다. [GitHub App 인증 문서](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/about-authentication-with-a-github-app)

## 3. 단계별 구현

### 0단계 — 실제 MySQL 실행 기반

**구현**

- 개발용 MySQL 연결, 로컬 환경 변수와 예시 설정.
- Flyway 의존성·MySQL 지원 모듈·최초 마이그레이션, `ddl-auto=validate`.
- 요청 DTO 검증, 예외 응답 형식, 페이지네이션 기본값, 낙관적 잠금 충돌 응답.
- 테스트 전용 H2를 유지하면서 실제 MySQL의 JSON·CHECK·복합 FK·collation 검증을 추가한다.

**완료 기준**: 빈 MySQL에 마이그레이션이 적용되고, 재실행 시 중복 생성 없이 서버가 시작된다. event_key 대소문자 구분과 잘못된 소속 연결 거부가 실제 MySQL에서도 확인된다.

**커밋 단위 예시**: DB 설정 → 마이그레이션 → 공통 오류·검증.

### 1단계 — GitHub 로그인과 현재 사용자

| 경로 제안 | 목적 |
|---|---|
| `GET /oauth2/authorization/github` | GitHub 사용자 승인 시작 |
| `GET /login/oauth2/code/github` | Spring Security의 OAuth callback |
| `GET /api/me` | 로그인한 zello 회원 조회 |
| `GET /api/auth/csrf` | 프론트의 CSRF 토큰 준비 |
| `POST /api/auth/logout` | 서비스 세션 종료 |

- GitHub 고유 ID로 계정을 식별하고 github_accounts·users를 저장한다.
- 이름·이메일을 계정 식별 키로 쓰지 않는다. 공개 이메일이 없어도 가입한다.
- 탈퇴 회원 처리, 로그인 실패·취소·세션 만료, 허용된 프론트 복귀 경로를 구현한다.
- 프론트의 고정 CURRENT_USER를 현재 사용자 응답으로 바꾼다.

**완료 기준**: 첫 로그인·재로그인에서 회원이 중복 생성되지 않는다. 로그인하지 않은 요청은 401이고, 로그아웃 후 보호 API를 호출할 수 없다. GitHub 토큰을 프론트 응답이나 localStorage로 전달하지 않는다.

**커밋 단위 예시**: 계정 Repository → OAuth 사용자 저장·세션 처리 → 내 정보·로그아웃 API → client 로그인 연동.

### 2단계 — 프로젝트와 멤버 권한

| 경로 제안 | 목적 |
|---|---|
| `POST /api/projects` | 프로젝트 생성 |
| `GET /api/projects` | 내가 참여한 프로젝트 목록 |
| `GET /api/projects/{projectId}` | 프로젝트 상세 |
| `GET /api/projects/{projectId}/members` | 담당자 선택용 멤버 목록 |

- 프로젝트와 생성자의 활성 멤버십을 한 트랜잭션에서 만든다.
- OWNER는 owner_user_id로 판정하고 ADMIN·MEMBER·VIEWER와 함께 권한 검사에 사용한다.
- 모든 프로젝트 API에 활성 회원·멤버십 검사를 적용한다. 요청 본문에서 userId를 받아 행위자로 사용하지 않는다.
- 새 프로젝트를 만드는 데 초대 기능 전체가 먼저 필요하지는 않다. 초대·역할 변경·소유권 이전은 이슈 첫 흐름 완성 후 확장한다.

**완료 기준**: 생성한 프로젝트가 내 목록에 보이고, 다른 프로젝트 ID로 접근해도 데이터가 노출되지 않는다. 소유자 멤버십 저장 실패 시 프로젝트 생성도 롤백된다.

**커밋 단위 예시**: 프로젝트·멤버 Repository → 권한 검사 → 생성 트랜잭션 → 목록·상세·멤버 API.

### 3단계 — GitHub App 설치와 저장소 연결

| 경로 제안 | 목적 |
|---|---|
| `GET /api/projects/{projectId}/github/install-url` | 프로젝트에 연결할 App 설치 URL 준비 |
| `GET /api/github/installations/callback` | 설치 후 복귀·설치 정보 검증 |
| `GET /api/github/installations` | 현재 사용자가 접근할 수 있는 설치 목록 |
| `GET /api/github/installations/{githubInstallationId}/repositories` | 연결 가능한 원본 저장소 목록 |
| `POST /api/projects/{projectId}/repositories` | 검증한 원본 저장소를 프로젝트에 연결 |
| `GET /api/projects/{projectId}/repositories` | 프로젝트에 연결된 저장소 목록 |
| `DELETE /api/projects/{projectId}/repositories/{repositoryId}` | 연결 해제·후속 동기화 중단 |

- 설치 시작 시 프로젝트와 현재 세션의 연관성을 서버에 기록한다.
- callback의 installation_id는 신뢰하지 않고 현재 사용자와 해당 설치의 관계를 GitHub API로 확인한다.
- 연결 요청은 `githubInstallationId`, `githubRepositoryId`를 받고 서버가 원본 정보·설치 범위·사용자 접근 권한을 검증한다. 클라이언트가 보낸 저장소 이름·언어·비공개 여부를 그대로 저장하지 않는다.
- 연결 관리는 OWNER·ADMIN만 허용한다. 동일 프로젝트의 중복 연결은 고유 제약과 409 응답으로 처리한다.
- 연결 확인과 전체 데이터 수집 완료를 구분한다. 이 단계에서 원본 접근이 확인돼도 초기 전체 수집 완료 시각은 아직 기록하지 않는다.
- client 저장소 설정 화면을 API로 바꾼다.

**완료 기준**: 실제 허용된 저장소만 목록에 나오고 연결된다. 위조한 설치 ID·접근 불가능한 저장소·권한 없는 멤버의 연결 요청은 실패한다.

GitHub는 setup URL의 installation_id를 위조할 수 있으므로 사용자와 설치의 연관성을 검증하도록 안내한다. [설치 후 복귀 문서](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-setup-url)

**커밋 단위 예시**: GitHub HTTP 클라이언트·토큰 공급자 → 설치 검증 → 저장소 목록 → 프로젝트 연결·해제 API → client 저장소 연동.

### 4단계 — zello 이슈 저장·조회와 기본 배정 알림

| 경로 제안 | 목적 |
|---|---|
| `POST /api/projects/{projectId}/issues` | 회의 맥락을 포함한 로컬 이슈 저장 |
| `GET /api/projects/{projectId}/issues` | 필터·페이지 기반 이슈 목록 |
| `GET /api/projects/{projectId}/issues/{issueId}` | 상세 |
| `PATCH /api/projects/{projectId}/issues/{issueId}` | 설명·완료 조건·담당자·작업 상태 수정 |

- 첫 흐름은 저장소가 연결된 뒤 로컬 이슈를 LOCAL 상태로 저장하는 것이다. 서버 저장과 GitHub 발행 완료를 UI에서 구분한다.
- 제목·저장소, 회의 경로의 날짜, 담당자 지정 가능 여부와 프로젝트 소속을 검증한다.
- 내부 표시 키와 GitHub 번호를 분리한다. 현재 issue_key는 NOT NULL·UNIQUE이므로 IDENTITY ID 발급 전에 키를 준비해야 한다. 첫 구현에서 독립 키를 발급할지, 숫자형 표시 번호용 카운터를 둘지 정하고 필요하면 작은 스키마 마이그레이션으로 반영한다. 같은 자동 증가 ID를 예상해서 키를 만들지 않는다.
- 수정에는 version을 받고 충돌 시 409로 처리한다. 필터는 상태·내 작업·담당자·미지정·PR 미연결·등록 경로·저장소를 순차적으로 지원한다.
- 생성·배정 이벤트와 수신 대상이 명확한 개인 알림은 같은 트랜잭션에서 만든다. GitHub 계정만 있고 zello 프로젝트의 활성 회원이 아닌 사람에게 서비스 알림을 생성하지 않는다.
- 기존 GitHub 이슈의 초기 수집도 이 단계에서 연결한다. 페이지별 처리·실패 구간을 기록하고 초기 과거 사건 알림은 만들지 않는다. GitHub Issues 응답에 섞이는 PR은 이슈 목록에서 제외한다.
- client의 팀 작업·이슈 상세를 API로 바꾼다. 원본 번호가 없는 이슈는 내부 표시 키로 보여준다.

**완료 기준**: 새로고침과 재로그인 후에도 이슈가 유지된다. 다른 프로젝트·저장소의 ID 연결, VIEWER 수정, 오래된 version 수정이 실패한다. 담당자 미지정도 정상 저장된다.

GitHub Issues API는 PR도 반환할 수 있으므로 pull_request 필드를 확인해 분리한다. [Issues API 문서](https://docs.github.com/en/rest/issues/issues)

**커밋 단위 예시**: 이슈 Repository·조회 → 생성 → 수정·충돌 처리 → 초기 수집 → 배정 활동·알림 → client 이슈 연동.

### 5단계 — 실제 GitHub 이슈 발행

| 경로 제안 | 목적 |
|---|---|
| `POST /api/projects/{projectId}/issues/{issueId}/publish` | 같은 로컬 이슈를 GitHub에 발행·재시도 |

- LOCAL → PENDING → PUBLISHED 흐름과 명확한 실패·결과 불명 상태를 구현한다.
- GitHub 성공 응답의 ID·번호·상태를 함께 저장한다. 프론트에서 원본 번호를 계산하지 않는다.
- 외부 호출 동안 긴 DB 트랜잭션·행 잠금을 유지하지 않는다. 짧은 트랜잭션으로 요청 점유를 기록하고 외부 호출 후 결과를 반영한다.
- publish_request_key는 zello의 요청 중복 방지용이며 GitHub 쪽 멱등성을 보장하지 않는다. 타임아웃 후 원본 생성 여부가 불명확하면 UNKNOWN으로 두고 원본 대조 후 복구한다.
- 발행된 이슈의 GitHub 원본 수정과 zello 전용 메타데이터 수정을 구분한다.

**완료 기준**: 화면에서 발행한 이슈가 실제 GitHub에 한 번 생성되고 원본 번호로 연결된다. 연속 클릭·명확한 실패의 재시도·응답 유실을 서로 구분하고 중복 생성을 막는다.

**커밋 단위 예시**: 원본 발행 클라이언트 → 발행 상태·점유 처리 → 발행 API → 오류·복구 테스트 → client 발행 상태 연동.

### 6단계 — PR 조회·작성과 이슈 연결

- 기존 PR·개인 리뷰어·실제 리뷰 상태를 먼저 수집하고 목록·상세 API를 만든다.
- `GET /api/projects/{projectId}/pull-requests`, `GET /api/projects/{projectId}/pull-requests/{pullRequestId}`.
- 연결된 저장소의 실제 브랜치 조회 API를 제공하고, PR 작성 시 원본/대상 브랜치·변경 존재·기존 PR·사용자 권한을 검증한다.
- `POST /api/projects/{projectId}/pull-requests`는 생성할 로컬 요청을 기록하고 이슈 발행과 같은 실패·불명 결과 정책으로 실제 PR을 만든다. 즉시 성공한 경우와 진행 중인 경우의 응답을 구분한다.
- 해결할 이슈는 같은 저장소로 제한한다. `PUT /api/projects/{projectId}/pull-requests/{pullRequestId}/issues`로 전체 연결 집합을 명시적으로 교체하고 양쪽 화면에 반영한다.
- team_summary는 GitHub 원본 body와 별도로 관리한다. 초안은 검토 대기 집계에서 제외한다.
- 리뷰·승인·병합의 직접 실행은 후속으로 두고 처음에는 원본 상태 조회와 GitHub 이동을 제공한다.

**완료 기준**: 실제 PR과 선택한 이슈의 연결이 저장되고 양쪽 상세에서 조회된다. PR 작성·검토·CI·병합·배포 상태가 섞이지 않는다.

### 7단계 — 동기화 보강·개인 알림·내 작업

- `POST /api/github/webhooks`: 서명 검증, delivery 중복 방지, 빠른 수신 응답과 후속 처리.
- 저장소별 주기·수동 수집, checkpoint, heartbeat·lease 복구, 실패·재시도 이력을 구현한다.
- 원본 갱신 시각과 version을 함께 확인하고 역순·같은 시각·누락 이벤트는 원본 재조회로 정합성을 맞춘다.
- `GET /api/notifications`, `GET /api/notifications/unread-count`.
- `PATCH /api/notifications/{notificationId}`의 read=true/false, `POST /api/notifications/read-all`.
- 목록을 열었다는 이유로 읽음 처리하지 않는다. 상세 확인 성공 또는 명시적 읽음 요청만 반영한다. 모두 읽음은 요청 시점의 범위를 고정한다.
- `GET /api/me/work`에 담당 이슈·검토 요청·내 PR을 제공한다. 프로젝트별·전역 알림 집계의 의미를 구분한다.
- client 개인 알림함·상단 배지·내 작업을 연결한다. 전송 방식은 폴링으로 시작한다.

**완료 기준**: 중복 이벤트가 중복 알림을 만들지 않고, 다른 회원의 알림을 읽을 수 없다. 한 명의 읽음은 다른 수신자에게 영향을 주지 않으며 기존 미확인은 재로그인 후에도 유지된다.

### 8단계 — 프로젝트 운영 기능과 후속 화면

- 초대 발급·수락·취소, 역할 변경·탈퇴, 소유권 이전, 회원 이용 종료.
- 권한을 반영한 대시보드 집계·이력·검색.
- 패치노트 초안·편집·공개, 빌드·환경별 배포 조회.
- 빌드 성공을 배포 성공으로 취급하지 않고 배포 커밋과 PR 포함 관계를 확인한다.

권한 검사와 기본 멤버십은 2단계부터 필수다. 여기서 미루는 것은 관리 UI와 확장 API이며, 권한 없는 요청의 차단을 후순위로 미루는 것이 아니다.

## 4. 바로 다음 개발 묶음

1. 실제 MySQL 연결과 초기 마이그레이션.
2. GitHub App 등록 정보·사용자 승인·설치 callback URL 준비.
3. GitHub 로그인과 `GET /api/me`, 서비스 로그아웃.
4. 프로젝트 생성과 내 프로젝트·멤버 목록.
5. 여기까지 검증한 뒤 저장소 연결 API를 시작한다.

각 기능은 **관련 Repository → Service·트랜잭션 → Controller·DTO → 권한·실패 테스트 → 해당 client 화면 연동** 순으로 끝낸다. 모든 Repository를 먼저 만들고 나중에 서비스를 한꺼번에 붙이는 방식으로 진행하지 않는다.

커밋은 기능의 동작 단위로 나누고, GitHub API는 테스트 대역으로 오류·권한·타임아웃을 재현한 뒤 개발용 저장소에서 실제 흐름을 확인한다. 배정·검토 요청 등 실제 외부 데이터 변경 테스트는 지정한 개발 저장소를 대상으로 한다.
