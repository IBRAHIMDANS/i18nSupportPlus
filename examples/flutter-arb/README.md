# Flutter ARB catalogues

Two catalogues, as `flutter gen-l10n` reads them (`l10n.yaml`): `lib/l10n/app_en.arb`, the template with its
`@key` metadata, and `lib/l10n/app_fr.arb`.

Open the folder in the sandbox IDE (`./gradlew runIde`), then:

- **Tool window**: `appTitle`, `greeting` and `cartEmpty`, in `en` and `fr`. No `@appTitle`, `@greeting` nor `@@locale` row.
- **Statistics**: `fr` misses one key of three.
- ***Sync Keys***: proposes `cartEmpty` for `fr`, and nothing else.

Dart code is not read: `AppLocalizations.of(context)!.greeting` is checked and completed by the Dart analyzer through the
generated class. The keys are therefore never reported unused.
