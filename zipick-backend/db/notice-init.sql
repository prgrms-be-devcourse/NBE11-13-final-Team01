-- ZIPICK 공고 수집 파트 로컬 MySQL DDL
-- 팀 공통 local-init.sql 실행 후 이 파일을 실행한다.
-- 이 스크립트도 기존 공고 수집 테이블을 DROP하므로 로컬에서만 사용한다.

DROP TABLE IF EXISTS housing_supply;
DROP TABLE IF EXISTS housing;
DROP TABLE IF EXISTS notice;
DROP TABLE IF EXISTS agency;

create table agency (
    agency_id bigint not null auto_increment,
    agency_code varchar(30) not null,
    agency_name varchar(100) not null,
    source_url varchar(1000),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    primary key (agency_id),
    constraint uk_agency_code unique (agency_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table notice (
    missing_count integer not null default 0,
    announced_at date,
    last_missing_checked_on date,
    winner_announced_at date,
    agency_id bigint,
    apply_end_at datetime(6),
    apply_start_at datetime(6),
    collected_at datetime(6) not null,
    created_at datetime(6) not null,
    last_seen_at datetime(6),
    notice_id bigint not null auto_increment,
    updated_at datetime(6) not null,
    source varchar(30) not null,
    status varchar(30) not null,
    status_reason varchar(30),
    external_notice_id varchar(100) not null,
    notice_type varchar(100),
    raw_status varchar(100),
    region varchar(100),
    rental_type varchar(100),
    previous_external_notice_id varchar(100),
    title varchar(500),
    detail_url varchar(2000),
    mobile_url varchar(2000),
    pc_url varchar(2000),
    raw_payload json,
    reference_text text,
    primary key (notice_id),
    constraint uk_notice_source_external_id unique (source, external_notice_id),
    constraint fk_notice_agency foreign key (agency_id) references agency (agency_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table housing (
    active bit not null default 1,
    build_year integer,
    floor integer,
    missing_count integer not null default 0,
    total_household_count integer,
    latitude decimal(10,7),
    longitude decimal(10,7),
    created_at datetime(6) not null,
    housing_id bigint not null auto_increment,
    last_missing_checked_on date,
    last_seen_at datetime(6),
    notice_id bigint not null,
    updated_at datetime(6) not null,
    pnu varchar(30),
    district_name varchar(100),
    heating_method varchar(100),
    province_name varchar(100),
    legal_dong_name varchar(255),
    name varchar(255),
    road_name varchar(255),
    address varchar(500) not null,
    normalized_address varchar(500),
    source_housing_key varchar(500) not null,
    raw_payload json,
    primary key (housing_id),
    constraint uk_housing_notice_source_key unique (notice_id, source_housing_key),
    constraint fk_housing_notice foreign key (notice_id) references notice (notice_id) on delete cascade,
    constraint ck_housing_latitude check (latitude is null or latitude between -90 and 90),
    constraint ck_housing_longitude check (longitude is null or longitude between -180 and 180)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create table housing_supply (
    active bit not null default 1,
    floor integer,
    missing_count integer not null default 0,
    room_count integer,
    supply_count integer,
    supply_house_count integer,
    exclusive_area decimal(10,2),
    balance bigint,
    contract_deposit bigint,
    created_at datetime(6) not null,
    external_house_id bigint,
    housing_id bigint not null,
    housing_supply_id bigint not null auto_increment,
    interim_payment bigint,
    last_missing_checked_on date,
    last_seen_at datetime(6),
    monthly_rent bigint,
    rent_deposit bigint,
    updated_at datetime(6) not null,
    housing_type varchar(100),
    supply_type varchar(100),
    building_dong varchar(100),
    unit_number varchar(100),
    source_supply_key varchar(500) not null,
    raw_payload json,
    primary key (housing_supply_id),
    constraint uk_housing_supply_source_key unique (housing_id, source_supply_key),
    constraint fk_housing_supply_housing foreign key (housing_id) references housing (housing_id) on delete cascade
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;

create index idx_notice_status on notice (status);
create index idx_notice_rental_type on notice (rental_type);
create index idx_notice_agency_id on notice (agency_id);
create index idx_notice_apply_period on notice (apply_start_at, apply_end_at);
create index idx_housing_notice_id on housing (notice_id);
create index idx_housing_active on housing (active);
create index idx_housing_pnu on housing (pnu);
create index idx_housing_coordinates on housing (latitude, longitude);
create index idx_housing_supply_housing_id on housing_supply (housing_id);
create index idx_housing_supply_active on housing_supply (active);
create index idx_housing_supply_deposit on housing_supply (rent_deposit);
create index idx_housing_supply_monthly_rent on housing_supply (monthly_rent);
