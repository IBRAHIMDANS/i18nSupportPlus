// A `msg` that is not Lingui's: this file imports nothing from `@lingui/`, so this is no key.
const msg = (text: string) => console.info(text);

export const logStart = () => msg("app.started");
