-- 로컬 개발용 예시 데이터 (가짜 데이터). local-init.sql 실행 뒤에 넣는다.
--   mysql -u {your_mysql_user} -p zipick < zipick-backend/db/local-seed.sql
-- 금액은 만 원, 면적은 ㎡. 카카오 카테고리: SW8 지하철역, MT1 대형마트, CS2 편의점, HP8 병원, SC4 학교

-- 사용자
INSERT INTO users (id, kakao_id, nickname, role, created_at, updated_at) VALUES
  (1, 'kakao-1001', '지우', 'USER', NOW(), NOW()),
  (2, 'kakao-1002', '현우', 'USER', NOW(), NOW()),
  (3, 'kakao-9999', '운영자', 'ADMIN', NOW(), NOW());

-- 기본 정보 (사용자 1: 27세 미혼 1인, 사용자 2: 31세 기혼 2인·소득 미입력)
INSERT INTO user_profiles (user_id, birth_date, job, married, household_size, monthly_income, total_assets, car_value, homeless, student, subscription_months, subscription_payments, created_at, updated_at) VALUES
  (1, '1999-03-14', '사무직', false, 1, 260, 2500, 0, true, false, 30, 30, NOW(), NOW()),
  (2, '1995-07-02', '개발자', true, 2, NULL, 8000, 1500, true, NULL, NULL, NULL, NOW(), NOW());

-- 희망 주거조건
INSERT INTO user_preferences (user_id, regions, deposit_min, deposit_max, rent_min, rent_max, area_min, area_max, housing_types, rental_types, monthly_budget, created_at, updated_at) VALUES
  (1, '[{"code":"11440","name":"마포구","rank":1},{"code":"11410","name":"서대문구","rank":2},{"code":"11380","name":"은평구","rank":3}]',
      1000, 3000, 0, 35, 20.00, 40.00, '["APARTMENT","MULTI_HOUSEHOLD"]', '["PURCHASE","HAPPY"]', 45, NOW(), NOW()),
  (2, '[{"code":"11440","name":"마포구","rank":1},{"code":"11560","name":"영등포구","rank":2}]',
      2000, 6000, 0, 50, 40.00, 60.00, '["APARTMENT"]', '["HAPPY","PURCHASE"]', NULL, NOW(), NOW());

-- 추천 기준 (사용자 1: 비용 우선, 사용자 2: 지역 '꼭')
INSERT INTO recommendation_profiles (user_id, name, active, weights, rules, infra_weights, version, created_at, updated_at) VALUES
  (1, '기본', true, '{"cost":5,"region":4,"area":3,"infra":3,"type":2}', '{}', '{"SW8":5,"CS2":4,"MT1":2,"HP8":3}', 1, NOW(), NOW()),
  (1, '역세권형', false, '{"cost":3,"region":3,"area":2,"infra":5,"type":2}', '{}', '{"SW8":5,"BUS":4}', 1, NOW(), NOW()),
  (2, '기본', true, '{"cost":3,"region":5,"area":4,"infra":3,"type":3}', '{"region":"MUST"}', '{}', 1, NOW(), NOW());

