import { getTranslations } from 'next-intl/server';
export async function generateMetadata({ locale }) { const t = await getTranslations({ locale, namespace: 'user' }); return { title: t('name') }; }
