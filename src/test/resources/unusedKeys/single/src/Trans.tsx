import { Trans, useTranslation } from 'react-i18next';

export const OtherTrans = () => <Trans i18nKey="transLabel" ns="other" />;

export const Card = () => {
  const { t } = useTranslation('common', { keyPrefix: 'card' });
  return <Trans t={t} i18nKey="title" />;
};
