# ZIPICK 모니터링 구성 및 AWS 적용

이 문서는 모니터링 구성, AWS 서버별 적용 방법과 검증 절차를 설명합니다. 실제 AWS 배포와 운영 검증은 아직 진행하지 않았습니다. 예시 주소와 설정값은 실제 환경에 맞게 변경해 주세요.

이 문서를 AI에게 전달하는 경우에도 저장소의 실제 설정과 대상 서버 상태를 먼저 확인해 주세요. 알 수 없는 IP·계정·배포 경로나 검증 결과를 추측하지 말고 담당자에게 확인해야 합니다. 운영 서비스 재생성, 보안 그룹 변경, DB 스키마 적용 및 실제 수집 실행은 영향과 절차를 담당자와 확인한 뒤 진행해 주세요.

## 1. 구성과 통신 방향

```text
모니터링 EC2                              앱 EC2
Prometheus ── 사설 IP로 읽기 ──────────── Node Exporter / cAdvisor
           ├───────────────────────────── 백엔드 Actuator
           ├───────────────────────────── Blackbox Exporter ──> 프론트·백엔드·알림센터
           ├───────────────────────────── RabbitMQ 지표
           └───────────────────────────── PostgreSQL Exporter (선택)
Grafana <── Prometheus
앱 EC2 HTTPS 프록시 ── 사설 IP ─────────> Grafana
```

Prometheus는 기본 30초 주기로 수집합니다. 지도 API 점검만 5분 주기이며 실제 DB 조회를 일으킵니다. 수집 부담과 응답 크기를 운영에서 측정해 주기를 조정합니다. 서비스 health는 HTTP 응답을 확인할 뿐, DB 값이나 이메일·Slack 발송 정확도를 보장하지 않습니다.

Grafana는 `grafana/provisioning/` 설정에 따라 Prometheus 데이터 소스와 다섯 개의 대시보드를 자동으로 읽습니다. AWS 담당자가 배포할 때 대시보드 JSON과 프로비저닝 폴더를 함께 전달해야 합니다. 아래 항목은 각각 필요한 원본이 연결됐을 때만 표시됩니다.

| Grafana 화면 | 지표가 나오는 곳 | 연결 조건 |
| --- | --- | --- |
| 서버·컨테이너 | 양쪽 서버의 Node Exporter, 앱 서버의 cAdvisor | `targets/node.yml`, `cadvisor.yml`, 각 포트와 실제 Linux의 컨테이너 이름 라벨 확인 |
| 메인 서비스 | 프론트·백엔드 health 점검, Actuator, PostgreSQL Exporter | `services.yml`, `backend.yml`, 선택적 `postgres.yml`과 DB 계정 설정 |
| 공고 수집기 | 수집 스크립트가 만든 `.prom` 파일 → 앱 서버 Node Exporter | 스크립트로 실제 실행하고 `/var/lib/zipick/metrics`를 Exporter가 읽을 수 있어야 함 |
| 알림 시스템 | 알림센터 health, RabbitMQ의 요청 큐와 실패 큐 지표 | `services.yml`, `rabbitmq.yml`, RabbitMQ 플러그인과 실제 큐 확인 |
| 부하테스트 | 백엔드 Actuator, Node Exporter, cAdvisor, PostgreSQL Exporter | API·서버·DB 지표가 연결되어야 하며 k6 결과와 별도로 확인 |

수집기 오류·호출 건수는 스크립트가 실행 로그에서 계산한 마지막 실행 결과입니다. 세부 실패 원인은 로그에서 확인해야 합니다. 카카오 주변 인프라 수집과 신청 가이드의 개별 호출 통계, 실제 메일·Slack 발송 성공 건수는 현재 이 대시보드에서 수집하지 않습니다.

| 앱 EC2 대상 | 포트 | 접근 주체 |
| --- | ---: | --- |
| Node Exporter | 9100 | 모니터링 EC2 보안 그룹 |
| cAdvisor | 8085 | 모니터링 EC2 보안 그룹 |
| 백엔드 Actuator | 19090 | 모니터링 EC2 보안 그룹 |
| Blackbox Exporter | 9115 | 모니터링 EC2 보안 그룹 |
| RabbitMQ 지표 | 15692 | 모니터링 EC2 보안 그룹 |
| PostgreSQL Exporter | 9187 | 사용하는 경우에만 모니터링 EC2 보안 그룹 |
| 모니터링 EC2 Node Exporter | 9100 | 해당 EC2 내부의 Prometheus |
| 모니터링 EC2 Grafana | 3000 | 앱 EC2 프록시의 보안 그룹 또는 별도 승인된 접근 경로 |

