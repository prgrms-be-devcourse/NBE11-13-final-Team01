-- =============================================================================
-- ZIPICK 로컬 개발용 스키마 초기화 DDL
-- =============================================================================
--
-- [경고] 이 스크립트는 기존 테이블을 DROP 하므로 저장된 데이터가 모두 삭제된다.
--        로컬 개발 환경에서만 실행한다. 운영 DB(RDS 등)에는 절대 적용하지 않는다.
--
-- 사용 방법
--   1. 로컬 MySQL에 zipick 스키마가 있어야 한다.
--        CREATE DATABASE IF NOT EXISTS zipick
--          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
--   2. 스키마가 바뀌면 이 파일을 다시 실행해 초기화한다.
--        mysql -u {사용자명} -p zipick < zipick-backend/db/local-init.sql
--   3. 애플리케이션을 실행해 ddl-auto: validate 검증을 통과하는지 확인한다.
--
-- 관리 규칙
--   - 엔티티가 추가·변경되면 이 파일도 함께 갱신해 커밋한다.
--   - hibernate 의 create-drop 설정은 사용하지 않는다.
--
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. DROP : 자식 테이블부터 삭제해 FK 제약 충돌을 피한다.
-- -----------------------------------------------------------------------------

DROP TABLE IF EXISTS recommendation_pair_choices;
DROP TABLE IF EXISTS recommendation_feedbacks;
DROP TABLE IF EXISTS recommendation_profiles;
DROP TABLE IF EXISTS user_preferences;
DROP TABLE IF EXISTS user_profiles;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS infra_summaries;
DROP TABLE IF EXISTS supply_houses;
DROP TABLE IF EXISTS announcements;


-- -----------------------------------------------------------------------------
-- 2. CREATE : 참조 대상 테이블부터 생성한다.
-- -----------------------------------------------------------------------------

-- Hibernate(MySQLDialect)가 엔티티에서 만든 DDL을 그대로 옮겼다. ddl-auto: validate 와 일치한다.
-- 엔티티를 바꾸면 아래처럼 다시 뽑아 교체한다 (DB 연결 없이 동작).
--   DB_USERNAME=none DB_PASSWORD=none ./gradlew bootRun --args="--spring.jpa.hibernate.ddl-auto=none \
--     --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect \
--     --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
--     --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create \
--     --spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=build/ddl/create.sql \
--     --spring.jpa.properties.hibernate.hbm2ddl.delimiter=; --spring.jpa.properties.hibernate.format_sql=true"
--
-- 테이블 담당: users = 인증, announcements·supply_houses = 공고·공급주택, infra_summaries = 주변 인프라,
--             user_profiles·user_preferences·recommendation_* = 추천 (각 담당자가 엔티티를 확장하면 함께 갱신)

create table announcements (
    close_date date,
    notice_date date,
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    external_id varchar(30) not null,
    region varchar(50),
    title varchar(200) not null,
    detail_url varchar(500),
    pdf_url varchar(500),
    agency enum ('GH','HUG','LH','SH') not null,
    rental_type enum ('DEUNDEUN','HAPPY','NATIONAL','PURCHASE') not null,
    status enum ('CLOSED','NOTICE','RECEIVING') not null,
    primary key (id)
) engine=InnoDB;

create table infra_summaries (
    collected_at datetime(6),
    created_at datetime(6),
    id bigint not null auto_increment,
    supply_house_id bigint not null,
    updated_at datetime(6),
    ai_summary varchar(500),
    category_counts text not null,
    nearest text,
    primary key (id)
) engine=InnoDB;

create table recommendation_feedbacks (
    created_at datetime(6),
    id bigint not null auto_increment,
    supply_house_id bigint not null,
    updated_at datetime(6),
    user_id bigint not null,
    reason enum ('ETC','EXPENSIVE','INFRA','OLD','REGION','SMALL'),
    type enum ('DISLIKE','HIDE') not null,
    primary key (id)
) engine=InnoDB;

create table recommendation_pair_choices (
    created_at datetime(6),
    id bigint not null auto_increment,
    left_house_id bigint not null,
    right_house_id bigint not null,
    updated_at datetime(6),
    user_id bigint not null,
    choice enum ('LEFT','RIGHT','TIE') not null,
    primary key (id)
) engine=InnoDB;

create table recommendation_profiles (
    active bit not null,
    version integer not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    user_id bigint not null,
    name varchar(30) not null,
    infra_weights text,
    rules text,
    weights text not null,
    primary key (id)
) engine=InnoDB;

create table supply_houses (
    applicants integer,
    approximate bit not null,
    built_year integer,
    conversion_rate decimal(5,2),
    deposit integer,
    exclusive_area decimal(6,2),
    floor integer,
    lat decimal(10,7),
    lng decimal(10,7),
    monthly_rent integer,
    supply_count integer,
    announcement_id bigint not null,
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    sigungu_code varchar(10),
    purchase_type varchar(30),
    address varchar(200) not null,
    housing_type enum ('APARTMENT','ETC','MULTI_HOUSEHOLD','OFFICETEL','ROW_HOUSE'),
    primary key (id)
) engine=InnoDB;

create table user_preferences (
    area_max decimal(6,2),
    area_min decimal(6,2),
    deposit_max integer,
    deposit_min integer,
    monthly_budget integer,
    rent_max integer,
    rent_min integer,
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    user_id bigint not null,
    housing_types text,
    regions text not null,
    rental_types text,
    primary key (id)
) engine=InnoDB;

create table user_profiles (
    birth_date date not null,
    car_value integer,
    homeless bit not null,
    household_size integer not null,
    married bit not null,
    monthly_income integer,
    student bit,
    subscription_months integer,
    subscription_payments integer,
    total_assets integer,
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    user_id bigint not null,
    job varchar(30),
    primary key (id)
) engine=InnoDB;

create table users (
    created_at datetime(6),
    id bigint not null auto_increment,
    updated_at datetime(6),
    nickname varchar(30) not null,
    kakao_id varchar(50) not null,
    role enum ('ADMIN','USER') not null,
    primary key (id)
) engine=InnoDB;

alter table announcements
   add constraint UKn4aj5scxqnr845itouw7hg9c9 unique (external_id);

alter table infra_summaries
   add constraint UK5dc2g54ecqq7vy5v2jme8klax unique (supply_house_id);

create index idx_rec_feedback_user
   on recommendation_feedbacks (user_id);

create index idx_rec_pair_user
   on recommendation_pair_choices (user_id);

create index idx_rec_profile_user
   on recommendation_profiles (user_id);

create index idx_supply_house_announcement
   on supply_houses (announcement_id);

create index idx_supply_house_latlng
   on supply_houses (lat, lng);

create index idx_supply_house_sigungu
   on supply_houses (sigungu_code);

alter table user_preferences
   add constraint UKqy8dkrkc8b34dcgwoq2km43rd unique (user_id);

alter table user_profiles
   add constraint UKe5h89rk3ijvdmaiig4srogdc6 unique (user_id);

alter table users
   add constraint UKk4ycaj27putgcujmehwbsrmmc unique (kakao_id);

alter table infra_summaries
   add constraint FK1swmbn5ufconr0ntq600qf7j1
   foreign key (supply_house_id)
   references supply_houses (id);

alter table supply_houses
   add constraint FKl08h482tixet0vfxivk0coy4q
   foreign key (announcement_id)
   references announcements (id);
