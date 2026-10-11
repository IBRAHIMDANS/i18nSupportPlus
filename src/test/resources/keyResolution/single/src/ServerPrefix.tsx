import { getTranslations } from 'next-intl/server';
export default async function Page() { const t = await getTranslations('profile'); return <p>{t('name')}</p>; }
