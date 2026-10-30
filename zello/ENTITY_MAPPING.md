# JPA 엔티티 매핑

수정한 ERD의 21개 테이블은 `src/main/java/com/zello/zello/domain` 아래에 도메인별로 나뉘어 있습니다. 각 엔티티와 해당 저장 테스트는 별도 커밋으로 추가했습니다.

## 매핑 기준

- 단일 PK는 MySQL `AUTO_INCREMENT`에 대응하는 `GenerationType.IDENTITY`입니다. 연결 테이블 5개는 `@EmbeddedId`를 사용합니다.
- 공통 생성·수정 시각은 UTC `LocalDateTime`이며 마이크로초 단위로 기록합니다. 프로젝트·이슈·PR·패치노트에는 `@Version`을 적용했습니다.
- FK 컬럼은 ID 필드에서 쓰고, 객체 연관관계는 지연 로딩하는 읽기 전용 매핑입니다. 여러 복합 FK가 project_id·repository_id를 공유하므로 같은 컬럼을 여러 연관관계가 중복해서 쓰지 않도록 했습니다.
- Builder에는 FK ID 대신 이미 저장된 부모 엔티티를 전달합니다. 생성 시 부모 ID를 확인하고, 공유하는 프로젝트·저장소가 일치하는지 검사합니다. 연관관계를 바꾸는 메서드는 ID와 객체 참조를 함께 갱신합니다. 연관 객체에 값을 대입하는 것만으로 FK가 바뀌지는 않습니다.
- 연쇄 삭제와 양방향 컬렉션은 추가하지 않았습니다. 회원 탈퇴·멤버 탈퇴·저장소 연결 해제는 해당 상태와 시각을 변경합니다.
- 상태 enum은 ordinal 대신 VARCHAR 문자열로 저장합니다. JSON 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)`과 `Map<String, Object>`로 매핑했습니다.
- 47개 FK 및 48개 CHECK는 엔티티의 매핑과 제약으로 보존합니다. 활동 대상 수의 CHECK는 MySQL과 H2에서 같은 의미가 되도록 CASE 표현식으로 작성했습니다.
- API 응답에는 엔티티를 직접 노출하지 않고 DTO를 사용해야 합니다.

## 검증

```sh
./gradlew test
./gradlew build
```

Java 25 toolchain을 사용합니다. 테스트 클래스패스의 `application.yaml`이 H2의 MySQL 모드와 create-drop을 설정하며, 운영 설정에는 테스트 DB 구성이 적용되지 않습니다.

- 21개 엔티티 저장·재조회, 복합키 조회, JSON 보존, 개별 알림 읽음, 상태 변경과 버전 증가를 검사합니다.
- 네이티브 SQL을 이용해 잘못된 프로젝트·저장소 연결, 비멤버 알림, 모순된 탈퇴·발행 상태, 초기 수집의 과거 알림 허용을 DB가 거부하는지 검사합니다.
- 오래된 엔티티의 변경은 낙관적 잠금으로 거부되는지 확인합니다.
- `src/test/resources/schema/erd-contract.sql`은 매핑 검증용 MySQL DDL 계약입니다. 테스트는 이 파일을 H2에서 실행하지 않고, 실제 Hibernate 매핑의 테이블·컬럼·NULL 허용·FK·CHECK와 대조합니다.
- MySQL dialect로 생성한 DDL은 `build/reports/schema/mysql-entities.sql`에 남깁니다. 이 단계는 MySQL 서버 실행 검증과 구분합니다.

실제 DB 배포 시에는 DDL 계약에 있는 collation·DB 기본 시각·엔진 설정까지 포함한 마이그레이션을 사용해야 합니다. 특히 event_key의 대소문자 구분은 MySQL에서 `utf8mb4_bin`으로 보장해야 합니다. 운영에서 Hibernate create/update로 스키마를 관리하는 설정은 추가하지 않았습니다.

소유자의 활성 멤버십, 사용자·GitHub 접근 권한, 소유권 이전과 탈퇴의 트랜잭션, 원본 시각 비교 및 역순 이벤트 처리, 외부 API 재시도는 서비스 계층에서 이어서 구현할 항목입니다. 엔티티의 버전·상태 컬럼 자체가 이 처리를 대신하지는 않습니다.
