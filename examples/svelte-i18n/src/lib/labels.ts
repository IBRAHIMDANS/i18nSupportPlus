import { _, unwrapFunctionStore } from "svelte-i18n";

// Outside a component there is no `$` store syntax: svelte-i18n documents unwrapping the store.
const $_ = unwrapFunctionStore(_);

export const settingsLabel = () => $_("nav.settings");

// `greeting` reads `{name}`, and nothing passes it. Never rendered: the formatter would throw.
export const greetingWithoutName = () => $_("greeting");
