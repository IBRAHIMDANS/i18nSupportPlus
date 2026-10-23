// Shapes close to react-intl's that name no message: nothing here is a key.

// A descriptor-shaped object outside `defineMessages`.
const placeholder = { email: { id: "not.a.message", defaultMessage: "you@example.com" } };

export function NotIntl() {
  // An `id` attribute on another tag than <FormattedMessage>.
  return <input id="not.a.message" placeholder={placeholder.email.defaultMessage} />;
}