-- 공고
INSERT INTO announcements (id, external_id, agency, rental_type, title, region, status, notice_date, close_date, pdf_url, detail_url, created_at, updated_at) VALUES
  (1, 'LH-2026-P-0301', 'LH', 'PURCHASE', '2026년 제3차 청년 매입임대주택 입주자 모집', '서울', 'RECEIVING', '2026-10-10', '2026-10-22', 'https://example.com/lh-p-0301.pdf', 'https://apply.lh.or.kr/', NOW(), NOW()),
  (2, 'LH-2026-H-0120', 'LH', 'HAPPY', '가좌역 행복주택 입주자 모집', '서울', 'NOTICE', '2026-10-05', '2026-10-30', 'https://example.com/lh-h-0120.pdf', 'https://apply.lh.or.kr/', NOW(), NOW()),
  (3, 'LH-2026-P-0288', 'LH', 'PURCHASE', '2026년 신혼부부 매입임대주택 I 입주자 모집', '서울', 'RECEIVING', '2026-10-08', '2026-10-24', 'https://example.com/lh-p-0288.pdf', 'https://apply.lh.or.kr/', NOW(), NOW()),
  (4, 'LH-2026-P-0150', 'LH', 'PURCHASE', '2026년 제2차 청년 매입임대주택 (접수마감)', '서울', 'CLOSED', '2026-08-01', '2026-08-20', NULL, 'https://apply.lh.or.kr/', NOW(), NOW()),
  (5, 'SH-2026-N-0042', 'SH', 'NATIONAL', '2026년 하반기 국민임대주택 예비입주자 모집', '서울', 'RECEIVING', '2026-10-06', '2026-10-27', 'https://example.com/sh-n-0042.pdf', 'https://www.i-sh.co.kr/', NOW(), NOW()),
  (6, 'HUG-2026-D-0007', 'HUG', 'DEUNDEUN', '든든전세주택 7차 입주자 모집', '서울', 'NOTICE', '2026-10-11', '2026-11-04', 'https://example.com/hug-d-0007.pdf', 'https://www.khug.or.kr/', NOW(), NOW());

