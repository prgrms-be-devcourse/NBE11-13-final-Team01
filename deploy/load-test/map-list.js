import { options as config, get, think } from './common.js';
export const options = config;
export default function () { get('/api/v1/map/housings', 'map_list'); think(); }
