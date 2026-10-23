import { useIntl } from "react-intl";

export function Profile() {
  const { formatMessage } = useIntl();
  return <h2>{formatMessage({ id: "profile.header.title" })}</h2>;
}