위 포트는 인터넷에 공개하지 않습니다. 사설 네트워크와 보안 그룹은 HTTP 트래픽을 암호화하지 않습니다. Prometheus·Alertmanager는 Compose에서 호스트 포트를 공개하지 않습니다. cAdvisor는 호스트 파일시스템과 Docker 소켓 접근을 요구하므로 운영 담당자의 보안 검토가 필요합니다.

## 2. 모니터링 EC2 준비

1. 저장소의 `deploy/monitoring/`을 모니터링 EC2에 배포합니다. Docker와 Compose가 설치돼 있고 앱 EC2 사설 주소로 접근 가능한지 확인합니다.
2. `deploy/monitoring`에서 예시를 복사하고 실제 값을 입력합니다. 예시의 `10.0.0.x`, 도메인과 비밀번호를 그대로 사용하지 않습니다.

```bash
cd /실제/프로젝트/경로/deploy/monitoring
if [ ! -e targets ]; then cp -r targets.example targets; fi
if [ ! -e monitoring.env ]; then cp monitoring.env.example monitoring.env; fi
if [ ! -e alertmanager.private.yml ]; then cp alertmanager.example.yml alertmanager.private.yml; fi
chmod 600 monitoring.env alertmanager.private.yml
```

3. `monitoring.env`의 `MONITOR_PRIVATE_IP`, `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`, `GRAFANA_ROOT_URL`과 파일 경로를 설정합니다. `GRAFANA_ROOT_URL`은 실제 HTTPS 경로와 일치해야 합니다. 파일에 실제 비밀번호를 넣고 Git에 올리지 마세요.
   기존 Grafana 데이터 볼륨을 재사용하는 경우 관리자 계정은 이미 초기화돼 있을 수 있습니다. 환경 변수만 바꿔 기존 관리자 비밀번호가 자동 변경된다고 가정하지 말고 담당자가 계정 상태를 확인해 주세요.
4. `targets/node.yml`의 앱·모니터링 서버 IP를 수정하고, `backend.yml`, `cadvisor.yml`, `rabbitmq.yml`, `services.yml`, `api.yml`의 앱 EC2 사설 IP를 수정합니다. PostgreSQL Exporter를 사용할 때만 `postgres.yml`도 설정합니다. 사용하지 않으면 `postgres.yml`을 빈 목록 `[]`으로 둡니다. Blackbox가 접근하는 `probe_target` URL은 **앱 서버 Docker 네트워크 안**의 실제 서비스 이름과 포트에 맞춥니다.
5. `alertmanager.private.yml`의 기본 수신자는 전송 설정이 없습니다. 수신 채널을 추가하지 않으면 외부 메시지가 가지 않습니다.

```bash
docker compose --env-file monitoring.env -f docker-compose.monitoring.yml config --quiet
docker compose --env-file monitoring.env -f docker-compose.monitoring.yml up -d
docker compose --env-file monitoring.env -f docker-compose.monitoring.yml ps
```

Prometheus/Grafana 데이터는 모니터링 EC2의 Compose 볼륨에 저장합니다. Prometheus 보관 설정은 7일 및 5GB 기준이지만 디스크 전체 상한은 아닙니다. 백업과 볼륨 관리 방법은 담당자가 결정합니다.

모든 대상 파일은 `PROMETHEUS_TARGETS_FILE`이 가리키는 **하나의 디렉터리** 안에 있어야 합니다. `targets/api.yml`에는 지도 API의 Blackbox 대상이 필요합니다. 경로와 실제 파일의 사설 IP를 변경한 뒤 설정 오류가 없는지 확인해 주세요.

## 3. 앱 EC2 준비

기존 운영 DB·RabbitMQ 볼륨과 Compose 프로젝트 이름을 유지해 주세요. `notice-init.sql` 등 로컬 초기화 SQL에는 `DROP TABLE`이 포함되므로 운영 DB에서 실행하지 마세요. 필요한 수집기 테이블은 DB 담당자와 비파괴 마이그레이션을 확인해 주세요.

