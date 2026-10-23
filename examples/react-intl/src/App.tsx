import { FormattedMessage } from "react-intl";
import { Cart } from "./Cart";
import { NotIntl } from "./NotIntl";
import { Orders } from "./Orders";
import { Profile } from "./Profile";

export function App() {
  return (
    <main>
      <h1>
        <FormattedMessage id="app.title" />
      </h1>
      <p>
        <FormattedMessage id="home.greeting" values={{ name: "Samuel" }} />
      </p>
      {/* `defaultMessage` is source text, never a key. */}
      <p>
        <FormattedMessage id="home.welcome" defaultMessage="Welcome back!" />
      </p>
      {/* On purpose: in en.json only. */}
      <p>
        <FormattedMessage id="home.only_in_english" />
      </p>
      {/* On purpose: in no translation file. */}
      <p>
        <FormattedMessage id="home.does_not_exist" />
      </p>
      <Cart count={3} />
      <Profile />
      <Orders />
      <NotIntl />
    </main>
  );
}
