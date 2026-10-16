import { m } from "./paraglide/messages.js";
import { getLocale, setLocale } from "./paraglide/runtime.js";
import { orderStatus } from "./orders.js";
import { profileTitle } from "./profile.js";
import { square } from "./notParaglide.js";

const app = document.querySelector<HTMLDivElement>("#app")!;

app.innerHTML = `
  <h1>${m.hello_world()}</h1>
  <p>${m.greeting({ name: "Samuel" })}</p>
  <p>${m.cart_items({ count: 3 })}</p>
  <p>${orderStatus("paid")}</p>
  <h2>${profileTitle()}</h2>
  <p>${m.only_in_english()}</p>
  <p>${m.does_not_exist()}</p>
  <p>${square(4)}</p>
  <button id="toggle">${getLocale()}</button>
`;

document.querySelector("#toggle")!.addEventListener("click", () => {
  setLocale(getLocale() === "en" ? "fr" : "en");
});
