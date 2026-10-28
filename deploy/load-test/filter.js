import { options as config, get, filterPath, think } from './common.js';
export const options = config;
export default function () { get(filterPath, 'housing_filter'); think(); }
