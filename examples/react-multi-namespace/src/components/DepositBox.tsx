import React from 'react';
import { Trans, useTranslation } from 'react-i18next';
import i18n from '../i18n';

type BoxStatus = 'pending' | 'accepted' | 'declined';

interface DepositBoxProps {
  trusteeCount: number;
  status: BoxStatus;
}

/**
 * The call shapes a real i18next codebase mixes, gathered in one component.
 *
 * The namespace is `deposit-box`: a name carrying a dash, which is also the plugin's default
 * plural separator — `deposit-box:retention.unit.day_one` must stay one namespace and one key.
 */
export default function DepositBox({ trusteeCount, status }: DepositBoxProps) {
  // No namespace given: every key below names its own, which is how most call sites look
  // once a project has more than a handful of namespaces.
  const { t } = useTranslation();

  // Statuses are picked at runtime, so the key is only known as a template literal. Nothing
  // can resolve it statically — the three `status.*` keys are used, and no scan can see it.
  const statusLabel = t(`deposit-box:status.${status}`);

  return (
    <div>
      <h2>{t('deposit-box:title')}</h2>

      <section style={{ marginBottom: '1.5rem' }}>
        <h3>{t('deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description')}</h3>
        {/*
          Six levels deep, and pluralized: the file holds `description_one` /
          `description_other`, while the suffix is never written here — i18next appends it
          from { count }.
        */}
        <p>
          {t('deposit-box:myBoxes.myTrustees.modal.confirmPassword.addTrustee.description', {
            count: trusteeCount,
          })}
        </p>
        <p>{t('deposit-box:myBoxes.myTrustees.modal.confirmPassword.removeTrustee.description')}</p>
        <p>{t('deposit-box:myBoxes.myTrustees.toasts.trusteeAdded', { count: trusteeCount })}</p>
      </section>

      <section style={{ marginBottom: '1.5rem' }}>
        <h3>Status</h3>
        <p>{statusLabel}</p>
        {/* A key passed as an option of another key. */}
        <p>
          {t('deposit-box:activity.entry.boxShared', {
            name: t('deposit-box:title'),
          })}
        </p>
      </section>

      <section style={{ marginBottom: '1.5rem' }}>
        <h3>Activity</h3>
        {/* The value carries markup, so the key is rendered through <Trans>. */}
        <p>
          <Trans
            i18nKey="deposit-box:activity.entry.fileAdded"
            values={{ actor: 'Alice', item: 'report.pdf' }}
            components={{ b: <b /> }}
          />
        </p>
        {/* A multiline value: the table and the hints must collapse it to one line. */}
        <p style={{ whiteSpace: 'pre-line' }}>{t('deposit-box:activity.tooltipInfo')}</p>
      </section>

      <section>
        <h3>Retention</h3>
        {/* Called on the i18next instance itself, outside any hook. */}
        <p>{i18n.t('deposit-box:retention.unit.day', { count: 30 })}</p>
      </section>
    </div>
  );
}
