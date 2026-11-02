# 이슈와 PR 연결

팀 작업과 내가 맡은 작업, 코드 검토는 `issue_pull_requests`의 같은 연결을 사용한다. 같은 프로젝트·저장소의 이슈와 실제 발행된 PR을 다대다로 연결한다. GitHub 번호가 없는 로컬 이슈도 연결할 수 있다.

## 화면과 API

- PR 작성에서 저장소의 이슈를 검색하고 여러 개 선택한다. 페이지를 바꿔도 선택을 유지한다. 작업의 **PR 작성**으로 들어오면 저장소·제목·이슈를 미리 채운다.
- 이슈 상세에서 기존 PR 연결·해제와 새 PR 작성을 제공한다. PR 상세에서도 이슈를 연결·해제하고, 양쪽 상세로 이동한다. 보드·이슈 목록·PR 목록에 연결 항목을 표시한다.
- `POST /api/projects/{projectId}/pull-requests`에 선택 사항 `issueIds`(최대 50개)를 추가했다. 예약 트랜잭션에서 검증한 연결을 함께 저장하고, 재시도 시 선택 집합도 대조한다. 발행 실패·결과 불명 상태에서도 예약된 연결은 유지하고 실제 발행 후 노출한다. 수집이 먼저 완료돼도 연결을 유지한다.
- `POST` / `DELETE /api/projects/{projectId}/issues/{issueId}/pull-requests/{pullId}?version={issueVersion}`으로 연결·해제한다. 같은 요청 재전송은 멱등 처리한다. 실제 변경은 프로젝트 잠금과 이슈 version으로 보호하며 상태가 그대로여도 version을 올린다.
- 이슈 응답의 `pullRequests`, PR 응답의 `linkedIssues`에 연결된 항목의 ID·번호·제목·상태·주소를 포함한다.
- PR 목록은 `repositoryId`, `unlinked=true` 필터를 서버에서 적용하고 페이지·total을 계산한다. 탭 counts는 기존처럼 검토/연결 필터와 무관한 전체 개수다.

## 상태

계산은 서버 한 곳에서 처리하고 DB 작업 상태에 반영하므로 주차·상태·내 작업 필터도 같은 결과를 사용한다. 연결 변경·PR 발행·PR 수집·리뷰 후 재조회·병합·GitHub 이슈 수집 및 편집 시 다시 계산한다.

1. GitHub 이슈가 닫혔거나 사용자가 명시적으로 완료한 작업은 완료를 유지한다.
2. 연결된 열린 PR 중 초안 또는 수정 요청이 하나라도 있으면 진행 중이다.
3. 그 밖에 열린 PR이 있으면 검토 중이다. 승인은 병합 전까지 검토 중으로 유지한다.
4. 열린 PR 없이 병합된 PR이 있으면 완료다. 완료 시각은 마지막 병합 시각이며 GitHub 이슈 닫힘 시각이 있으면 그 값을 우선한다.
5. 병합 없이 닫힌 PR은 완료 근거로 사용하지 않는다. 유효한 PR 연결이 모두 사라지면 원래 계획 상태로 복원한다.

V10은 `issues.status_before_pr`를 추가해 자동 상태를 적용하기 전 계획을 보존한다. 기존 마이그레이션은 수정하지 않는다. PR 상태에 따른 zello 완료는 GitHub 이슈를 직접 닫거나 서비스 배포를 수행하지 않는다.

## GitHub와 권한

새 PR은 선택한 이슈 중 GitHub에 발행된 항목의 `Closes #번호`를 본문에 추가한다. GitHub 자동 닫기는 기본 브랜치 병합 및 저장소 설정을 따른다. 기존 PR의 수동 연결·해제는 zello 관계만 수정한다. 본문에 이미 있는 `Closes` 참조를 없애거나 추가하려면 GitHub에서 본문을 편집한다. GitHub에서 만든 Development 연결의 자동 수집은 이번 범위에 포함하지 않는다.

연결 변경은 OWNER/ADMIN/MEMBER만 가능하며 CSRF와 현재 GitHub 저장소 접근을 검사한다. 다른 저장소·프로젝트 연결, 보관된 이슈의 신규 연결은 거부한다. VIEWER는 연결 조회만 가능하며 기존 연결은 보관된 작업에서도 해제할 수 있다.

GitHub 문서: https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue
