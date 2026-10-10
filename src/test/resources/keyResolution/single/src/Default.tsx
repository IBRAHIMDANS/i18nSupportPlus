import { useTranslation } from 'react-i18next';
export const A = () => { const { t } = useTranslation(); return <p>{t('defaultOnly')}</p>; };
