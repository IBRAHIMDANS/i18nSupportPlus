import { useI18nContext } from './i18n/i18n-react'

export function App() {
	const { LL, locale, setLocale } = useI18nContext()
	const count = 3

	return (
		<main>
			<h1>{LL.home.title()}</h1>
			<p>{LL.HI({ name: 'Ada' })}</p>
			<p>{LL.home.subtitle()}</p>
			<p>{LL.cart.items(count)}</p>
			<button>{LL.cart.checkout.button()}</button>

			{/* Missing in fr, on purpose */}
			<p>{LL.home.onlyInEnglish()}</p>

			{/* Unknown key, on purpose: the generated types reject it, so `tsc` fails on this line */}
			<p>{LL.home.doesNotExist()}</p>

			<button onClick={() => setLocale(locale === 'en' ? 'fr' : 'en')}>{locale}</button>
		</main>
	)
}
