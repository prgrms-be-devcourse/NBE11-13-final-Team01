-- =============================================================================
-- 카카오 소셜 로그인 도입 — users 테이블 마이그레이션
-- =============================================================================
--
-- 대상: 이미 운영 중인 DB (로컬 개발 DB 포함). 데이터를 **보존**한다.
--       새로 만드는 DB 는 이 파일이 아니라 local-init.sql 을 쓴다 (같은 결과가 이미 반영돼 있다).
--
-- 실행
--   psql -U {사용자명} -d zipick -f zipick-backend/db/migration/2026-11-02-kakao-login.sql
--
-- 적용 순서 (중요)
--   1. 이 SQL 을 먼저 실행한다.
--   2. 그 다음 애플리케이션을 배포한다.
--   애플리케이션이 먼저 뜨면 ddl-auto: validate 가 provider 컬럼이 없다고 기동에 실패한다.
--   반대로 1번과 2번 사이에는 구버전 애플리케이션이 새 스키마 위에서 돈다.
--   읽기는 영향이 없고(컬럼을 명시해 조회한다), 쓰기도 provider 기본값 덕에 정상 동작한다.
--
-- ⚠️ Hibernate 의 validate 는 컬럼의 **nullable 여부를 검사하지 않는다.**
--    3번(password NOT NULL 해제)을 빠뜨려도 기동은 성공하고,
--    첫 카카오 로그인 시점에 not-null 제약 위반으로 터진다. 네 단계를 모두 실행해야 한다.
--
-- 되돌리기는 파일 맨 아래 주석 참고.
-- =============================================================================

BEGIN;

-- 1. 인증 수단 컬럼 추가. 기존 회원은 전부 이메일·비밀번호 가입이므로 LOCAL 이다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider    varchar(20);
ALTER TABLE users ADD COLUMN IF NOT EXISTS provider_id varchar(64);

UPDATE users SET provider = 'LOCAL' WHERE provider IS NULL;

-- 기본값을 둬서 "SQL 적용 후 ~ 새 애플리케이션 배포 전" 사이에 구버전이 계속 떠 있어도
-- 회원가입 INSERT 가 깨지지 않게 한다 (구버전은 provider 컬럼을 모르고 보내지 않는다).
-- 새 애플리케이션은 항상 provider 를 직접 넣으므로 이 기본값을 쓰지 않는다.
ALTER TABLE users ALTER COLUMN provider SET DEFAULT 'LOCAL';

ALTER TABLE users ALTER COLUMN provider SET NOT NULL;

-- 2. 카카오가 검증된 이메일을 주지 않으면 email 은 NULL 이 된다.
--    기존 회원의 이메일 값은 그대로 보존된다 (제약만 푼다).
--    이메일 가입의 "이메일 필수"는 SignupRequest 의 @NotBlank @Email 이 계속 담당한다.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- 3. 소셜 계정은 비밀번호가 없다. 기존 회원의 비밀번호 해시는 그대로 보존된다.
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;

-- 4. 같은 카카오 계정이 동시 최초 로그인으로 두 번 생성되는 것을 막는다.
--    LOCAL 계정은 provider_id 가 NULL 이고 PostgreSQL 은 NULL 을 중복으로 보지 않으므로
--    기존 회원 수와 무관하게 제약을 걸 수 있다.
ALTER TABLE users DROP CONSTRAINT IF EXISTS uk_users_provider;
ALTER TABLE users ADD  CONSTRAINT uk_users_provider UNIQUE (provider, provider_id);

COMMIT;


-- 적용 확인 ---------------------------------------------------------------
--   SELECT column_name, data_type, is_nullable
--     FROM information_schema.columns
--    WHERE table_name = 'users'
--    ORDER BY ordinal_position;
--
--   기대값: email YES / password YES / provider NO / provider_id YES
--
--   SELECT provider, count(*) FROM users GROUP BY provider;
--   기대값: 기존 회원 전원이 LOCAL


-- 되돌리기 ----------------------------------------------------------------
-- 카카오 회원이 이미 생겼다면 되돌릴 수 없다 (email·password 가 NULL 인 행이 남는다).
-- 카카오 회원이 없을 때만:
--   BEGIN;
--   ALTER TABLE users ALTER COLUMN provider DROP DEFAULT;
--   ALTER TABLE users DROP CONSTRAINT IF EXISTS uk_users_provider;
--   ALTER TABLE users DROP COLUMN IF EXISTS provider_id;
--   ALTER TABLE users DROP COLUMN IF EXISTS provider;
--   ALTER TABLE users ALTER COLUMN email    SET NOT NULL;
--   ALTER TABLE users ALTER COLUMN password SET NOT NULL;
--   COMMIT;
