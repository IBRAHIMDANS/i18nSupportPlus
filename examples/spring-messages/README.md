# Spring messages (Java resource bundles)

Manual testing target for the **i18n Support Plus** plugin on Java resource bundles, as a Spring Boot application
keeps them in `src/main/resources`: `messages.properties` holds the reference locale (English),
`messages_fr.properties` and `messages_pt_BR.properties` the translations. `application.properties` has no localized
file beside it: it is configuration, never read as translations.

## Run it

Open this folder (`examples/spring-messages`) in IntelliJ IDEA and let it import the Maven project (`pom.xml`, Spring's
`spring-context` only). The setup wizard ticks *Spring*.

`Greeter.java` reads the bundle both ways, `messageSource.getMessage("user.greeting", …)` and
`ResourceBundle.getString("farewell")`. IDEA resolves both keys itself (Ctrl+click, completion, rename, *Unused
property*); the plugin adds the bundle to its tool window and checks.

## What the messages cover

| Case | Where |
|---|---|
| Dotted key read as levels (`user` › `name`) | `user.name`, `user.greeting` |
| `MessageFormat` placeholder | `user.greeting` (`{0}`), `cart.items` (`{0,number}`) |
| Placeholder of the reference missing from a translation | `user.greeting` in `messages_fr.properties` |
| Missing key | `cart.items` in `messages_pt_BR.properties` |
| Empty value | `farewell` in `messages_fr.properties` |
| `\uXXXX` escape and line continuation | `welcome` |

From the repository root, the command-line check reports the same problems:

```bash
./gradlew :cli:installDist
cli/build/install/i18n-check/bin/i18n-check examples/spring-messages
```
