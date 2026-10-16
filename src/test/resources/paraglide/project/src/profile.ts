// Paraglide 1's import form, still supported.
import * as m from "./paraglide/messages.js";

// A nested key is read through an index: user → profile → title.
export const profileTitle = () => m["user.profile.title"]();

// A computed member names no key: left alone.
export const byKey = (key: string) => (m as unknown as Record<string, () => string>)[key]();
