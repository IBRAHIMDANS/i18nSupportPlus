package com.example.messages;

import java.util.Locale;
import java.util.ResourceBundle;

import org.springframework.context.MessageSource;

/**
 * Both ways a Java application reads its bundle. IntelliJ IDEA resolves the two keys itself: Ctrl+click,
 * completion, Find Usages, rename and the "Unused property" inspection on messages.properties.
 */
public class Greeter {

    private final MessageSource messageSource;

    public Greeter(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    public String greet(String name, Locale locale) {
        return messageSource.getMessage("user.greeting", new Object[] {name}, locale);
    }

    public String farewell(Locale locale) {
        return ResourceBundle.getBundle("messages", locale).getString("farewell");
    }
}
