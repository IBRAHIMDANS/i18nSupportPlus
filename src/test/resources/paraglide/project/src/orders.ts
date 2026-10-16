import { m as msg } from "./paraglide/messages.js";

// Passed along without being called: each member still names a message.
const statusMessages = new Map([
  ["pending", msg.order_pending],
  ["paid", msg.order_paid],
  ["failed", msg.order_failed],
]);

export const orderStatus = (status: string) => (statusMessages.get(status) ?? msg.order_unknown)();
