# Paraglide JS

Manual testing target for the **i18n Support Plus** plugin on a [Paraglide JS](https://paraglidejs.com) (inlang)
project: Vite and TypeScript, two locales, messages in the inlang message format.

Paraglide compiles each message into a function: `m.hello_world()` reads the message `hello_world`, and no string
names the key. The plugin reads the key from the member of the message object once the file imports it from
`…/paraglide/messages`.

## Run it

```bash
npm install
npm run compile   # writes src/paraglide/ (gitignored), the code Paraglide generates
npm run dev
```

Without `npm run compile`, the plugin still reads the keys (it only needs the import line and `messages/`), but the
IDE's own JavaScript support reports `./paraglide/messages.js` as missing. Compile once before checking navigation,
hover or completion, which have to work next to that generated code.

## What the messages cover

| Case | Where |
|---|---|
| `$schema` at the root of each file: no key | `messages/*.json` |
| Plain message, message with a parameter | `hello_world`, `greeting` |
| Nested key, read as `m["user.profile.title"]` | `user.profile.title` |
| Message with variants (plural), an array rather than a string | `cart_items` |
| Missing in a locale, on purpose | `only_in_english` (not in `fr.json`) |
| Unknown key at the call site, on purpose | `m.does_not_exist()` in `src/main.ts` |

## What the call sites cover

| Case | Where |
|---|---|
| `import { m } from "./paraglide/messages.js"` | `src/main.ts` |
| Aliased import, `{ m as msg }` | `src/orders.ts` |
| Messages passed along without being called (`new Map([["paid", msg.order_paid]])`) | `src/orders.ts` |
| Paraglide 1's `import * as m` | `src/profile.ts` |
| Computed member, `m[key]`: no key | `src/profile.ts` |
| An `m` imported from another module: no key | `src/notParaglide.ts` |

## What to check in the sandbox IDE

Open this folder with `./gradlew runIde`, accept the setup wizard (it detects *Paraglide JS (inlang)*), then:

- `m.does_not_exist` is underlined as *Unresolved key*, `m.only_in_english` as *Partially translated*;
- nothing is underlined in `src/notParaglide.ts`, nor `m[key]` in `src/profile.ts`;
- `$schema` shows nowhere in the tool window.

Navigation to the message, folding to its value, completion after `m.`, usages and rename are not supported yet.
