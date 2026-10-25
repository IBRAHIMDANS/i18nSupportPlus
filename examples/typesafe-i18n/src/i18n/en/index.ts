import type { BaseTranslation } from '../i18n-types'

const en = {
	HI: 'Hello {name:string}!',
	home: {
		title: 'Home',
		subtitle: 'Welcome back',
		onlyInEnglish: 'Shown in English only',
	},
	cart: {
		items: '{count:number} item{{s}}',
		checkout: { button: 'Check out' },
	},
} satisfies BaseTranslation

export default en
