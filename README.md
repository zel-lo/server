# server
zello server repository

## CI와 컨테이너 이미지 배포

`main` 대상 PR과 `main` push에서 GitHub Actions가 테스트·빌드, MySQL 컨테이너 기동과 Flyway 마이그레이션을 검증합니다. `main`에서는 검증을 통과한 이미지를 GHCR에 업로드합니다. 실행 방법과 운영 배포 범위는 [CI_CD.md](CI_CD.md)를 참고하세요.

## 설계 문서

- [ERD와 테이블 명세](zello/docs/erd.md): Flyway V1~V5와 JPA에 대응하는 MySQL 스키마 명세.
- [전체 Mermaid ERD](zello/docs/erd.mmd): 21개 테이블의 관계도 원본.
- [MySQL SQL](zello/docs/erd.sql): dbdiagram의 Import → MySQL에서 가져올 DDL.
- [dbdiagram DBML](zello/docs/erd.dbml): dbdiagram 기본 편집창에 바로 붙여넣을 코드.
- [제약 검증](zello/docs/checks/verify_erd.py): SQLite 메모리 DB로 소속·상태 제약 22개 시나리오 검증. MySQL 엔진 검증과는 구분합니다.

## 구현과 실행

- [GitHub 로그인과 MySQL 세션](zello/AUTHENTICATION.md)
- [프로젝트·개인/Organization 저장소 연결](zello/REPOSITORY_CONNECTION.md)
- [개발 계획](zello/DEVELOPMENT_PLAN.md)

- [초대·멤버·권한·소유권 관리](zello/COLLABORATION.md)
- [이슈 로컬 저장·GitHub 발행](zello/ISSUES.md)
