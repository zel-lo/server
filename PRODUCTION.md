# zello.asia 운영 설정

## 서버 구성

- GCP VM: `instance-20260827-144854`, `us-central1-a`, `34.45.131.216`
- 서버 계정: `g97choijw`, 운영 디렉터리: `/opt/zello`
- Nginx: `/etc/nginx/sites-available/zz-zello`
- 인증서: `/etc/letsencrypt/live/zello.asia/`, Certbot 타이머로 갱신
- HTTP와 `www.zello.asia`는 `https://zello.asia`로 이동합니다.
- Nginx → `127.0.0.1:8083`의 클라이언트 → 내부 백엔드 → 내부 MySQL 순서입니다.
- 운영 앱을 시작하기 전에는 HTTPS 준비 화면과 HTTP 503을 제공합니다.

Compose와 배포 도구의 원본은 [deploy/production](deploy/production/)입니다. 서버 설정 변경은 이 원본과 `/opt/zello`에 함께 반영해야 합니다. 자동 배포는 앱 이미지를 교체하며 서버 설정 파일은 덮어쓰지 않습니다.

## 1. 운영 OAuth 값 입력

운영용 GitHub OAuth App에 다음 주소를 등록합니다.

| 항목 | 값 |
| --- | --- |
| Homepage URL | `https://zello.asia` |
| Authorization callback URL | `https://zello.asia/login/oauth2/code/github` |

서버에서 `nano /opt/zello/.env`로 아래 두 값만 채웁니다.

```dotenv
GITHUB_CLIENT_ID=
GITHUB_CLIENT_SECRET=
```

DB 암호 2개와 세션 암호화 키는 설치 시 서버에서 생성하며 파일 권한은 600입니다. 기존 암호와 키는 재시작·배포 때 유지합니다. 앱 비밀값은 이 파일에서 읽으며 GCP VM 메타데이터나 GitHub Actions Secrets에서 자동으로 읽지 않습니다. Secret Manager는 별도 연동이 필요합니다.

```sh
cd /opt/zello
python3 preflight.py
./deploy.sh
```

첫 기동 시 MySQL과 Flyway 마이그레이션을 준비하고 상태 검사를 통과하면 준비 화면 대신 실제 앱을 제공합니다. 설치 과정에서 검증된 클라이언트·서버 이미지와 MySQL 이미지를 미리 적재하므로 첫 실행에 레지스트리 PAT는 필요하지 않습니다.

## 2. GitHub Actions 연결

`zel-lo/client`와 `zel-lo/server` 양쪽 저장소의 **Settings → Secrets and variables → Actions**에 입력합니다.

| 종류 | 이름 | 값 |
| --- | --- | --- |
| Repository variable | `DEPLOY_HOST` | `34.45.131.216` |
| Repository variable | `DEPLOY_USER` | `g97choijw` |
| Repository variable | `DEPLOY_ENABLED` | 위 설정이 끝난 뒤 `true` |
| Repository secret | `DEPLOY_SSH_KEY` | 설치 시 만든 배포 전용 개인 키 전체 |
| Repository secret | `DEPLOY_KNOWN_HOSTS` | 설치 시 저장한 서버 호스트 키 파일 전체 |

로컬 Mac의 `server/deploy/production/local-secrets/`에 두 입력 파일이 있습니다. 이 폴더는 Git에서 제외합니다. 터미널에서 다음 명령으로 각각 복사한 뒤 GitHub Secrets 입력란에 붙여 넣습니다.

```sh
pbcopy < server/deploy/production/local-secrets/id_ed25519
pbcopy < server/deploy/production/local-secrets/known_hosts
```

두 명령은 각 Secret을 입력할 때 하나씩 실행합니다. 배포 키는 서버에 등록돼 있으며 앱 배포 명령만 허용합니다. 일반 셸·포트 전달은 허용하지 않습니다.

환경 준비 후 `DEPLOY_ENABLED=true`로 바꾸고 Actions의 **Client CI/CD / Server CI/CD → Run workflow → main**을 실행합니다. 이후 `main` push마다 테스트·이미지 검증·업로드·운영 배포가 순서대로 진행됩니다. `production` 환경의 배포 이력에서 결과를 확인할 수 있습니다.

각 실행의 `GITHUB_TOKEN`으로 자기 저장소의 비공개 GHCR 이미지를 내려받으며, 토큰은 서버 임시 디렉터리에만 저장하고 종료 시 삭제합니다. 별도의 장기 PAT를 GitHub Secrets나 서버에 넣지 않습니다.

## 상태 확인과 복구

```sh
cd /opt/zello
docker compose --env-file .env --env-file .images.env ps
curl --fail https://zello.asia/healthz
curl --fail https://zello.asia/api/auth/status
```

두 저장소의 배포는 서버의 파일 잠금으로 직렬 실행합니다. 상태 검사 실패 시 기존 실행이 있으면 이전 앱 이미지로 복구합니다. Flyway가 적용한 DB 마이그레이션은 이미지 복구로 되돌리지 않으므로 운영 DB 백업을 별도로 관리해야 합니다. 배포 도구는 볼륨을 삭제하지 않습니다.

컨테이너 로그는 서비스별 10MB × 3개로 제한합니다. MySQL 데이터는 `zello_mysql-data` 볼륨에 저장합니다. 클라이언트·백엔드·DB 메모리 상한은 각각 128MB·1GB·768MB입니다.

전체 Compose 구성은 `bash scripts/smoke-production-stack.sh /opt/zello`로 검증합니다. 별도 네트워크·임시 DB 볼륨·임의의 로컬 포트를 사용하며 종료 시 테스트 자원을 삭제합니다. 이 검증은 운영 DB를 생성하거나 변경하지 않습니다.

## 초기 이미지 재적재

새 VM을 준비하거나 초기 이미지가 없는 경우 각 저장소의 **Export verified image** 워크플로에 이미 CI가 발행한 전체 커밋 SHA를 넣습니다. 1일간 보관되는 `container-image-<SHA>` 아티팩트를 서버로 전송하고 아래 명령으로 적재합니다.

```sh
gunzip -c container-image.tar.gz | docker load
docker pull mysql:8.4
```

`.images.env`의 두 이미지 태그와 실제 적재한 태그를 맞춘 뒤 `./deploy.sh`를 실행합니다.
