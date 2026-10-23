'use client';

import { useState } from 'react';
import { useTranslations } from 'next-intl';

export default function Cart() {
  const t = useTranslations('Cart');
  const [count, setCount] = useState(0);

  return (
    <section>
      <h2>{t('title')}</h2>
      <p>{t('items', { count })}</p>
      <p>{t('total', { amount: count * 12 })}</p>
      {/* `amount` not passed on purpose. */}
      <p>{t('total')}</p>
      <button onClick={() => setCount(count + 1)}>{t('checkout.button.label')}</button>
      {/* Missing in fr.json on purpose. */}
      <button>{t('saveForLater')}</button>
      {/* In no locale on purpose. */}
      <p>{t('discount')}</p>
    </section>
  );
}
