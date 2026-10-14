import { api, unwrap } from './client';
import type { UserPreference, UserProfile } from '../types';

export const profileApi = {
  get: () => unwrap<UserProfile>(api.get('/api/v1/profiles/me')),
  save: (body: UserProfile) => unwrap<UserProfile>(api.put('/api/v1/profiles/me', body)),
  getPreference: () => unwrap<UserPreference>(api.get('/api/v1/profiles/me/preferences')),
  savePreference: (body: UserPreference) =>
    unwrap<UserPreference>(api.put('/api/v1/profiles/me/preferences', body)),
};