-- 공급주택
INSERT INTO supply_houses (id, announcement_id, address, sigungu_code, lat, lng, approximate, housing_type, built_year, floor, exclusive_area, deposit, monthly_rent, conversion_rate, purchase_type, applicants, supply_count, created_at, updated_at) VALUES
  (1, 1, '서울 마포구 망원동 ○○로 12', '11440', 37.5563000, 126.9236000, false, 'MULTI_HOUSEHOLD', 2019, 3, 29.40, 1500, 32, 6.00, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (2, 1, '서울 마포구 성산동 ○○길 7', '11440', 37.5641000, 126.9105000, false, 'MULTI_HOUSEHOLD', 2016, 2, 18.00, 1200, 28, 6.00, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (3, 1, '서울 서대문구 남가좌동 ○○로 3', '11410', 37.5772000, 126.9180000, false, 'MULTI_HOUSEHOLD', 2021, 4, 33.10, 2000, 30, 6.00, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (4, 1, '서울 은평구 응암동 ○○로 55', '11380', 37.5985000, 126.9152000, true, 'OFFICETEL', 2013, 7, 24.50, 1000, 38, 6.00, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (5, 1, '서울 강서구 화곡동 ○○로 21', '11500', 37.5412000, 126.8407000, false, 'MULTI_HOUSEHOLD', 2018, 1, 27.00, 900, 25, 6.00, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (6, 1, '서울 마포구 합정동 ○○로 88', '11440', 37.5497000, 126.9138000, false, 'APARTMENT', 2022, 9, 36.00, 3500, 45, 6.00, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (7, 2, '서울 서대문구 북가좌동 가좌역 행복주택', '11410', 37.5805000, 126.9222000, false, 'APARTMENT', 2024, 6, 26.00, 2400, 21, 6.00, NULL, 320, 40, NOW(), NOW()),
  (8, 2, '서울 서대문구 북가좌동 가좌역 행복주택', '11410', 37.5805000, 126.9222000, false, 'APARTMENT', 2024, 12, 44.00, 5200, 34, 6.00, NULL, 180, 20, NOW(), NOW()),
  (9, 3, '서울 마포구 상암동 ○○로 101', '11440', 37.5790000, 126.8900000, false, 'APARTMENT', 2020, 8, 49.80, 4800, 42, 6.00, '신혼부부 매입임대', NULL, 1, NOW(), NOW()),
  (10, 3, '서울 영등포구 당산동 ○○로 9', '11560', 37.5343000, 126.9020000, false, 'MULTI_HOUSEHOLD', 2017, 3, 42.00, 3000, 40, NULL, '신혼부부 매입임대', NULL, 1, NOW(), NOW()),
  (11, 4, '서울 마포구 연남동 ○○로 2', '11440', 37.5620000, 126.9250000, false, 'MULTI_HOUSEHOLD', 2015, 2, 22.00, 1000, 30, 6.00, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (12, 1, '서울 마포구 ○○동 (주소 일부 미확인)', '11440', NULL, NULL, true, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '청년 매입임대', NULL, 1, NOW(), NOW()),
  (13, 5, '서울 마포구 상암동 ○○단지 국민임대', '11440', 37.5815000, 126.8862000, false, 'APARTMENT', 2012, 5, 39.00, 2600, 23, 6.00, NULL, 210, 30, NOW(), NOW()),
  (14, 5, '서울 은평구 진관동 ○○단지 국민임대', '11380', 37.6370000, 126.9180000, false, 'APARTMENT', 2010, 11, 46.00, 3300, 28, 6.00, NULL, 150, 24, NOW(), NOW()),
  (15, 6, '서울 서대문구 홍은동 ○○빌라', '11410', 37.5880000, 126.9420000, false, 'MULTI_HOUSEHOLD', 2018, 3, 34.00, 9000, 0, 6.00, NULL, NULL, 1, NOW(), NOW());

-- 주변 인프라 요약 (반경 1km)
INSERT INTO infra_summaries (supply_house_id, category_counts, nearest, ai_summary, collected_at, created_at, updated_at) VALUES
  (1, '{"SW8":1,"CS2":4,"MT1":0,"HP8":3,"SC4":2}',
      '{"SW8":{"name":"망원역","distanceM":420},"CS2":{"name":"GS25 망원점","distanceM":120},"HP8":{"name":"망원내과","distanceM":300},"SC4":{"name":"망원초","distanceM":650}}',
      '지하철역이 도보 7분 거리이고 편의점이 4곳 있어요. 반경 1km 안에 대형마트는 없어요.', NOW(), NOW(), NOW()),
  (2, '{"SW8":1,"CS2":3,"MT1":1,"HP8":2,"SC4":1}',
      '{"SW8":{"name":"마포구청역","distanceM":780},"CS2":{"name":"CU 성산점","distanceM":200},"MT1":{"name":"홈플러스 월드컵점","distanceM":900},"HP8":{"name":"성산의원","distanceM":450},"SC4":{"name":"성산초","distanceM":400}}',
      '마트와 학교가 가깝지만 지하철역은 도보 13분 정도 걸려요.', NOW(), NOW(), NOW()),
  (3, '{"SW8":1,"CS2":5,"MT1":0,"HP8":4,"SC4":2}',
      '{"SW8":{"name":"가좌역","distanceM":350},"CS2":{"name":"세븐일레븐","distanceM":90},"HP8":{"name":"가좌연합의원","distanceM":280},"SC4":{"name":"가좌초","distanceM":500}}',
      '역과 편의점, 병원이 모두 가까워요.', NOW(), NOW(), NOW()),
  (4, '{"SW8":0,"CS2":2,"MT1":0,"HP8":1,"SC4":1}',
      '{"CS2":{"name":"이마트24","distanceM":350},"HP8":{"name":"응암의원","distanceM":800},"SC4":{"name":"응암초","distanceM":900}}',
      '반경 1km 안에 지하철역과 대형마트가 없어요.', NOW(), NOW(), NOW()),
  (5, '{"SW8":1,"CS2":3,"MT1":1,"HP8":2,"SC4":2}',
      '{"SW8":{"name":"화곡역","distanceM":600},"CS2":{"name":"GS25","distanceM":150},"MT1":{"name":"이마트 가양점","distanceM":1300},"HP8":{"name":"화곡내과","distanceM":500},"SC4":{"name":"화곡초","distanceM":380}}',
      '생활 시설은 고르게 있지만 대형마트는 멀어요.', NOW(), NOW(), NOW()),
  (6, '{"SW8":2,"CS2":6,"MT1":1,"HP8":5,"SC4":1}',
      '{"SW8":{"name":"합정역","distanceM":250},"CS2":{"name":"CU 합정점","distanceM":80},"MT1":{"name":"메세나폴리스 마트","distanceM":300},"HP8":{"name":"합정메디컬","distanceM":200},"SC4":{"name":"합정초","distanceM":700}}',
      '역·마트·병원이 모두 도보 5분 안에 있어요.', NOW(), NOW(), NOW()),
  (7, '{"SW8":1,"CS2":3,"MT1":0,"HP8":2,"SC4":2}',
      '{"SW8":{"name":"가좌역","distanceM":150},"CS2":{"name":"GS25 가좌역점","distanceM":180},"HP8":{"name":"가좌의원","distanceM":420},"SC4":{"name":"가좌초","distanceM":550}}',
      '역세권이고 편의점이 가까워요.', NOW(), NOW(), NOW()),
  (8, '{"SW8":1,"CS2":3,"MT1":0,"HP8":2,"SC4":2}',
      '{"SW8":{"name":"가좌역","distanceM":150},"CS2":{"name":"GS25 가좌역점","distanceM":180},"HP8":{"name":"가좌의원","distanceM":420},"SC4":{"name":"가좌초","distanceM":550}}',
      '역세권이고 편의점이 가까워요.', NOW(), NOW(), NOW()),
  (9, '{"SW8":1,"CS2":4,"MT1":1,"HP8":3,"SC4":3}',
      '{"SW8":{"name":"디지털미디어시티역","distanceM":700},"CS2":{"name":"CU 상암점","distanceM":100},"MT1":{"name":"홈플러스 상암점","distanceM":500},"HP8":{"name":"상암메디컬","distanceM":350},"SC4":{"name":"상암초","distanceM":300}}',
      '마트와 학교가 가깝고 역은 도보 12분 정도예요.', NOW(), NOW(), NOW()),
  (10, '{"SW8":1,"CS2":5,"MT1":1,"HP8":4,"SC4":2}',
      '{"SW8":{"name":"당산역","distanceM":300},"CS2":{"name":"세븐일레븐 당산점","distanceM":70},"MT1":{"name":"롯데마트","distanceM":1100},"HP8":{"name":"당산내과","distanceM":250},"SC4":{"name":"당산초","distanceM":450}}',
      '역과 병원이 가깝고 편의점이 많아요.', NOW(), NOW(), NOW()),
  (13, '{"SW8":1,"CS2":3,"MT1":1,"HP8":2,"SC4":2}',
      '{"SW8":{"name":"디지털미디어시티역","distanceM":900},"CS2":{"name":"GS25 상암단지점","distanceM":110},"MT1":{"name":"홈플러스 상암점","distanceM":650},"HP8":{"name":"상암연세의원","distanceM":400},"SC4":{"name":"상암중","distanceM":350}}',
      '단지 안에 편의점이 있고 마트가 가까워요. 역은 도보 15분 정도예요.', NOW(), NOW(), NOW()),
  (14, '{"SW8":1,"CS2":2,"MT1":1,"HP8":2,"SC4":3}',
      '{"SW8":{"name":"구파발역","distanceM":1200},"CS2":{"name":"CU 진관점","distanceM":200},"MT1":{"name":"롯데몰 은평점","distanceM":1100},"HP8":{"name":"진관내과","distanceM":500},"SC4":{"name":"진관초","distanceM":300}}',
      '학교가 가깝고 조용한 단지예요. 역과 마트는 조금 멀어요.', NOW(), NOW(), NOW()),
  (15, '{"SW8":0,"CS2":3,"MT1":0,"HP8":2,"SC4":1}',
      '{"CS2":{"name":"세븐일레븐 홍은점","distanceM":140},"HP8":{"name":"홍은의원","distanceM":380},"SC4":{"name":"홍은초","distanceM":600}}',
      '편의점과 병원은 가깝지만 반경 1km 안에 지하철역이 없어요.', NOW(), NOW(), NOW());
