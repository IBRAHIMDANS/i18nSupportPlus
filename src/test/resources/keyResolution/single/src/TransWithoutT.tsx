import { Trans, useTranslation } from 'react-i18next';
export const A = () => { const { t } = useTranslation('admin'); return <p>{t('dashboard.title')}<Trans i18nKey="defaultOnly" /></p>; };
