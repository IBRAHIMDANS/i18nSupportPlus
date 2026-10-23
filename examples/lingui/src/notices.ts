import { i18n } from "@lingui/core";

// Outside any component: Lingui's global instance. The key has no French translation, on purpose.
export const betaNotice = () => i18n._("about.beta");
