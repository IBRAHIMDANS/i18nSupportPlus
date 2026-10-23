import { useLingui } from "@lingui/react";
import { Trans } from "@lingui/react/macro";
import { navItems } from "./nav";
import { betaNotice } from "./notices";

const user = { name: "Ada" };
const cartCount = 3;

const formatPrice = (amount: number, locale: string) =>
  new Intl.NumberFormat(locale, { style: "currency", currency: "EUR" }).format(amount);

export function App() {
  const { i18n } = useLingui();
  const otherLocale = i18n.locale === "en" ? "fr" : "en";

  return (
    <main>
      {/* Source text: the msgid is the text itself. */}
      <h1>
        <Trans>Hello world!</Trans>
      </h1>
      {/* An expression is numbered: the msgid is "Your total is {0}". */}
      <p>
        <Trans>Your total is {formatPrice(42, i18n.locale)}</Trans>
      </p>
      <p>{i18n._("app.greeting", { name: user.name })}</p>
      <h2>{i18n._("user.profile.title")}</h2>
      <p>{i18n._("cart.items", { count: cartCount })}</p>
      <ul>
        {navItems.map((item) => (
          <li key={item.id}>{i18n._(item)}</li>
        ))}
      </ul>
      <p>{betaNotice()}</p>
      {/* In no catalogue, on purpose. */}
      <p>{i18n._("checkout.coupon")}</p>
      <button type="button" onClick={() => i18n.activate(otherLocale)}>
        {otherLocale}
      </button>
    </main>
  );
}
