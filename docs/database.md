# 로컬 개발 환경 및 데이터베이스

README의 [로컬 실행 안내](../README.md#로컬-개발-환경-실행)를 보완하는 문서입니다. **다음 초기화 절차는 로컬 DB 전용**입니다.

## 실행 환경

- JDK 25 (Gradle wrapper 9.3 사용)
- PostgreSQL
- Node.js / npm (프론트엔드)

기본 DB 연결 정보는 `application-local.yml`을 따릅니다.

| 변수 | 역할 | 기본값 |
| --- | --- | --- |
| `DB_HOST` | PostgreSQL 호스트 | `localhost` |
| `DB_PORT` | PostgreSQL 포트 | `5432` |
| `DB_NAME` | DB 이름 | `zipick` |
| `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL 계정 | 필수 |
| `JWT_SECRET` | HS256 서명용 Base64 키 (디코딩 시 32바이트 이상) | 필수 |

환경 변수 설정 예시 (`{...}`는 실제 값으로 교체):

**IntelliJ** — Run/Debug Configurations → Environment variables

```text
DB_USERNAME={user};DB_PASSWORD={password};JWT_SECRET={base64_secret}
```

**Windows PowerShell**

```powershell
$env:DB_USERNAME = "{user}"
$env:DB_PASSWORD = "{password}"
$env:JWT_SECRET = "{base64_secret}"
```

**macOS / Linux / WSL**

```bash
export DB_USERNAME="{user}"
export DB_PASSWORD="{password}"
export JWT_SECRET="{base64_secret}"
```

서명 키 생성 (macOS / Linux / WSL):

```bash
openssl rand -base64 32 | tr -d '\n'
```

외부 API 키는 `zipick-backend/config/application-secret.example.yml`을 복사해 `application-secret.yml`을 만들고, 마이홈 API 키와 카카오 REST API 키를 채웁니다. 실제 키가 있는 파일은 Git에 커밋하지 않습니다.

## 스키마 생성과 초기화

로컬 프로파일은 `spring.jpa.hibernate.ddl-auto: validate`이므로 애플리케이션이 테이블을 자동 생성하지 않습니다.

먼저 PostgreSQL에 DB를 생성합니다.

```sql
CREATE DATABASE zipick;
```

그다음 **새 로컬 DB**에서 저장소 루트 기준으로 아래 순서대로 실행합니다.

```bash
psql -U <DB_USERNAME> -d zipick -f zipick-backend/db/local-init.sql
psql -U <DB_USERNAME> -d zipick -f zipick-backend/db/notice-init.sql
psql -U <DB_USERNAME> -d zipick -f zipick-backend/db/infra-init.sql
```

| 파일 | 관리 영역 |
| --- | --- |
| `local-init.sql` | 인증·사용자·추천·알림 관련 테이블 |
| `notice-init.sql` | `agency`, `notice`, `housing`, `housing_supply` 등 공고·공급주택 |
| `infra-init.sql` | `housing_infra_snapshot`, `housing_infra_place` |

- `local-init.sql`만 실행하면 `agency` 등이 없어 기동 시 스키마 검증에 실패합니다.
- `infra-init.sql`은 공고 테이블의 `housing`을 FK로 참조하므로 **반드시 `notice-init.sql` 다음에** 실행합니다.
- 각 SQL은 관련 테이블을 DROP/CREATE할 수 있어 **기존 데이터가 삭제됩니다**. 운영 DB에서는 실행하면 안 됩니다.
- 인프라 테이블이 이미 있으면 FK 때문에 `notice-init.sql`의 `housing` DROP이 실패할 수 있습니다. **로컬 DB를 초기화하기로 합의한 경우에만** 인프라 테이블을 먼저 삭제하거나 DB 전체를 다시 만들어야 합니다.

```sql
-- 로컬 데이터 삭제를 명시적으로 결정한 경우에만 사용
DROP TABLE IF EXISTS housing_infra_place, housing_infra_snapshot;
```

## 기존 로컬 DB에 적용했던 변경 이력

아래는 기존 README에서 옮긴 참고사항입니다. **새 DB에는 위 세 SQL을 순서대로 실행하면 되며**, 이미 적용된 DB에 중복 실행할 필요는 없습니다.

### 인프라 테이블 추가 (2026-10-19)

이전 버전의 로컬 DB를 유지하는 경우 `infra-init.sql`의 실행 여부를 확인합니다. 이 스크립트는 인프라 테이블을 DROP/CREATE하므로 **이미 수집된 인프라 데이터는 삭제됩니다.**

### 사용자 프로필 FK 추가 (2026-10-16)

`user_profiles` / `user_preferences`가 `users`를 참조하도록 FK가 추가되었습니다. 전체 DB를 초기화하지 않고 제약만 추가하는 경우에는 다음을 사용할 수 있습니다(이미 존재하는 FK에는 재실행 금지).

```sql
ALTER TABLE user_profiles
  ADD CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (user_id);
ALTER TABLE user_preferences
  ADD CONSTRAINT fk_user_preferences_user FOREIGN KEY (user_id) REFERENCES users (user_id);
```

`users`에 없는 `user_id`가 기존 테이블에 있으면 FK 추가에 실패합니다. 이력 데이터 확인 후 별도 정리해야 합니다.

## 실행·검증

```bash
cd zipick-backend
./gradlew build
./gradlew bootRun
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- 로컬 프론트엔드: 별도 터미널에서 `zipick-frontend/`로 이동 후 `npm install && npm run dev`
- PostgreSQL, JWT, 외부 API 키가 준비돼야 관련 기능이 정상 동작합니다.
