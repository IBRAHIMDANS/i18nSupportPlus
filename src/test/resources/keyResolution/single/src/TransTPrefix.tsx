import { Trans, useTranslation } from 'react-i18next';
export const A = () => { const { t } = useTranslation('common', { keyPrefix: 'profile' }); return <Trans t={t} i18nKey="name" />; };
