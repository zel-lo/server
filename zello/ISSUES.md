# 이슈와 가입 안내 — 2026-10-31

사용자가 추가한 「Zello 관련 정리」의 4·5단계와 화면 명세를 기준으로 구현했다. 현재 운영 중인 OAuth App 로그인은 유지한다. 문서의 GitHub App 설치·installation token 설계는 별도 전환 작업이며 이번 구현으로 전환하지 않는다.

## 사용자 흐름

- 로그인 후 프로젝트의 연결 저장소가 없으면 가입 안내로 이동한다. 사용 가능한 저장소를 고르면 프로젝트 이름은 소유자 이름으로 준비한다. 기존 프로젝트도 선택할 수 있다.
- 조직 접근 승인 버튼은 서버의 공개 Client ID로 GitHub의 해당 앱 승인 페이지를 바로 연다. 돌아오면 기존 조직·저장소 목록을 자동 갱신한다. 조직 소유자의 승인이 필요한 경우 이를 안내한다. 비밀 값 입력이나 개발자 설정을 사용자에게 요구하지 않는다.
- 실제 팀 작업·내가 맡은 작업은 같은 이슈 API와 상태를 사용한다. 프로젝트 선택은 두 화면과 PR 화면에서 같은 현재 프로젝트를 사용한다.
- 새 작업은 zello 서버에 먼저 저장한다. 내부 키는 `ZELLO-{DB ID}`이며 GitHub 번호와 독립적이다. GitHub 발행은 상세의 별도 버튼으로 진행한다.
- 삭제 대신 보관·복원을 제공한다. 보관은 GitHub 원본을 수정하거나 닫지 않는다. 발행된 이슈를 완료 처리하면 GitHub 상태도 닫힘으로 저장한다.

## API

ID는 문자열, 인증은 HttpOnly 서버 세션, 변경 요청은 CSRF 헤더를 사용한다.

| API | 동작 |
| --- | --- |
| GET /api/github/authorization | 현재 OAuth 앱의 조직 승인 화면 URL; Client Secret·token 미포함 |
| POST /api/projects/{projectId}/issues | LOCAL 이슈 생성. repositoryId, details, requestKey 필수 |
| GET /api/projects/{projectId}/issues | query, status, mine, activeOnly, archived, page, size 필터; items/total/hasNext 응답 |
| GET /api/projects/{projectId}/issues/{id} | 본문·회의 내용·완료 조건·발행 상태·version |
| PATCH /api/projects/{projectId}/issues/{id} | details, githubState, version을 받아 변경 |
| POST /api/projects/{projectId}/issues/{id}/publish | version으로 점유 후 GitHub 발행 또는 이전 결과 복구 |
| DELETE /api/projects/{projectId}/issues/{id}?version=... | 로컬 보관 |
| POST /api/projects/{projectId}/issues/{id}/restore?version=... | 보관 복원 |
| POST /api/projects/{projectId}/issues/sync | 실제 GitHub 이슈를 페이지별 수집 |
| GET /api/projects/{projectId}/repositories/{repositoryId}/assignees?page=1 | GitHub에서 지정 가능하면서 현재 프로젝트의 활성 회원인 후보; 페이지 정보 포함 |

`details`는 title, description, status, priority, source, meetingDate, meetingContext, acceptanceCriteria, assignee를 포함한다. DAILY/WEEKLY는 회의 날짜가 필요하다. 담당자 미지정은 null이다. 마감일·이슈 타입은 아직 추가하지 않았다.

## 원본과 저장 정책