1. 기존 `deploy/.env`를 유지하며 비공개 파일에 `APP_PRIVATE_IP`를 추가합니다. PostgreSQL Exporter를 사용할 경우 `POSTGRES_EXPORTER_DSN`도 설정합니다. URL의 특수문자 인코딩과 계정 권한을 DB 담당자와 확인하고, 관리자 계정 대신 최소 권한 계정을 검토합니다. DB가 별도 서버라면 기본 overlay의 `depends_on: db`를 비롯한 경로·의존성을 실제 구조에 맞춰 담당자가 조정해야 합니다.
2. 수집기 지표를 보관할 `/var/lib/zipick/metrics`를 만들고 실행 계정이 쓸 수 있게 설정합니다. 기존 파일이 있다면 덮어쓰기 전에 확인합니다.

예를 들어 수집 스크립트를 실행하는 계정으로 아래 명령을 실행할 수 있습니다. 디렉터리가 이미 존재한다면 소유권을 임의로 바꾸지 말고 해당 계정의 쓰기 권한을 확인해 주세요.

```bash
if [ ! -d /var/lib/zipick/metrics ]; then
  sudo install -d -m 0755 -o "$(id -un)" -g "$(id -gn)" /var/lib/zipick/metrics
fi
test -w /var/lib/zipick/metrics && echo '수집기 지표 쓰기 가능'
```
3. **설정만 먼저 검증**합니다. `config --quiet`는 비밀값을 출력하지 않습니다.

```bash
cd /실제/프로젝트/경로/deploy
docker compose --env-file .env -f docker-compose.prod.yml -f monitoring/docker-compose.app-exporters.yml config --quiet
```

4. 담당자 협의 후 내보내기 전용 서비스를 먼저 실행할 수 있습니다. 이 명령은 백엔드나 RabbitMQ의 설정 변경을 적용하지 않습니다.

```bash
docker compose --env-file .env -f docker-compose.prod.yml -f monitoring/docker-compose.app-exporters.yml up -d --no-deps node-exporter cadvisor blackbox-exporter
```

PostgreSQL Exporter 사용 시 `--profile postgres`를 Compose 옵션으로 추가하고 `postgres-exporter`를 별도로 실행합니다. DB 위치와 네트워크 연결을 먼저 확인하세요.

5. 백엔드 `19090` 포트 노출과 RabbitMQ 플러그인은 위 전용 서비스 실행만으로 적용되지 않습니다. 담당자가 overlay의 `backend`·`rabbitmq` 변경을 검토한 뒤 유지보수 시간에 각 서비스 적용·재생성을 결정해야 합니다. RabbitMQ 큐·볼륨 보존 및 알림 서비스의 재연결을 확인합니다. CI/CD의 이후 배포가 overlay를 누락해 설정을 되돌리지 않는지도 확인합니다.
6. 앱 서버의 기존 Caddy 설정에는 Grafana 프록시가 아직 없습니다. 실제 HTTPS 호스트와 `/grafana/` 프록시를 배포 담당자가 추가·검증해야 합니다. 앱 서버가 중단되면 이 경로도 중단되므로 SSM 등 별도 접근 경로가 필요합니다. `/actuator/**`는 현재 인증 없이 접근 가능하므로 반드시 사설 IP와 보안 그룹으로 제한합니다.

백엔드와 RabbitMQ 적용이 승인된 경우의 **개별 적용 명령**은 다음과 같습니다. 각각 해당 서비스가 재생성될 수 있으므로 실행 전에 중단·복구 계획과 Compose 프로젝트 `zipick`의 기존 볼륨을 확인해 주세요. 이 명령을 실행해야 Actuator 포트와 RabbitMQ 지표 플러그인이 실제 적용됩니다.

```bash
# 앱 EC2의 deploy 디렉터리에서 실행합니다.
docker compose --env-file .env -f docker-compose.prod.yml -f monitoring/docker-compose.app-exporters.yml up -d --no-deps backend
docker compose --env-file .env -f docker-compose.prod.yml -f monitoring/docker-compose.app-exporters.yml up -d --no-deps rabbitmq
```

