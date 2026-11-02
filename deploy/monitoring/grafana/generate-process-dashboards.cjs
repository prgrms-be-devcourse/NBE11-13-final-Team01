// Generate provisioned monitoring dashboards. No application or team service code is changed.
const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, 'dashboards');
const backup = path.join(__dirname, 'previous-dashboards');
fs.mkdirSync(backup, {recursive: true});
for (const name of ['zipick-summary.json', 'zipick-overview.json']) {
  const source = path.join(dir, name), dest = path.join(backup, name);
  if (fs.existsSync(source) && !fs.existsSync(dest)) fs.copyFileSync(source, dest);
}

const ds = {type: 'prometheus', uid: 'zipick-prometheus'};
const clean = 'environment!~"synthetic_test|real_process_failure_test"';
const titles = [
  ['zipick-overview', '서버·컨테이너'],
  ['zipick-details', '메인 서비스'],
  ['zipick-collector', '공고 수집기'],
  ['zipick-notification', '알림 시스템'],
  ['zipick-load-test', '부하테스트']
];
const links = titles.map(([uid, title]) => ({title, type: 'link', url: `/grafana/d/${uid}`, targetBlank: false}));
const target = (expr, legendFormat = '') => ({expr, legendFormat, refId: 'A'});
const mapping = (items) => [{type: 'value', options: Object.fromEntries(items.map(([v, text, color], index) => [String(v), {text, color, index}]))}];
const stateMap = mapping([[0, '연결 실패', 'red'], [1, '정상', 'green']]);
const runningMap = mapping([[0, '대기·종료', 'gray'], [1, '실행 중', 'blue']]);
const exitMap = mapping([[0, '종료 실패', 'red'], [1, '종료 성공', 'green']]);
const grid = (x, y, w, h) => ({x, y, w, h});
function stat(id, title, expr, pos, opts = {}) {
  const steps = opts.dangerPositive ? [{color:'green', value:null}, {color:'red', value:1}] : opts.dangerZero ? [{color:'red', value:null}, {color:'green', value:1}] : [{color:'blue', value:null}];
  const defaults = {displayName: title, noValue: opts.noValue || '측정 정보 없음', color: {mode: 'thresholds'}, thresholds: {mode: 'absolute', steps}};
  defaults.mappings = [...(opts.mappings || []), {type:'special', options:{match:'null',result:{text:opts.noValue || '측정 정보 없음',color:'gray',index:99}}}];
  if (opts.unit) defaults.unit = opts.unit;
  if (opts.decimals !== undefined) defaults.decimals = opts.decimals;
  return {id, title, description:opts.description || '', type:'stat', gridPos:pos, datasource:ds,
    fieldConfig:{defaults, overrides:[]},
    options:{colorMode:'background', graphMode:'none', textMode:'value_and_name', justifyMode:'center', orientation:'vertical', text:{titleSize:20,valueSize:32}, reduceOptions:{values:false,calcs:['lastNotNull'],fields:''}},
    targets:[{...target(expr), instant:true, range:false}]};
}
function graph(id, title, expressions, pos, unit, description = '') {
  return {id,title,description,type:'timeseries',gridPos:pos,datasource:ds,
    fieldConfig:{defaults:{unit:unit || 'short', color:{mode:'palette-classic'}, custom:{drawStyle:'line',lineWidth:2,fillOpacity:8,spanNulls:false}},overrides:[]},
    options:{legend:{displayMode:'list',placement:'bottom',showLegend:true},tooltip:{mode:'multi',sort:'none'}},
    targets:expressions.map(([expr, legendFormat], i)=>({expr, legendFormat, refId:String.fromCharCode(65+i)}))};
}
function dashboard(uid, title, description, panels) {
  return {uid,title,description,schemaVersion:40,version:3,refresh:'30s',time:{from:'now-6h',to:'now'},links,panels};
}
const serverPanels = [
  graph(1,'서버 CPU 사용률',[[`100 * (1 - avg by (server_name) (rate(node_cpu_seconds_total{mode="idle",server_name!="",${clean}}[5m])))`,'{{server_name}}']],grid(0,0,12,9),'percent','서버별 CPU 사용률의 시간 변화입니다.'),
  graph(2,'서버 메모리 사용률',[[`100 * (1 - node_memory_MemAvailable_bytes{server_name!="",${clean}} / node_memory_MemTotal_bytes{server_name!="",${clean}})`,'{{server_name}}']],grid(12,0,12,9),'percent'),
  graph(3,'컨테이너 메모리',[[`container_memory_working_set_bytes{job="cadvisor",name!="",${clean}}`,'{{name}}']],grid(0,9,12,9),'bytes','Docker에서 확인된 컨테이너별 사용량입니다. 지표가 없으면 0이 아닙니다.'),
  graph(4,'컨테이너 CPU',[[`sum by (name) (rate(container_cpu_usage_seconds_total{job="cadvisor",name!="",${clean}}[5m]))`,'{{name}}']],grid(12,9,12,9),'short','코어 단위 사용량입니다. 1은 CPU 코어 한 개를 뜻합니다.'),
  graph(5,'서버 디스크 여유',[[`node_filesystem_avail_bytes{server_name!="",mountpoint="/",fstype!~"tmpfs|overlay",${clean}}`,'{{server_name}}']],grid(0,18,12,8),'bytes','서버 루트 파일시스템의 사용 가능한 용량입니다.'),
  graph(6,'서버 스왑 사용량',[[`node_memory_SwapTotal_bytes{server_name!="",${clean}} - node_memory_SwapFree_bytes{server_name!="",${clean}}`,'{{server_name}}']],grid(12,18,12,8),'bytes')
];
const mainPanels = [
  stat(1,'프론트',`min(probe_success{job="service-probe",service="frontend",${clean}})`,grid(0,0,6,5),{mappings:stateMap,description:'HTTP 접근 가능 여부입니다. 실제 화면 기능 전체를 보장하지 않습니다.'}),
  stat(2,'백엔드',`min(up{job="backend",${clean}})`,grid(6,0,6,5),{mappings:stateMap,description:'백엔드의 지표 포트 접근 상태입니다.'}),
  stat(3,'데이터베이스',`min(pg_up{job="postgres",${clean}})`,grid(12,0,6,5),{mappings:stateMap,description:'PostgreSQL Exporter의 DB 연결 여부입니다.'}),
  stat(4,'DB 지표 수집기',`min(up{job="postgres",${clean}})`,grid(18,0,6,5),{mappings:stateMap,description:'DB 자체의 연결 상태와 별개인 Exporter 접근 여부입니다.'}),
  graph(5,'API 응답 시간',[[`sum(rate(http_server_requests_seconds_sum{job="backend",uri!~"/actuator.*",${clean}}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count{job="backend",uri!~"/actuator.*",${clean}}[5m])), 0.000001)`,'평균'],[`histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="backend",uri!~"/actuator.*",${clean}}[5m])))`,'p95 추정']],grid(0,5,12,9),'s','p95는 정해진 시간 구간에 기반한 추정치이며 요청이 적을 때 불안정합니다.'),
  graph(6,'API 요청과 서버 오류',[[`sum(rate(http_server_requests_seconds_count{job="backend",uri!~"/actuator.*",${clean}}[5m]))`,'전체 요청 / 초'],[`sum(rate(http_server_requests_seconds_count{job="backend",status=~"5..",uri!~"/actuator.*",${clean}}[5m]))`,'서버 오류 / 초']],grid(12,5,12,9),'short'),
  graph(7,'백엔드 DB 연결 대기',[[`sum(hikaricp_connections_pending{job="backend",${clean}})`,'연결을 기다리는 요청']],grid(0,14,12,8),'short'),
  graph(8,'DB 연결 수',[[`sum by (instance) (pg_stat_database_numbackends{job="postgres",${clean}})`,'현재 연결'],[`pg_settings_max_connections{job="postgres",${clean}}`,'설정상 최대']],grid(12,14,12,8),'short')
];
// Distinguish scrape reachability, application health, and a real public API request.
mainPanels[1].title = '백엔드 지표 연결';
mainPanels[1].fieldConfig.defaults.displayName = '백엔드 지표 연결';
mainPanels[5] = graph(6,'API별 평균 응답 시간',[[`sum by (uri) (rate(http_server_requests_seconds_sum{job="backend",uri!~"/actuator.*|UNKNOWN|REDIRECTION",${clean}}[5m])) / clamp_min(sum by (uri) (rate(http_server_requests_seconds_count{job="backend",uri!~"/actuator.*|UNKNOWN|REDIRECTION",${clean}}[5m])), 0.000001)`,'{{uri}}']],grid(12,15,12,9),'s','최근 5분간 요청이 기록된 API별 평균입니다. 요청이 없으면 해석에 주의하세요.');
mainPanels.push(
  stat(9,'백엔드 통합 상태',`min(probe_success{job="service-probe",service="backend_health",${clean}})`,grid(0,5,6,5),{mappings:stateMap,description:'백엔드 Actuator 종합 health의 HTTP 응답입니다. DB 등 포함된 health 항목에 따라 실패할 수 있으며, DB 단독 상태로 단정할 수 없습니다.'}),
  stat(10,'지도 조회 API',`min(last_over_time(probe_success{job="map-api-probe",service="map_api",${clean}}[6m]))`,grid(6,5,6,5),{mappings:stateMap,description:'대표 지도 조회 API의 HTTP 200 여부를 5분마다 확인합니다. 최근 6분 내 기록만 사용하며 응답 내용의 정확성까지 검증하지 않습니다.'}),
  stat(11,'최근 5분 · API 요청',`sum(increase(http_server_requests_seconds_count{job="backend",uri!~"/actuator.*",${clean}}[5m]))`,grid(12,5,6,5),{noValue:'요청 지표 없음',decimals:0,description:'최근 5분의 요청 수 추정치입니다. 지도 API 자동 점검 요청도 포함됩니다.'}),
  stat(12,'최근 5분 · 서버 오류',`sum(increase(http_server_requests_seconds_count{job="backend",status=~"5..",uri!~"/actuator.*",${clean}}[5m]))`,grid(18,5,6,5),{noValue:'오류 지표 없음',decimals:0,dangerPositive:true,description:'최근 5분 HTTP 5xx 응답 수 추정치입니다. 해당 지표가 아직 없으면 0으로 단정하지 않습니다.'})
);
mainPanels.find(p => p.id === 5).gridPos = grid(0,10,12,9);
mainPanels.find(p => p.id === 6).gridPos = grid(12,10,12,9);
mainPanels.find(p => p.id === 7).gridPos = grid(0,19,12,8);
mainPanels.find(p => p.id === 8).gridPos = grid(12,19,12,8);
const collectorPanels = [
  stat(1,'기본 수집기 · 현재',`max(zipick_collector_running{job_type="basic",${clean}})`,grid(0,0,6,5),{mappings:runningMap,noValue:'실행 기록 없음',description:'관측 스크립트 기준이며 강제 종료 시 실제 상태와 다를 수 있습니다.'}),
  stat(2,'기본 수집기 · 마지막 종료',`min(zipick_collector_last_run_success{job_type="basic",${clean}})`,grid(6,0,6,5),{mappings:exitMap,noValue:'실행 기록 없음',description:'프로세스 종료 결과이며 일부 공고 오류가 없다는 뜻은 아닙니다.'}),
  stat(3,'AI 수집기 · 현재',`max(zipick_collector_running{job_type="ai",${clean}})`,grid(12,0,6,5),{mappings:runningMap,noValue:'실행 기록 없음',description:'관측 스크립트 기준입니다.'}),
  stat(4,'AI 수집기 · 마지막 종료',`min(zipick_collector_last_run_success{job_type="ai",${clean}})`,grid(18,0,6,5),{mappings:exitMap,noValue:'실행 기록 없음',description:'종료 성공이어도 문서 추출이나 정규화가 연기될 수 있습니다.'}),
  stat(5,'기본 수집 · 마지막 소요 시간',`max(zipick_collector_last_duration_seconds{job_type="basic",${clean}})`,grid(0,5,6,5),{unit:'s',noValue:'실행 기록 없음'}),
  stat(6,'AI 수집 · 마지막 소요 시간',`max(zipick_collector_last_duration_seconds{job_type="ai",${clean}})`,grid(6,5,6,5),{unit:'s',noValue:'실행 기록 없음'}),
  stat(7,'기본 수집 · 기관 오류',`sum(zipick_collector_last_source_errors{job_type="basic",${clean}})`,grid(12,5,6,5),{unit:'short',noValue:'실행 기록 없음',dangerPositive:true}),
  stat(8,'AI 정규화 · 처리 연기',`sum(zipick_collector_last_normalization_deferred{job_type="ai",${clean}})`,grid(18,5,6,5),{unit:'short',noValue:'실행 기록 없음',dangerPositive:true}),
  stat(9,'Gemini 추출 · 최종 호출 실패',`sum(zipick_collector_last_gemini_final_failures{job_type="ai",stage="extraction",${clean}})`,grid(0,10,6,5),{noValue:'실행 기록 없음',dangerPositive:true,description:'재시도 후에도 실패한 호출 수입니다. 모델명은 수집기 설정을 따릅니다.'}),
  stat(10,'Gemini 정규화 · 최종 호출 실패',`sum(zipick_collector_last_gemini_final_failures{job_type="ai",stage="normalization",${clean}})`,grid(6,10,6,5),{noValue:'실행 기록 없음',dangerPositive:true,description:'재시도 후에도 실패한 호출 수입니다. 모델명은 수집기 설정을 따릅니다.'}),
  stat(11,'카카오 좌표 검색 · 호출',`sum(zipick_collector_last_kakao_geocoding_calls{job_type="basic",${clean}})`,grid(12,10,6,5),{noValue:'실행 기록 없음',description:'기본 수집기의 좌표 검색 기록입니다. 주변 인프라 검색은 포함하지 않습니다.'}),
  stat(12,'카카오 좌표 검색 · 중단 오류',`sum(zipick_collector_last_kakao_geocoding_errors{job_type="basic",${clean}})`,grid(18,10,6,5),{noValue:'실행 기록 없음',dangerPositive:true}),
  graph(13,'Gemini 일시 오류와 최종 실패',[[`sum by (stage) (zipick_collector_last_gemini_http_errors{job_type="ai",${clean}})`,'{{stage}} 호출 오류 (재시도 포함)'],[`sum by (stage) (zipick_collector_last_gemini_final_failures{job_type="ai",${clean}})`,'{{stage}} 최종 호출 실패']],grid(0,15,24,8),'short','호출 오류는 최종 실패를 뜻하지 않습니다. 기록은 마지막 실행 기준입니다.')
];
// Keep the most actionable outcomes visible as separate cards, rather than hiding them in a crowded chart.
collectorPanels.pop();
collectorPanels.push(
  stat(13,'기본 수집 · 마지막 성공',`max(zipick_collector_last_success_timestamp_seconds{job_type="basic",${clean}} > 0) * 1000`,grid(0,15,6,5),{unit:'dateTimeAsLocal',noValue:'성공 기록 없음',description:'수집 프로세스가 마지막으로 정상 종료된 시각입니다. 개별 공고 처리의 성공을 보장하지 않습니다.'}),
  stat(14,'AI 수집 · 마지막 성공',`max(zipick_collector_last_success_timestamp_seconds{job_type="ai",${clean}} > 0) * 1000`,grid(6,15,6,5),{unit:'dateTimeAsLocal',noValue:'성공 기록 없음',description:'AI 수집 프로세스가 마지막으로 정상 종료된 시각입니다. 문서 처리 연기 여부도 확인하세요.'}),
  stat(15,'기본 수집 · 공고 오류',`sum(zipick_collector_last_notice_errors{job_type="basic",${clean}})`,grid(12,15,6,5),{noValue:'실행 기록 없음',dangerPositive:true,description:'오류가 기록된 서로 다른 공고 수입니다. 상세 처리 오류가 포함될 수 있습니다.'}),
  stat(16,'기본 수집 · 상세 처리 오류',`sum(zipick_collector_last_detail_errors{job_type="basic",${clean}})`,grid(18,15,6,5),{noValue:'실행 기록 없음',dangerPositive:true,description:'상세 주택 정보 처리 중 기록된 오류 횟수입니다. 공고 오류와 중복될 수 있습니다.'}),
  stat(17,'AI 추출 · 불완전 문서',`sum(zipick_collector_last_ai_document_errors{job_type="ai",reason="incomplete",${clean}})`,grid(0,20,6,5),{noValue:'실행 기록 없음',dangerPositive:true}),
  stat(18,'AI 추출 · 빈 결과',`sum(zipick_collector_last_ai_document_errors{job_type="ai",reason="empty",${clean}})`,grid(6,20,6,5),{noValue:'실행 기록 없음',dangerPositive:true}),
  stat(19,'AI 추출 · 처리 예외',`sum(zipick_collector_last_ai_document_errors{job_type="ai",reason="exception",${clean}})`,grid(12,20,6,5),{noValue:'실행 기록 없음',dangerPositive:true}),
  stat(20,'AI 정규화 · 처리 오류',`sum(zipick_collector_last_normalization_errors{job_type="ai",${clean}})`,grid(18,20,6,5),{noValue:'실행 기록 없음',dangerPositive:true,description:'호출 실패와는 별도로 기록된 정규화 처리 오류입니다.'}),
  stat(21,'Gemini 추출 · 성공 호출',`sum(zipick_collector_last_gemini_http_success{job_type="ai",stage="extraction",${clean}})`,grid(0,25,6,5),{noValue:'실행 기록 없음',description:'호출 성공 횟수이며 문서가 완전하거나 정확하다는 뜻은 아닙니다.'}),
  stat(22,'Gemini 정규화 · 성공 호출',`sum(zipick_collector_last_gemini_http_success{job_type="ai",stage="normalization",${clean}})`,grid(6,25,6,5),{noValue:'실행 기록 없음',description:'호출 성공 횟수이며 정규화 결과의 정확성을 보장하지 않습니다.'}),
  stat(23,'Gemini 최종 실패 · 호출 제한',`sum(zipick_collector_last_gemini_final_failures{job_type="ai",category="429",${clean}})`,grid(12,25,6,5),{noValue:'실행 기록 없음',dangerPositive:true,description:'429 오류로 최종 실패한 호출입니다. 추출과 정규화를 합산합니다.'}),
  stat(24,'Gemini 최종 실패 · 서버 오류',`sum(zipick_collector_last_gemini_final_failures{job_type="ai",category="5xx",${clean}})`,grid(18,25,6,5),{noValue:'실행 기록 없음',dangerPositive:true,description:'503을 포함한 5xx 오류로 최종 실패한 호출입니다. 추출과 정규화를 합산합니다.'}),
  stat(25,'Gemini 최종 실패 · 네트워크',`sum(zipick_collector_last_gemini_final_failures{job_type="ai",category="network",${clean}})`,grid(0,30,6,5),{noValue:'실행 기록 없음',dangerPositive:true}),
  stat(26,'Gemini 최종 실패 · 기타',`sum(zipick_collector_last_gemini_final_failures{job_type="ai",category="other",${clean}})`,grid(6,30,6,5),{noValue:'실행 기록 없음',dangerPositive:true})
);
// Sequence the cards by operational purpose so related information stays together.
const collectorOrder = [1,2,5,13,3,4,6,14,7,15,16,11,12,17,18,19,8,20,9,10,21,22,23,24,25,26];
collectorPanels.sort((a,b) => collectorOrder.indexOf(a.id) - collectorOrder.indexOf(b.id));
collectorPanels.forEach((panel,i) => { panel.gridPos = grid((i % 4) * 6, Math.floor(i / 4) * 5, 6, 5); });
const notificationPanels = [
  stat(1,'알림센터 접속',`min(probe_success{job="service-probe",service="notification",${clean}})`,grid(0,0,6,5),{mappings:stateMap,description:'알림센터 GET /health의 HTTP 응답입니다. DB·큐 연결과 실제 발송 성공을 보장하지 않습니다.'}),
  stat(2,'RabbitMQ 지표 수집',`min(up{job="rabbitmq",${clean}})`,grid(6,0,6,5),{mappings:stateMap,description:'RabbitMQ 지표 접근 상태이며 메시지 처리 성공을 보장하지 않습니다.'}),
  stat(3,'알림 요청 · 처리 대기',`sum(rabbitmq_detailed_queue_messages_ready{job="rabbitmq",queue="notification.requests.v1",${clean}})`,grid(12,0,6,5),{noValue:'큐 지표 없음',description:'기본 알림 요청 큐에서 아직 소비되지 않은 메시지 수입니다.'}),
  stat(6,'알림 요청 · 소비자',`sum(rabbitmq_detailed_queue_consumers{job="rabbitmq",queue="notification.requests.v1",${clean}})`,grid(18,0,6,5),{noValue:'큐 지표 없음',dangerZero:true,description:'기본 큐의 소비자 수입니다. 0이면 처리 주체 연결을 확인해야 합니다.'}),
  stat(7,'실패 메시지 큐 · 대기',`sum(rabbitmq_detailed_queue_messages_ready{job="rabbitmq",queue="notification.requests.v1.dlq",${clean}})`,grid(0,5,8,5),{noValue:'큐 지표 없음',dangerPositive:true,description:'형식 오류 또는 처리할 수 없는 알림 요청 등이 들어가는 큐입니다. 이메일·Slack 발송 실패 전체를 뜻하지 않습니다.'}),
  stat(8,'알림 요청 · 처리 중',`sum(rabbitmq_detailed_queue_messages_unacked{job="rabbitmq",queue="notification.requests.v1",${clean}})`,grid(8,5,8,5),{noValue:'큐 지표 없음',description:'소비자에게 전달됐지만 아직 확인 응답되지 않은 메시지 수입니다.'}),
  graph(4,'알림 요청과 실패 큐 · 대기 추이',[[`rabbitmq_detailed_queue_messages_ready{job="rabbitmq",queue="notification.requests.v1",${clean}}`,'알림 요청 / 대기'],[`rabbitmq_detailed_queue_messages_unacked{job="rabbitmq",queue="notification.requests.v1",${clean}}`,'알림 요청 / 처리 중'],[`rabbitmq_detailed_queue_messages_ready{job="rabbitmq",queue="notification.requests.v1.dlq",${clean}}`,'실패 메시지 / 대기']],grid(0,10,24,9),'short','지표가 없으면 0으로 가정하지 않습니다. 실패 큐는 모든 이메일·Slack 발송 실패를 의미하지 않습니다.'),
  graph(5,'알림 요청 큐 · 소비자 추이',[[`rabbitmq_detailed_queue_consumers{job="rabbitmq",queue="notification.requests.v1",${clean}}`,'연결된 소비자']],grid(0,19,24,8),'short','알림 요청 큐에 연결된 소비자 수입니다.')
];
const loadPanels = [
  graph(1,'API 요청 수 / 초',[[`sum by (uri) (rate(http_server_requests_seconds_count{job="backend",uri=~"/api/v1/map/housings.*|/api/v1/houses.*",${clean}}[1m]))`,'{{uri}}']],grid(0,0,12,8),'reqps','백엔드에서 관측한 전체 요청이며 k6만 분리한 값은 아닙니다.'),
  graph(2,'API 응답 시간 · p95 추정',[[`histogram_quantile(0.95, sum by (le,uri) (rate(http_server_requests_seconds_bucket{job="backend",uri=~"/api/v1/map/housings.*|/api/v1/houses.*",${clean}}[1m])))`,'{{uri}}']],grid(12,0,12,8),'s','서버 히스토그램 구간 기반 추정치입니다. 정확한 k6 p95는 k6 결과를 확인하세요.'),
  graph(3,'API 서버 오류 / 초',[[`sum by (uri) (rate(http_server_requests_seconds_count{job="backend",status=~"5..",uri=~"/api/v1/map/housings.*|/api/v1/houses.*",${clean}}[1m]))`,'{{uri}}']],grid(0,8,12,8),'reqps','HTTP 5xx만 표시합니다. 지표가 없으면 오류 0이라고 단정하지 않습니다. 전체 실패율은 k6 결과를 확인하세요.'),
  graph(4,'DB 연결 풀 · 사용 중과 대기',[[`sum(hikaricp_connections_active{job="backend",${clean}})`,'사용 중'],[`sum(hikaricp_connections_pending{job="backend",${clean}})`,'대기']],grid(12,8,12,8),'short','백엔드 연결 풀 기준입니다.'),
  graph(5,'서버 CPU 사용률',[[`100 * (1 - avg by (server_name) (rate(node_cpu_seconds_total{mode="idle",server_name!="",${clean}}[1m])))`,'{{server_name}}']],grid(0,16,12,8),'percent','테스트 대상 EC2와 로드 생성기의 CPU를 구별하세요.'),
  graph(6,'서버 메모리 사용률',[[`100 * (1 - node_memory_MemAvailable_bytes{server_name!="",${clean}} / node_memory_MemTotal_bytes{server_name!="",${clean}})`,'{{server_name}}']],grid(12,16,12,8),'percent'),
  graph(7,'컨테이너 메모리 사용량',[[`container_memory_working_set_bytes{job="cadvisor",name!="",${clean}}`,'{{name}}']],grid(0,24,12,8),'bytes','컨테이너 이름 지표가 제공되지 않으면 No data입니다.'),
  graph(8,'PostgreSQL 연결 수',[[`sum by (instance) (pg_stat_database_numbackends{job="postgres",${clean}})`,'현재 연결'],[`pg_settings_max_connections{job="postgres",${clean}}`,'최대 연결']],grid(12,24,12,8),'short','PostgreSQL Exporter가 연결된 경우에만 표시합니다.')
];
const loadDashboard = dashboard('zipick-load-test','ZIPICK · 부하테스트','부하테스트 중 서버 지표를 확인합니다. 요청 수에는 일반 트래픽도 포함됩니다.',loadPanels);
loadDashboard.time = {from:'now-15m',to:'now'};
for (const [name, obj] of [
  ['zipick-summary.json', dashboard('zipick-overview','ZIPICK · 서버·컨테이너','서버와 컨테이너의 자원 사용을 확인합니다.',serverPanels)],
  ['zipick-overview.json', dashboard('zipick-details','ZIPICK · 메인 서비스','프론트, 백엔드 API, DB 상태를 확인합니다.',mainPanels)],
  ['zipick-collector.json', dashboard('zipick-collector','ZIPICK · 공고 수집기','기본·AI 수집기와 Gemini·카카오 결과를 확인합니다.',collectorPanels)],
  ['zipick-notification.json', dashboard('zipick-notification','ZIPICK · 알림 시스템','알림센터 접근과 RabbitMQ 큐를 확인합니다.',notificationPanels)],
  ['zipick-load-test.json', loadDashboard]
]) fs.writeFileSync(path.join(dir,name), JSON.stringify(obj,null,2)+'\n');
console.log('5 dashboards generated; old provisioned dashboards backed up outside the provider directory.');
