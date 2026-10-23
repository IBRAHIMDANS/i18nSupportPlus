import { defineMessage, defineMessages, useIntl } from "react-intl";

const messages = defineMessages({
  paid: {
    id: "orders.status.paid",
    defaultMessage: "Your order is paid",
    description: "Order status, once the payment is received",
  },
  pending: { id: "orders.status.pending", defaultMessage: "Your order is pending" },
  // On purpose: in no translation file.
  lost: { id: "orders.status.does_not_exist", defaultMessage: "Your order is lost" },
});

const refunded = defineMessage({ id: "orders.status.refunded", defaultMessage: "Your order was refunded" });

export function Orders() {
  const intl = useIntl();
  // A descriptor passed by reference: its key is the `id` declared above, not this call.
  return (
    <ul>
      {[messages.paid, messages.pending, messages.lost, refunded].map((message) => (
        <li key={message.id}>{intl.formatMessage(message)}</li>
      ))}
    </ul>
  );
}
