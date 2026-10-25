import { useTranslation } from 'react-i18next'

export default function App() {
  const { t } = useTranslation()
  return (
    <main>
      <h1>{t('title')}</h1>
      <p>{t('actions.save')}</p>
      <p>{t('posts.empty')}</p>
    </main>
  )
}
