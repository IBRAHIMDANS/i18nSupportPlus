/// <reference types="vite/client" />

// What `@lingui/vite-plugin` turns a catalogue into when it is imported.
declare module "*.po" {
  import type { Messages } from "@lingui/core";
  export const messages: Messages;
}
