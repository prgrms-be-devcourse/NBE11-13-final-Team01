# ZIPICK 조회 API 부하테스트

이 폴더의 k6 시나리오는 조회 API만 호출합니다. 실행 대상의 공고·주택 데이터와 인덱스, 서버 사양, 데이터베이스 위치에 따라 결과가 달라집니다. 로컬 측정치를 AWS 성능으로 사용하지 마세요.

## 시나리오

| 파일 | 대상 | 측정 내용 |
| --- | --- | --- |
| `map-list.js` | `GET /api/v1/map/housings` | 지도 목록 조회 |
| `housing-detail.js` | `GET /api/v1/map/housings/{housingId}` | 주택 상세 조회 |
| `filter.js` | `GET /api/v1/houses` | 보증금·월세·면적 필터 |
| `user-flow.js` | 지도 목록 → 주택 상세 | 두 요청을 순서대로 수행하는 사용자 흐름 |

상세와 사용자 흐름은 실행 시작 시 지도 목록에서 실제 주택 ID를 가져옵니다. `HOUSING_IDS`를 지정하면 목록 조회 대신 지정한 ID를 사용합니다. 목록이 비어 있거나 ID가 유효하지 않으면 테스트가 실패합니다. 상세 API가 404인 ID를 성공으로 취급하지 않습니다.

## 실행

백엔드와 테스트 DB, Prometheus/Grafana를 실행한 뒤 프로젝트 루트에서 사용합니다. `BASE_URL`은 **반드시 명시**해야 합니다. 아래는 PowerShell에서 로컬 서버를 대상으로 실행하는 예시입니다.

```powershell
$env:BASE_URL = 'http://localhost:8080'
$env:VUS = '5'
$env:DURATION = '30s'
k6 run --summary-export=map-result.json deploy/load-test/map-list.js
k6 run --summary-export=detail-result.json deploy/load-test/housing-detail.js
k6 run --summary-export=filter-result.json deploy/load-test/filter.js
k6 run --summary-export=flow-result.json deploy/load-test/user-flow.js
```

결과 파일은 테스트 데이터와 운영 정보를 포함할 수 있으므로 확인 후 공유하세요. `HOUSING_IDS=1,2,3`, `FILTER_QUERY=depositMax=3000&rentMax=50&areaMin=15&limit=100`, `THINK_SECONDS=0.5`로 대상을 조정할 수 있습니다. 보증금과 월세 필터는 API 기준 **만원** 단위입니다. `FILTER_QUERY`는 URL 쿼리 문자열로 지정하며 특수문자는 URL 인코딩해 주세요.

기본 `VUS=5`, `DURATION=30s`, `THINK_SECONDS=0.5`는 동작 확인용입니다. 기본 판정 기준 `P95_MS=2000`, `ERROR_RATE=0.01`은 **임시 기준**이며 서비스의 실제 SLA나 팀 합의 목표가 아닙니다. 결과가 기준을 넘거나 HTTP 200·응답 `success=true` 검사가 실패하면 k6가 실패를 반환합니다. 환경 변수로 기준을 조정해 주세요. 네 시나리오는 순서대로 실행하고, 부하 단계와 중단 기준은 대상 환경에 맞게 담당자와 정합니다.

## 결과 확인

- k6 결과: 요청 수, `http_req_duration` p95, `http_req_failed`, 검사 실패 및 처리량을 기록합니다. 혼합 시나리오의 기본 p95는 두 API를 합산한 값이므로 API별 값이 필요하면 결과의 `endpoint` 태그를 따로 분석합니다.
- Grafana `ZIPICK · 부하테스트`: 같은 시간 범위의 API 요청·응답 시간, DB 연결, 서버 CPU·메모리와 컨테이너 지표를 확인합니다. **서버 지표에는 일반 사용자 요청과 자동 점검도 포함**되며 k6 전용 측정값이 아닙니다. 서버 히스토그램 p95는 정해진 구간의 추정치입니다.
- PostgreSQL·cAdvisor 지표는 Exporter가 연결돼 있어야 합니다. `No data`는 0이 아닙니다. 로컬 Docker 환경의 지표가 Windows 전체 또는 AWS EC2의 사용량을 뜻하지는 않습니다.
- 시작·종료 시각, 데이터 건수, 대상 API, VU·기간·생각 시간, 서버 사양, p95·오류율·처리량, CPU·메모리·DB 지표를 함께 남깁니다. `vus`와 `sleep`을 사용하는 폐쇄형 테스트이므로 요청률은 응답 시간에 따라 달라집니다.

AWS에서 테스트하기 전 대상·시간·부하·중단 기준을 운영 담당자와 정하고, 실제 사용자와 외부 서비스에 미치는 영향을 확인해 주세요. 이 폴더에는 Gemini·카카오 호출을 반복하는 시나리오가 없습니다.

2026-10-28 로컬의 낮은 부하 연결 확인 결과는 [시험 기록](results/local-connection-check-2026-10-28.md)을 참고해 주세요. AWS 테스트 결과는 아직 없습니다.
