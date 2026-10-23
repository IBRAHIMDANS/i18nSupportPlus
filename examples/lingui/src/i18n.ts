import { i18n } from "@lingui/core";
import { messages as en } from "./locales/en/messages.po";
import { messages as fr } from "./locales/fr/messages.po";

i18n.load({ en, fr });
i18n.activate("en");

export { i18n };
