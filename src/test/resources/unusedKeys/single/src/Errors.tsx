import i18n from './i18n';

// Template literals: a static prefix, a dynamic namespace, no static part at all.
export const error = (code: string) => i18n.t(`common:errors.${code}`);
export const status = (ns: string) => i18n.t(`${ns}:status.ok`);
export const field = (name: string) => i18n.t(`${name}`);
