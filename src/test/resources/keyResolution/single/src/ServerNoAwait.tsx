import { getTranslations } from 'next-intl/server';
export function load() { const t = getTranslations('profile'); return t('name'); }
