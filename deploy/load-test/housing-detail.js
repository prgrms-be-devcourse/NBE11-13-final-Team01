import { options as config, get, housingIds, choose, think } from './common.js';
export const options = config;
export function setup() { return { ids: housingIds() }; }
export default function (data) { get(`/api/v1/map/housings/${choose(data.ids)}`, 'housing_detail'); think(); }
