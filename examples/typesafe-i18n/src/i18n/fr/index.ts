import type { Translation } from '../i18n-types'

const fr = {
	HI: 'Bonjour {name} !',
	home: {
		title: 'Accueil',
		subtitle: 'Bon retour',
	},
	cart: {
		items: '{count} article{{s}}',
		checkout: { button: 'Commander' },
	},
} satisfies Translation

export default fr
