# 서버 CI와 컨테이너 이미지 배포

설정: [Server CI/CD](.github/workflows/ci-cd.yml)

## 실행 조건과 결과

- `main` 대상 PR: Java 25에서 `./gradlew --no-daemon test bootJar`와 Docker 이미지 검증을 수행합니다.
- `main` push: 위 검증 후 `ghcr.io/zel-lo/server:sha-<전체 커밋 SHA>` 이미지를 업로드합니다.
- GitHub Actions의 `Run workflow`로 수동 실행할 수 있습니다. 이미지 업로드는 `main` 실행에만 적용됩니다.
- 테스트 보고서와 실행 JAR는 실행의 Artifacts에 7일간 보관합니다.

컨테이너 검증에서는 임시 MySQL 8.4를 생성하고 전체 Flyway 마이그레이션, JPA 스키마 검증, `/actuator/health`와 `/api/auth/status` 응답을 확인합니다. 검증용 컨테이너와 볼륨은 종료 시 삭제합니다. 실제 운영 DB와 OAuth 비밀값은 사용하지 않습니다.

## GitHub 반영

이 설정을 `main`에 push하면 자동 실행됩니다. 저장소에서 GitHub Actions 사용과 `GITHUB_TOKEN`의 `packages: write` 권한이 허용되어 있어야 합니다. 별도 PAT나 Docker Hub 비밀값은 필요하지 않습니다.

실행 결과: https://github.com/zel-lo/server/actions

## 로컬 검증

```sh
cd zello
./gradlew --no-daemon test bootJar
cd ..
docker build -t zello-server:local .
bash scripts/smoke-container.sh zello-server:local
```

Java 25와 실행 중인 Docker가 필요합니다. 단위·애플리케이션 테스트는 테스트용 H2 설정을 사용하며 Docker 검증은 별도 MySQL을 사용합니다.

## 운영 배포 범위

`DEPLOY_ENABLED=true`이고 배포용 Secrets가 등록되면 이미지 업로드 후 `zello.asia`에 자동 배포합니다. 설치된 서버 구성과 남은 입력 항목은 [PRODUCTION.md](PRODUCTION.md)를 참고하세요. 설정 전에는 운영 배포 작업을 건너뜁니다.

이미지는 일반 사용자로 8080 포트에서 실행합니다. 운영 환경의 DB·세션 암호화 키·GitHub OAuth 설정은 [AUTHENTICATION.md](zello/AUTHENTICATION.md)에 따라 별도로 주입합니다. `.env`와 개인 키는 이미지 빌드 대상에서 제외됩니다.
