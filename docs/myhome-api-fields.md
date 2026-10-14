# 마이홈 API 필드 목록

ZIPICK에서 사용하는 마이홈 API 2개의 현재 응답 필드를 정리한다. API 응답 DTO에는 우선 전체 필드를 보존하고, 실제 저장 필드는 서비스 요구사항에 맞춰 추후 선택한다.

## 공공주택 모집공고 조회

- 요청 주소: `GET https://apis.data.go.kr/1613000/HWSPR02/rsdtRcritNtcList`
- ZIPICK 테스트 주소: `GET /api/external/myhome/notices`

### 조회 조건

| 필드 | 의미 |
| --- | --- |
| `brtcCode` | 광역시도 코드 |
| `signguCode` | 시군구 코드 |
| `pageNo` | 페이지 번호 |
| `numOfRows` | 페이지당 데이터 개수 |
| `suplyTy` | 공급유형 |
| `houseTy` | 주택유형 |
| `lfstsTyAt` | 전세형 모집 여부 |
| `bassMtRntchrgSe` | 월임대료 구분 |
| `yearMtBegin` | 모집공고 시작월, `YYYYMM` |
| `yearMtEnd` | 모집공고 종료월, `YYYYMM` |

### 응답 데이터

| 필드 | 의미 |
| --- | --- |
| `suplyHoCo` | 공급 호수 |
| `pblancId` | 모집공고 ID |
| `houseSn` | 공고 내 주택 일련번호 |
| `sttusNm` | 모집 상태명 |
| `pblancNm` | 모집공고명 |
| `suplyInsttNm` | 공급기관명 |
| `houseTyNm` | 주택유형명 |
| `suplyTyNm` | 공급유형명 |
| `beforePblancId` | 이전 모집공고 ID |
| `rcritPblancDe` | 모집공고일 |
| `przwnerPresnatnDe` | 당첨자 발표일 |
| `refrnc` | 문의처 또는 참고정보 |
| `url` | 관련 URL |
| `pcUrl` | PC 상세 URL |
| `mobileUrl` | 모바일 상세 URL |
| `hsmpNm` | 단지명 |
| `brtcNm` | 광역시도명 |
| `signguNm` | 시군구명 |
| `fullAdres` | 전체 주소 |
| `rnCodeNm` | 도로명 정보 |
| `refrnLegaldongNm` | 참고 법정동명 |
| `pnu` | 필지고유번호 |
| `heatMthdNm` | 난방 방식명 |
| `totHshldCo` | 전체 세대수 |
| `sumSuplyCo` | 공급 세대수 합계 |
| `rentGtn` | 임대보증금 |
| `enty` | 계약금 |
| `prtpay` | 중도금 |
| `surlus` | 잔금 |
| `mtRntchrg` | 월임대료 |
| `beginDe` | 접수 시작일 |
| `endDe` | 접수 종료일 |

## 공공임대주택 단지정보 조회

- 요청 주소: `GET https://apis.data.go.kr/1613000/HWSPR04/rentalHouseGwList`
- ZIPICK 테스트 주소: `GET /api/external/myhome/complexes`

### 조회 조건

| 필드 | 의미 |
| --- | --- |
| `brtcCode` | 광역시도 코드, 필수 |
| `signguCode` | 시군구 코드, 필수 |
| `pageNo` | 페이지 번호, 필수 |
| `numOfRows` | 페이지당 데이터 개수, 필수 |

시군구 코드는 법정동 코드 5자리가 아니라 마이홈 코드표의 3자리 코드를 사용한다. 예: 전북특별자치도 익산시 `brtcCode=52`, `signguCode=140`.

### 응답 데이터

| 필드 | 의미 |
| --- | --- |
| `hsmpSn` | 단지 일련번호 |
| `insttNm` | 공급·관리 기관명 |
| `brtcCode` | 광역시도 코드 |
| `brtcNm` | 광역시도명 |
| `signguCode` | 시군구 코드 |
| `signguNm` | 시군구명 |
| `hsmpNm` | 단지명 |
| `rnAdres` | 도로명 주소 |
| `pnu` | 필지고유번호 |
| `competDe` | 준공일 |
| `hshldCo` | 세대수 |
| `suplyTyNm` | 공급유형명 |
| `styleNm` | 주택형 이름 |
| `suplyPrvuseAr` | 공급 전용면적 |
| `suplyCmnuseAr` | 공급 공용면적 |
| `houseTyNm` | 주택유형명 |
| `heatMthdDetailNm` | 난방 방식 상세명 |
| `buldStleNm` | 건물 형태명 |
| `elvtrInstlAtNm` | 엘리베이터 설치 여부명 |
| `parkngCo` | 주차 대수 |
| `bassRentGtn` | 기본 임대보증금 |
| `bassMtRntchrg` | 기본 월임대료 |
| `bassCnvrsGtnLmt` | 기본 전환보증금 한도 |

## 공통 응답 포장 필드

| 필드 | 의미 |
| --- | --- |
| `response.header.resultCode` | 결과 코드. `00`이면 정상 |
| `response.header.resultMsg` | 결과 메시지 |
| `response.body.totalCount` | 전체 결과 개수 |
| `response.body.numOfRows` | 페이지당 결과 개수 |
| `response.body.pageNo` | 현재 페이지 |
| `response.body.item` | 실제 데이터 목록 |
