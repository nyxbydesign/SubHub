package com.subhub.app.atmosphere;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Hold alternatives for the keyboard's shared English and German QWERTY layout. */
final class GagKeyAlternatives {
    private GagKeyAlternatives() {}

    static List<String> forLetter(String letter, String hint, boolean shifted) {
        String accents = switch (letter) {
            case "a" -> "àáâäæãåā";
            case "c" -> "ç";
            case "e" -> "éèêëēė";
            case "i" -> "íîïīì";
            case "n" -> "ñń";
            case "o" -> "óôöòœøōõ";
            case "s" -> "ßśš";
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
            case "1" -> "¹½⅓¼⅕⅙⅐⅛⅑⅒";
            case "2" -> "²⅔⅖";
            case "3" -> "³¾⅗⅜";
            case "4" -> "⁴⅘";
            case "5" -> "⁵⅚⅝";
            case "6" -> "⁶";
            case "7" -> "⁷⅞";
            case "8" -> "⁸";
            case "9" -> "⁹";
            case "0" -> "⁰ⁿ∅";
            case "$" -> "¢£€¥₱₹";
            case "£", "€", "¥", "¢" -> "¢£€¥₱$₹";
            case "-" -> "_–—·";
            case "+" -> "±";
            case "*" -> "†‡★";
            case "(" -> "<{[";
            case ")" -> ">}]";
            case "<" -> "‹≤«⟨";
            case ">" -> "›≥»⟩";
            case "!" -> "¡";
            case "?" -> "¿‽";
            case "'" -> "‘’‚‛‹›";
            case "\"" -> "“”„‟«»";
            case "." -> "…·";
            case "•" -> "·♪♥♠♦♣";
            case "π" -> "ΠμΩ";
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