- 로컬 저장은 외부 이슈를 만들지 않는다. 생성 requestKey와 원본 요청 SHA-256을 저장하여 같은 키·같은 내용은 같은 행을 반환하고 다른 내용은 409로 거부한다.
- GitHub의 title/body/assignees/state/priority 라벨을 동기화한다. 완료 조건은 관리되는 `완료 조건` 본문 섹션으로 발행하고 다시 읽을 때 설명과 분리한다. 회의 맥락과 날짜는 외부로 전송하지 않는다.
- 우선순위 라벨 `priority:high|medium|low`는 발행·수정 시 없으면 만든다. 기존의 다른 라벨과 복수 담당자는 보존한다. 서비스의 assignee는 대표 담당자이며 GitHub에서 여러 사람이 배정된 경우 한 사람으로 표시한다.
- 발행 상태는 LOCAL → PENDING → PUBLISHED, 명확한 거부는 FAILED, 타임아웃·성공 응답 해석 실패 등은 UNKNOWN이다. 네트워크 호출 동안 DB 트랜잭션을 유지하지 않는다.
- 발행 중복 방지용 식별자를 GitHub 본문의 숨김 주석에 포함한다. UNKNOWN/PENDING의 재시도는 원본과 대조만 하고 또 POST하지 않는다. 30초 동안은 진행 중으로 응답하며 이후 확인할 수 있다. 명확한 FAILED는 같은 로컬 이슈로 다시 발행할 수 있다. GitHub API 자체가 멱등 생성을 제공하는 것은 아니다.
- GitHub 결과 조회는 최근 수정된 최대 1,000개 이슈 범위다. 원본에서 식별자가 삭제됐거나 범위 밖에 있으면 UNKNOWN을 유지하고 수동 확인을 안내한다.
- 수집에서는 Issues 응답에 섞인 PR을 제외한다. 페이지와 checkpoint는 같은 트랜잭션에서 저장한다. 중간 페이지가 실패해도 완료된 페이지는 남고 `repository_sync_runs`에 처리 건수·실패·다음 페이지를 기록한다. 재수집은 최신 첫 페이지부터 중복 없이 반영한다. 초기 과거 이력으로 알림을 만들지 않는다.
- 오래된 GitHub 응답으로 더 최신 데이터를 덮어쓰지 않는다. 발행된 이슈를 수정하기 전에 최신 원본을 재조회하여 GitHub에서 변경된 본문·완료 조건·제목도 409로 알리고 먼저 DB를 갱신한다.
- 업무 상태는 현재 저장값으로 관리하고 GitHub 이슈 닫힘은 DONE, 닫혔다 다시 열린 이슈는 TODO로 맞춘다. 연결 PR·브랜치로 상태를 계산하는 확장은 PR 연결 단계에서 추가한다. 병합·빌드·서비스 배포는 별도 상태다.

## 권한과 알림

- 활성 프로젝트 멤버만 조회한다. OWNER/ADMIN/MEMBER만 수정·발행·보관한다. VIEWER는 조회와 수집만 할 수 있다.
- 모든 목록·상세·변경은 현재 사용자의 GitHub 저장소 접근도 재검증한다. 접근이 취소된 저장소의 캐시를 노출하지 않는다.
- 담당자는 프로젝트 활성 회원이며 GitHub에서도 배정 가능한지 확인한다. GitHub 단독 계정, 탈퇴·탈퇴 멤버십은 새 담당자로 지정할 수 없다.
- 생성·담당자 변경 시 다른 활성 회원에게 배정하는 경우 activity와 notification을 이슈와 같은 트랜잭션에서 기록한다. 같은 생성 요청 재전송은 중복 알림을 만들지 않는다. 본인 배정과 과거 이슈 수집은 알림을 만들지 않는다.
- 알림함의 실제 서버 조회·읽음 UI, 웹훅, 주기 수집, PR과 이슈 연결/상태 계산은 다음 단계다. 현재의 샘플 알림 화면과 구분한다.

## 스키마와 검증

V4는 issues.archived_at 및 issues.creation_hash와 보관 조회 인덱스를 추가한다. 기존 데이터를 삭제하지 않으며 Flyway로 적용한다. EntitySchemaTests의 SQL 계약과 ERD 스냅샷도 함께 갱신한다.

이슈 API 통합 테스트는 로컬 저장/발행 분리, 생성 재전송, 충돌, GitHub 원본 변경, 보관/복원, 명확한 실패와 UNKNOWN 복구, 담당자 자격, 같은 트랜잭션의 배정 알림, VIEWER·외부인·CSRF·접근 취소, 페이지 실패 후 진행 보존을 검증한다. GitHub HTTP 테스트는 PR 제외, 페이지 정보, 라벨 준비, 배정 가능 여부와 안전한 오류 응답을 검증한다. 외부 GitHub 쓰기는 테스트 대역으로 검증하며 실제 발행 검증은 지정한 개발 저장소에서 별도로 수행한다.
