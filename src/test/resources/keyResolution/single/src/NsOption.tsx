import { useTranslation } from 'react-i18next';
export const A = () => { const { t } = useTranslation(); return <p>{t('label', { ns: 'other' })}</p>; };
