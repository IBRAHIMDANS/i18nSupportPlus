import { mount } from "svelte";
import "./lib/i18n";
import App from "./App.svelte";

export default mount(App, { target: document.querySelector("#app")! });
