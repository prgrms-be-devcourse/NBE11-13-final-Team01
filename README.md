# ZIPICK (집픽)

공공임대 공급주택을 지도에서 탐색하고, 사용자 자격정보와 희망 주거조건을 바탕으로 추천 점수와 근거를 제공하는 서비스.

```
zipick-backend/    Java 25 / Spring Boot 4.1.1 / Spring Security / JPA / MySQL
zipick-frontend/   React / TypeScript / Vite
```

## 백엔드 로컬 실행

### 1. 사전 준비

- JDK 25 (Gradle toolchain 이 사용한다)
- 로컬 MySQL

Gradle 은 wrapper(9.3)를 사용하므로 별도 설치가 필요 없다.

### 2. 스키마 생성

```sql
CREATE DATABASE IF NOT EXISTS zipick
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. 환경 변수 설정

아래 표를 참고해 환경 변수를 설정한다. **실제 값은 Git 에 커밋하지 않는다.**

| 이름 | 용도 | 필수     | 기본값         |
| --- | --- |--------|-------------|
| `DB_HOST` | MySQL 호스트 | 선택     | `localhost` |
| `DB_PORT` | MySQL 포트 | 선택     | `3306`      |
| `DB_NAME` | 스키마 이름 | 선택     | `zipick`    |
| `DB_USERNAME` | MySQL 계정 | 선택     | `root`       |
| `DB_PASSWORD` | MySQL 비밀번호 | **필수** | 없음          |
| `JWT_SECRET` | JWT(HS256) 서명 키 | **필수** | 없음          |

`JWT_SECRET` 은 **Base64 로 인코딩된 문자열**이어야 하며, 디코딩한 결과가 **32바이트(256비트) 이상**이어야 한다.
Base64 가 아니거나 길이가 모자라면 애플리케이션 기동 시 오류가 발생한다.
아래 명령으로 생성한 값을 그대로 사용한다.

```bash
openssl rand -base64 32 | tr -d '\n'
```

생성한 값은 각자 로컬 환경 변수로만 설정하고 Git 에 커밋하지 않는다.

설정 예시 — 아래 `{...}` 자리에 본인 값을 넣는다.

**IntelliJ** — Run/Debug Configurations > Environment variables

```text
DB_USERNAME={your_mysql_user};DB_PASSWORD={your_mysql_password};JWT_SECRET={your_jwt_secret}
```

**Windows PowerShell**

```powershell
$env:DB_USERNAME = "{your_mysql_user}"
$env:DB_PASSWORD = "{your_mysql_password}"
$env:JWT_SECRET = "{your_jwt_secret}"
```

**macOS / Linux**

```bash
export DB_USERNAME="{your_mysql_user}"
export DB_PASSWORD="{your_mysql_password}"
export JWT_SECRET="{your_jwt_secret}"
```

### 4. 스키마 초기화

로컬 프로파일은 `ddl-auto: validate` 라 테이블을 자동 생성하지 않는다.
초기화 DDL 을 직접 실행한다.

```bash
mysql -u {your_mysql_user} -p zipick < zipick-backend/db/local-init.sql
```

> `zipick-backend/db/local-init.sql` 은 기존 테이블을 DROP 후 재생성하므로 **데이터가 모두 삭제된다. 로컬 전용이며 운영 DB 에는 적용하지 않는다.**
> 엔티티가 추가·변경되면 초기화 DDL 도 함께 갱신해 커밋한다.

### 5. 실행

```bash
cd zipick-backend
./gradlew build          # 빌드
./gradlew bootRun        # 실행
```
