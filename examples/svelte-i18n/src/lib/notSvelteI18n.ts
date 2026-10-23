// A `_` that is not svelte-i18n's: this file does not import it, so its argument is no key.
const _ = (text: string) => text.toUpperCase();

export const shouted = _("not.a.key");
