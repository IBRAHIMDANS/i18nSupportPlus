import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { IntlProvider } from "react-intl";
import { App } from "./App";
import en from "./lang/en.json";
import fr from "./lang/fr.json";

const messages: Record<string, Record<string, string>> = { en, fr };

function Root() {
  const [locale, setLocale] = useState("en");
  return (
    <IntlProvider locale={locale} defaultLocale="en" messages={messages[locale]}>
      <App />
      <button onClick={() => setLocale(locale === "en" ? "fr" : "en")}>{locale}</button>
    </IntlProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
