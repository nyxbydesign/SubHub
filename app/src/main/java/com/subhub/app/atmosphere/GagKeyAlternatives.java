package com.subhub.app.atmosphere;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** English QWERTY and symbol alternatives, based on AOSP LatinIME. */
final class GagKeyAlternatives {
    private GagKeyAlternatives() {}

    static List<String> forLetter(String letter, String hint, boolean shifted) {
        String accents = switch (letter) {
            case "a" -> "àáâäæãåā";
            case "c" -> "ç";
            case "e" -> "éèêëē";
            case "i" -> "íîïīì";
            case "n" -> "ñ";
            case "o" -> "óôöòœøōõ";
            case "s" -> "ß";
            case "u" -> "úûüùū";
            default -> "";
        };
        if (accents.isEmpty()) return List.of(hint);
        ArrayList<String> choices = new ArrayList<>();
        choices.add(hint);
        accents.codePoints().mapToObj(Character::toString).forEach(value ->
                choices.add(shifted ? (value.equals("ß") ? "ẞ" : value.toUpperCase(Locale.ROOT)) : value));
        return List.copyOf(choices);
    }

    static List<String> forSymbol(String symbol) {
        String alternatives = switch (symbol) {
            case "1" -> "¹½⅓¼⅛";
            case "2" -> "²⅔";
            case "3" -> "³¾⅜";
            case "4" -> "⁴";
            case "5" -> "⅝";
            case "7" -> "⅞";
            case "0" -> "ⁿ∅";
            case "$" -> "¢£€¥₱";
            case "£", "€", "¥", "¢" -> "¢£€¥₱$";
            case "-" -> "_–—";
            case "+" -> "±";
            case "*" -> "†‡★";
            case "(" -> "<{[";
            case ")" -> ">}]";
            case "<" -> "‹≤«";
            case ">" -> "›≥»";
            case "!" -> "¡";
            case "?" -> "¿";
            case "'" -> "‘’‚‛‹›";
            case "\"" -> "“”„‟«»";
            case "." -> "…·";
            case "•" -> "·♪♥♠♦♣";
            case "π" -> "Π";
            case "°" -> "′″";
            case "^" -> "↑↓←→";
            case "=" -> "≠≈∞";
            case "¶" -> "§";
            case "%" -> "‰";
            default -> "";
        };
        ArrayList<String> choices = new ArrayList<>();
        alternatives.codePoints().mapToObj(Character::toString).filter(value -> !value.equals(symbol))
                .forEach(choices::add);
        return List.copyOf(choices);
    }
}
