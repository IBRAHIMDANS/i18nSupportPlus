import { Trans, useTranslation } from 'react-i18next';
export const A = () => { const { t } = useTranslation('admin'); return <Trans t={t} i18nKey="dashboard.title" />; };