PostgreSQL Exporter 사용 시 예시 명령은 다음과 같습니다. 기존 DB 서비스가 이 Compose 프로젝트 안에 있다는 전제이며, DB를 별도로 옮긴 경우 연결 설정과 의존성부터 수정해야 합니다.

```bash
docker compose --profile postgres --env-file .env -f docker-compose.prod.yml -f monitoring/docker-compose.app-exporters.yml up -d --no-deps postgres-exporter
```

Grafana를 앱 서버 Caddy의 `/grafana/`로 연결하는 경우, 기존 정적 파일 처리보다 앞에 아래 핸들러를 추가하는 방식을 검토해 주세요. 주소는 모니터링 EC2의 실제 사설 IP로 바꾸고, Caddy의 기존 배포 설정·HTTPS 인증서와 함께 확인해야 합니다. 이 문서의 설정만으로 프록시가 자동 적용되지는 않습니다.

```caddyfile
handle /grafana* {
    reverse_proxy 모니터링_EC2_사설_IP:3000
}
```

## 4. 공고 수집기 운영 연결

실행할 백엔드 이미지에 두 수집기 진입점이 포함되어 있고 필요한 DB 스키마가 적용되어 있는지 확인해 주세요. 수집기 실행은 외부 API를 호출하고 DB를 변경할 수 있습니다.
이 저장소의 변경사항이 아직 이미지 빌드·배포에 반영되지 않았다면 기존 운영 이미지에는 두 진입점이나 지표 기록 코드가 없을 수 있습니다. 이미지 버전과 포함 여부를 실제 배포 담당자가 확인해야 합니다.

두 수집기는 웹 서버를 열지 않는 `WebApplicationType.NONE`으로 실행됩니다. 현재 주변 인프라 스케줄러는 웹 애플리케이션에만 등록되지만, 알림 스케줄러 설정은 별도로 존재합니다. **수집기 프로세스에서 다른 스케줄러가 실행되지 않는다는 운영 검증은 아직 완료되지 않았습니다.** 중복 알림·배치가 없는지 비운영 환경에서 확인하고 담당자와 실행 설정을 정해 주세요.

현재 예약 실행은 만들어져 있지 않습니다. `run-daily-collection.sh`는 스케줄러 자체가 아니라 스케줄러에서 호출할 수 있는 스크립트입니다. 운영 담당자가 실행 계정·스케줄러·시각·시간대를 정한 후 연결합니다.

스케줄러가 호출할 명령은 아래와 같습니다. 이 명령은 실제 공고 수집과 AI 호출을 실행합니다. 배포 경로를 바꾸고, DB 구조·키·Gemini 할당량·로그 저장 및 실행 시점을 확인한 다음 사용해 주세요.

```bash
bash /실제/프로젝트/경로/deploy/monitoring/run-daily-collection.sh
```

- `run-daily-collection.sh`: 기본 수집 후 AI 수집을 순차 실행합니다. 기본 수집이 실패해도 AI 정규화 대기 작업을 시도합니다. 단, 관측 스크립트로 다른 수집이 이미 실행 중이면 종료 코드 75로 중단합니다.
- `collect-notices-observed.sh basic` / `ai`: 각각 한 번 실행합니다. Docker Compose, 이미지, `/var/lib/zipick/metrics` 쓰기 권한과 실제 배포 경로가 필요합니다. 스크립트는 `deploy/docker-compose.prod.yml`을 사용하고 해당 디렉터리의 `.env`를 참조하므로 운영 배포 구조가 다르면 담당자가 먼저 조정합니다.
- `flock`은 **같은 지표 디렉터리를 사용하는 이 스크립트 간** 중복만 막습니다. 다른 직접 실행이나 다른 디렉터리의 작업까지 막지는 않습니다.
- 지표는 Node Exporter의 textfile collector가 읽습니다. 프로세스 비정상 강제 종료 시 실행 중 상태가 남을 수 있습니다. 종료 성공도 개별 공고 처리 성공을 보장하지 않습니다. 실패 공고의 상세 원인은 수집기 로그에서 확인합니다.
- 스크립트의 임시 로그는 실행 종료 시 삭제되고 일회성 Docker 컨테이너도 제거됩니다. 실패 원인을 나중에 확인할 수 있도록 스케줄러가 표준 출력과 오류 출력을 영구 로그로 남기도록 별도로 설정해 주세요. 현재 Loki나 전송형 로그 파이프라인은 구성되어 있지 않습니다.

