import { useTranslation } from 'react-i18next';
export const A = ({ k }: { k: string }) => { const { t } = useTranslation(); return <p>{t(`common:${k}`)}</p>; };
