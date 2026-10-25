import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import TypesafeI18n from './i18n/i18n-react'
import { loadAllLocales } from './i18n/i18n-util.sync'
import { App } from './App'

loadAllLocales()

createRoot(document.getElementById('root')!).render(
	<StrictMode>
		<TypesafeI18n locale="en">
			<App />
		</TypesafeI18n>
	</StrictMode>,
)