## 5. 배포 후 검증 체크리스트

아래 결과는 담당자가 **실제 AWS에서** 기록합니다. 테스트 중 실제 사용자 발송이나 운영 데이터 변경이 생기지 않도록 대상과 방법을 먼저 협의합니다.

1. 모니터링 EC2에서 각 대상의 사설 포트 연결과 Prometheus Targets의 `up`을 확인합니다. Blackbox는 `up` 외에 `probe_success`도 확인합니다. 수집기 지표 파일이 없거나 큐가 없을 때 `No data`를 0이나 정상으로 간주하지 않습니다.
2. Grafana에서 `서버·컨테이너`, `메인 서비스`, `공고 수집기`, `알림 시스템`, `부하테스트`를 각각 확인합니다. 앱 EC2와 모니터링 EC2의 CPU·메모리, 컨테이너별 지표, EC2 루트 디스크가 올바른 대상인지 확인합니다. 로컬 Docker VM 값은 EC2의 값이 아닙니다.
3. 대표 API 호출 시 요청 수·응답 시간·5xx 지표가 반영되는지 확인합니다. 5xx 지표 자체가 아직 없으면 `오류 지표 없음`으로 표시될 수 있습니다. API별 평균은 부하테스트 결과 전체를 대신하지 않습니다.
4. PostgreSQL Exporter 사용 시 `up`, `pg_up`, `pg_exporter_last_scrape_error`, 연결 수를 함께 확인합니다. `up=1`은 Exporter 접근 성공일 뿐 DB의 모든 지표를 읽었다는 뜻이 아닙니다.
5. 알림센터 health와 `notification.requests.v1`의 대기·소비자, `notification.requests.v1.dlq`의 대기 지표를 확인합니다. 실패 큐는 처리할 수 없는 **알림 요청**에 관한 것이며 이메일·Slack 발송 실패 전체를 뜻하지 않습니다. 큐 생성 여부를 알림 담당자와 확인하세요.
6. 수집 전·중·후 앱 서버 전체 메모리와 컨테이너별 메모리·CPU, API 응답 시간과 DB 연결을 기록합니다. 30초 수집은 짧은 피크를 놓칠 수 있으며, 현재 수집기별 **정확한 최고 메모리 기록은 구현 완료가 아닙니다.** 별도 계측이 필요합니다.
7. Grafana의 Alerting 또는 Prometheus 규칙에서 경고 발생·복구를 확인합니다. 기본 설정에는 알림 수신자가 없으므로 실제 Slack·메일 수신은 별도 구성 전까지 기대하지 않습니다.
8. 운영 담당자와 별도로 부하테스트의 대상 API·동시 요청·시간·중단 기준을 정합니다. 테스트 전·중·후 응답 시간, 오류율, 앱/DB 자원 사용을 기록합니다. 로컬 연결 확인 결과는 있지만 **AWS 부하테스트 결과는 아직 없습니다.**

조회 부하테스트 실행 방법과 결과 기록 기준은 [부하테스트 README](../load-test/README.md)를 확인해 주세요. Grafana의 부하테스트 화면은 기존 서버 지표를 모아서 보여주며 k6 요청만 구별하지 않습니다.

### Prometheus와 Grafana에서 확인하는 방법

Prometheus의 `9090`은 외부에 공개되지 않으므로 모니터링 EC2에서 다음 명령으로 읽을 수 있습니다. 디렉터리는 위 2번의 모니터링 EC2 `deploy/monitoring`입니다.

```bash
docker compose --env-file monitoring.env -f docker-compose.monitoring.yml exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
docker compose --env-file monitoring.env -f docker-compose.monitoring.yml exec -T prometheus promtool query instant http://localhost:9090 'up'
docker compose --env-file monitoring.env -f docker-compose.monitoring.yml exec -T prometheus promtool query instant http://localhost:9090 'probe_success'
```

