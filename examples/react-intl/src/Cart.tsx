import { useIntl } from "react-intl";

export function Cart({ count }: { count: number }) {
  const intl = useIntl();
  return (
    <section>
      <p>{intl.formatMessage({ id: "cart.summary" }, { count })}</p>
      <button>{intl.formatMessage({ id: "cart.checkout", defaultMessage: "Checkout" })}</button>
      {/* On purpose: the plural needs `count`, which is not passed. */}
      <p>{intl.formatMessage({ id: "cart.summary" })}</p>
      {/* On purpose: in no translation file. */}
      <p>{intl.formatMessage({ id: "cart.does_not_exist" })}</p>
    </section>
  );
}
