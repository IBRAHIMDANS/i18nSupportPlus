import { useTranslation } from 'react-i18next';
export const A = ({ n }: { n: number }) => { const { t } = useTranslation(); return <p>{t('common:item', { count: n })}</p>; };
