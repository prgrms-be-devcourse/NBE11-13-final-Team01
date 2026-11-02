import { api, unwrap } from './client';
import type { NotificationEvent, NotificationSetting, NotificationSettingRequest } from '../types';

const BASE = '/api/v1/notifications';

/** 알림 설정·내역 */
export const notificationApi = {
  setting: () => unwrap<NotificationSetting>(api.get(`${BASE}/settings`)),
  save: (req: NotificationSettingRequest) => unwrap<NotificationSetting>(api.put(`${BASE}/settings`, req)),
  history: () => unwrap<NotificationEvent[]>(api.get(BASE)),
};