- `up=1`은 **Prometheus가 지표 주소에 접근했다는 뜻**입니다. Blackbox Exporter가 정상이어도 대상 서비스는 실패할 수 있으므로 `probe_success`를 함께 확인해 주세요. 결과 자체가 없으면 대상 파일·IP·포트·서비스 실행 상태를 확인합니다.
- `node`, `cadvisor`, `backend`, `rabbitmq`, `service-probe`, `map-api-probe`의 실제 대상이 보이는지 확인합니다. PostgreSQL Exporter를 사용하는 경우 `postgres`도 확인합니다. `node`의 `server_name`은 대시보드 구분에 사용하므로 앱·모니터링 서버 라벨을 유지해 주세요.
- 설정 파일 `prometheus.yml`이나 `alerts.yml`을 수정했다면 Prometheus 설정 재적용이 필요합니다. 대상 파일은 file-based discovery로 주기적으로 갱신되지만, 변경 직후 결과가 없으면 대상 파일 경로와 Prometheus 로그를 확인해 주세요.
- 프록시와 HTTPS가 준비되면 Grafana의 `ZIPICK` 폴더에서 네 대시보드를 확인해 주세요. Grafana 로그인 설정과 `/grafana/` 경로는 2·3번 설정과 일치해야 합니다. 지도 API 점검은 5분 간격이므로 첫 결과까지 기다려야 할 수 있습니다.

**값이 표시되지 않을 때 확인할 곳**

| 증상 | 먼저 확인할 항목 |
| --- | --- |
| 대상이 없음 | `PROMETHEUS_TARGETS_FILE`, 해당 `targets/*.yml`, 파일의 실제 IP와 YAML 형식 |
| `up=0` | 대상 프로세스·포트, 두 EC2의 사설 통신과 보안 그룹, 서버별 바인딩 IP |
| `up=1`, `probe_success=0` | Blackbox가 접근하는 `probe_target`, Docker 네트워크 서비스명·포트·HTTP 상태 |
| 수집기만 `No data` | 관측 스크립트 실행 여부, `/var/lib/zipick/metrics/*.prom` 생성과 권한, Node Exporter의 textfile 설정 |
| 알림 큐만 `No data` | RabbitMQ 플러그인, 알림센터의 큐 생성 여부, `notification.requests.v1`·`notification.requests.v1.dlq` 지표 |
| 컨테이너·디스크만 `No data` | EC2에서 cAdvisor의 개별 컨테이너 `name` 라벨과 Node Exporter의 루트 마운트 `/` 지표 |
| DB `No data` | 선택적 Exporter 실행, DSN·권한·DB 접속, `postgres.yml` 대상 설정 |

## 6. 로컬 검증과 남은 범위

- 로컬 Prometheus/Grafana와 대시보드 쿼리, 기본 수집기 직접 실행 기록을 확인했습니다. 별도 테스트 컨테이너에서 알림센터 health, 큐 소비자 1개, 실패 큐 1건이 Grafana에 표시되는 것도 확인했습니다.
- 실제 로컬 백엔드와 PostgreSQL을 대상으로 낮은 부하의 [연결 확인 시험](../load-test/results/local-connection-check-2026-10-28.md)을 진행했습니다. AWS 성능 결과는 아닙니다.
- 로컬 Docker Desktop의 cAdvisor·디스크는 EC2 Linux와 다르며 일부 패널이 `No data`였습니다. AWS에서 실제 수집 대상과 라벨을 확인해야 합니다.
- AWS 보안 그룹·사설 연결, Grafana HTTPS 프록시, RabbitMQ/백엔드 운영 적용, 운영 수집 스케줄, EC2 메모리 피크 및 부하테스트는 **미검증**입니다.

## 로컬에서 수집기 지표 확인

Windows에서 IntelliJ로 `NoticeCollectionApplication` 또는 `NoticeAiApplication`을 직접 실행하면 `deploy/monitoring/collector-metrics/`에 지표가 기록됩니다. 로컬 모니터링의 Node Exporter가 이 디렉터리를 읽습니다. 아래 명령은 프로젝트 루트에서 실행해 주세요.

```bash
docker compose -f deploy/monitoring/docker-compose.local-collector.yml up -d
```

로컬 Prometheus의 `targets.local/node.yml`에는 `host.docker.internal:9116`을 추가해 주세요. 이 방식은 Windows 직접 실행용이며 Linux 운영에서는 위의 관측 스크립트를 사용합니다. 두 방식은 중복 실행 방지 잠금을 공유하지 않습니다.
