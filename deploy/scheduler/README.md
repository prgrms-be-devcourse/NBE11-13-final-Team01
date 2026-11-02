# 공고 수집 예약 실행

`zipick-collection.timer`는 한국 시간 매일 09:30에 별도 수집기 컨테이너를 순차 실행합니다. 기본 공고 수집 → 인프라 수집 → AI 보완 → 인프라 수집 순서입니다. 인프라 단계는 각각의 수집기 진입점에서 실행합니다. 기존 스크립트를 사용하므로 수집기 지표도 기록합니다.

운영 담당자는 앱 서버에서 먼저 `crontab -l`, 시스템 crontab, `systemctl list-timers --all` 등을 확인해 기존 예약과 중복 등록하지 마세요. 이미 같은 시각에 실행되는 예약이 있다면 기존 예약을 유지하거나 교체하고 둘 다 켜지 마세요. 이 파일을 저장소에 추가하는 것만으로는 운영 예약이 활성화되지 않습니다.

배포된 `docker-compose.prod.yml`과 `monitoring/run-daily-collection.sh`, `monitoring/collect-notices-observed.sh`가 같은 배포 디렉터리에 있어야 합니다. GitHub Actions 배포(`remote-deploy.sh`)는 두 스크립트를 compose 파일과 같은 커밋에서 받아 갱신합니다. 처음 설치할 때나 수동 배포(`deploy.sh`)를 쓸 때는 직접 복사하세요.

서비스는 `ubuntu` 계정으로 실행합니다. 지표 디렉터리와 잠금 파일(`/var/lib/zipick/metrics/.collector.lock`)을 `ubuntu` 소유로 유지해야 `ubuntu`로 실행하는 다른 수집과 충돌하지 않습니다. `ubuntu`는 `docker` 그룹에 속해 있어야 합니다.

실제 배포 경로를 확인한 후 앱 서버에서 다음 순서로 실행하세요.

```bash
# 1. ubuntu 가 docker 를 쓸 수 있는지 확인
id -nG ubuntu | grep -qw docker && echo 'docker 그룹 확인'

# 2. 기존 cron 예약 제거 (crontab -e 로 09:30 수집 줄을 지운 뒤 확인)
crontab -l | grep -n 'run-daily-collection\|collect-notices' || echo '남은 수집 cron 없음'

# 3. 지표 디렉터리 소유자 정리 (root 로 실행된 적이 있으면 root 소유 파일이 남아 있을 수 있음)
sudo install -d -m 0755 -o ubuntu -g ubuntu /var/lib/zipick/metrics
sudo chown -R ubuntu:ubuntu /var/lib/zipick

# 4. 배포 경로 설정. 실제 경로로 변경하세요. 예: ZIPICK_DEPLOY_DIR=/home/ubuntu/zipick
sudo mkdir -p /etc/zipick
echo 'ZIPICK_DEPLOY_DIR=/실제/배포/디렉터리' | sudo tee /etc/zipick/collector.env

# 5. 타이머 등록
sudo cp deploy/scheduler/zipick-collection.service deploy/scheduler/zipick-collection.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now zipick-collection.timer
systemctl list-timers --all zipick-collection.timer
```

cron 과 타이머를 둘 다 켜 두면 같은 시각에 두 번 실행되고, 한쪽은 잠금에 걸려 종료 코드 75로 끝납니다. 서비스는 75를 정상 종료로 처리하므로 실패로 표시되지 않습니다. 바로 시험하려면 `sudo systemctl start zipick-collection.service`로 실행하고 로그를 확인하세요.

수동으로 실행할 때는 다음을 사용합니다.

```bash
# 전체 수집(기본 → AI)을 지금 한 번 실행. 서비스 설정대로 ubuntu 로 실행되고 로그도 journal 에 남는다.
sudo systemctl start zipick-collection.service
journalctl -u zipick-collection.service -f

# 기본 또는 AI 수집만 실행
bash /home/ubuntu/zipick/monitoring/collect-notices-observed.sh basic   # 또는 ai
```

`collect-notices-observed.sh`를 root로 실행하면(`sudo bash ...`) 지표 디렉터리 소유자 계정으로 바꿔 다시 실행합니다. 디렉터리가 아직 없으면 `ZIPICK_COLLECTOR_USER`(기본 `ubuntu`)로 실행하고, 디렉터리가 root 소유면 실행하지 않고 `chown` 안내를 출력합니다.

타이머는 서버 시간대와 무관하게 `Asia/Seoul`로 해석합니다. 서버가 예정 시간에 꺼져 있었다고 해서 기동 직후 밀린 작업을 자동 실행하지 않습니다. 로그는 `journalctl -u zipick-collection.service`로 확인합니다. 한 번의 실행이 6시간을 넘기면 중단되며, 이 경우 `failed`로 표시됩니다. 기본 수집이 실패해도 기존 대기 작업을 위해 AI 수집을 시도하는 기존 정책은 유지합니다. 개별 인프라 수집 오류는 해당 진입점에서 경고로 기록되므로 프로세스 정상 종료만으로 모든 단계의 성공을 보장하지 않습니다.

웹 서버의 별도 인프라 스케줄러와 `daily-cron` 설정은 제거했습니다. 두 수집기의 `INFRA_COLLECTION_ENABLED`가 `true`여야 위 순서로 인프라를 수집합니다. 기존 운영 설정을 확인하세요.
