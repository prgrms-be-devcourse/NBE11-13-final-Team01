import http from 'k6/http';
import { check, sleep, fail } from 'k6';

// BASE_URL은 실행 대상마다 명시합니다. 테스트는 조회 API만 호출합니다.
export const baseUrl = (__ENV.BASE_URL || '').replace(/\/$/, '');
if (!/^https?:\/\//.test(baseUrl)) throw new Error('BASE_URL에 테스트 서버 주소를 지정하세요.');
const vus = Number(__ENV.VUS || '5');
const duration = __ENV.DURATION || '30s';
const maxP95 = Number(__ENV.P95_MS || '2000');
const maxErrorRate = Number(__ENV.ERROR_RATE || '0.01');
if (!Number.isInteger(vus) || vus < 1 || !Number.isFinite(maxP95) || maxP95 <= 0 || !Number.isFinite(maxErrorRate) || maxErrorRate < 0 || maxErrorRate > 1) throw new Error('VUS, P95_MS, ERROR_RATE 설정을 확인하세요.');
export const options = {
  vus, duration,
  thresholds: {
    http_req_failed: [`rate<${maxErrorRate}`],
    http_req_duration: [`p(95)<${maxP95}`],
    checks: ['rate==1'],
  },
};

export function get(path, tag) {
  const res = http.get(baseUrl + path, { tags: { endpoint: tag }, timeout: '15s' });
  check(res, { [`${tag}: HTTP 200`]: r => r.status === 200, [`${tag}: 응답 성공`]: r => { try { return r.json('success') === true; } catch (_) { return false; } } });
  return res;
}

export function housingIds() {
  if (__ENV.HOUSING_IDS) {
    const ids = __ENV.HOUSING_IDS.split(',').map(s => s.trim());
    if (!ids.length || ids.some(id => !/^[1-9]\d*$/.test(id))) fail('HOUSING_IDS에는 양의 정수를 쉼표로 구분해 지정하세요.');
    return ids;
  }
  const response = get('/api/v1/map/housings', 'setup_map');
  if (response.status !== 200) fail('주택 ID 조회에 실패했습니다.');
  let rows;
  try { rows = response.json('data.housings'); } catch (_) { fail('주택 목록 응답을 읽을 수 없습니다.'); }
  const ids = Array.isArray(rows) ? rows.map(x => x.housingId).filter(id => Number.isInteger(id) && id > 0).map(String) : [];
  if (!ids.length) fail('지도 목록에 주택이 없습니다. 유효한 HOUSING_IDS를 지정하거나 테스트 데이터를 준비하세요.');
  return ids;
}

export const filterPath = '/api/v1/houses?' + (__ENV.FILTER_QUERY || 'depositMax=3000&rentMax=50&areaMin=15&limit=100');
export const think = () => sleep(Number(__ENV.THINK_SECONDS || '0.5'));
export const choose = ids => ids[Math.floor(Math.random() * ids.length)];
