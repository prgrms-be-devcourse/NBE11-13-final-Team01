# 공고 수집 예약 실행

`zipick-collection.timer`는 한국 시간 매일 09:30에 별도 수집기 컨테이너를 순차 실행합니다. 기본 공고 수집 → 인프라 수집 → AI 보완 → 인프라 수집 순서입니다. 인프라 단계는 각각의 수집기 진입점에서 실행합니다. 기존 스크립트를 사용하므로 수집기 지표도 기록합니다.

운영 담당자는 앱 서버에서 먼저 `crontab -l`, 시스템 crontab, `systemctl list-timers --all` 등을 확인해 기존 예약과 중복 등록하지 마세요. 이미 같은 시각에 실행되는 예약이 있다면 기존 예약을 유지하거나 교체하고 둘 다 켜지 마세요. 이 파일을 저장소에 추가하는 것만으로는 운영 예약이 활성화되지 않습니다.

배포된 `docker-compose.prod.yml`과 `monitoring/run-daily-collection.sh`, `monitoring/collect-notices-observed.sh`가 같은 배포 디렉터리에 있어야 합니다. 실제 배포 경로를 확인한 후 앱 서버에서 다음을 실행하세요.

```bash
sudo mkdir -p /etc/zipick
# 실제 경로로 변경하세요. 예: ZIPICK_DEPLOY_DIR=/home/ubuntu/zipick
echo 'ZIPICK_DEPLOY_DIR=/실제/배포/디렉터리' | sudo tee /etc/zipick/collector.env
sudo cp deploy/scheduler/zipick-collection.service deploy/scheduler/zipick-collection.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now zipick-collection.timer
systemctl list-timers --all zipick-collection.timer
```

타이머는 서버 시간대와 무관하게 `Asia/Seoul`로 해석합니다. 서버가 예정 시간에 꺼져 있었다고 해서 기동 직후 밀린 작업을 자동 실행하지 않습니다. 로그는 `journalctl -u zipick-collection.service`로 확인합니다. 기본 수집이 실패해도 기존 대기 작업을 위해 AI 수집을 시도하는 기존 정책은 유지합니다. 개별 인프라 수집 오류는 해당 진입점에서 경고로 기록되므로 프로세스 정상 종료만으로 모든 단계의 성공을 보장하지 않습니다.

웹 서버의 별도 인프라 스케줄러와 `daily-cron` 설정은 제거했습니다. 두 수집기의 `INFRA_COLLECTION_ENABLED`가 `true`여야 위 순서로 인프라를 수집합니다. 기존 운영 설정을 확인하세요.
