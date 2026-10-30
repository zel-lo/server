# server
zello server repository

## 설계 문서

- [ERD와 테이블 명세](zello/docs/erd.md): 현재 기능과 GitHub 연동을 기준으로 한 MySQL 설계 초안.
- [전체 Mermaid ERD](zello/docs/erd.mmd): 21개 테이블의 관계도 원본.
- [MySQL SQL](zello/docs/erd.sql): dbdiagram의 Import → MySQL에서 가져올 DDL.
- [dbdiagram DBML](zello/docs/erd.dbml): dbdiagram 기본 편집창에 바로 붙여넣을 코드.
- [제약 검증](zello/docs/checks/verify_erd.py): SQLite 메모리 DB로 소속·상태 제약 21개 시나리오 검증. MySQL 엔진 검증과는 구분합니다.

## 구현과 실행

- [GitHub 로그인과 MySQL 세션](zello/AUTHENTICATION.md)
- [프로젝트·개인/Organization 저장소 연결](zello/REPOSITORY_CONNECTION.md)
- [개발 계획](zello/DEVELOPMENT_PLAN.md)
