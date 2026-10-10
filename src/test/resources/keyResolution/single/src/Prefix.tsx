import { useTranslation } from 'react-i18next';
export const A = () => { const { t } = useTranslation('common', { keyPrefix: 'profile' }); return <p>{t('name')}</p>; };
