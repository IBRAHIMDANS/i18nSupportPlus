import type { Metadata } from 'next';
import { getTranslations, setRequestLocale } from 'next-intl/server';
import Cart from './Cart';
import PrivacySettings from './PrivacySettings';

type Props = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: Props): Promise<Metadata> {
  const { locale } = await params;
  // The namespace option, outside the render where no request locale is set.
  const t = await getTranslations({ locale, namespace: 'Metadata' });

  return {
    title: t('title'),
    description: t('description')
  };
}

export default async function HomePage({ params }: Props) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('HomePage');

  return (
    <main>
      <h1>{t('title')}</h1>
      <p>{t('greeting', { name: 'Ada' })}</p>
      <p>{t('visits', { count: 3 })}</p>
      <nav>
        <a href="https://next-intl.dev">{t('links.docs')}</a>
        <a href="https://github.com/amannn/next-intl">{t('links.source')}</a>
      </nav>
      {/* Missing in fr.json on purpose. */}
      <p>{t('onlyInEnglish')}</p>
      {/* In no locale on purpose. */}
      <p>{t('doesNotExist')}</p>
      <Cart />
      <PrivacySettings />
    </main>
  );
}
