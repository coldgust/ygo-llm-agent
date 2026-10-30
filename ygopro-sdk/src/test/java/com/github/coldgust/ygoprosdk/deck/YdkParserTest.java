package com.github.coldgust.ygoprosdk.deck;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YdkParserTest {

    @Test
    void parsesUtf8BomAndCrlf() {
        String text = "\uFEFF#created by MDPro3\r\n#main\r\n89631139\r\n#extra\r\n\r\n!side\r\n";
        var deck = YdkParser.parse(text);
        assertEquals(1, deck.main().size());
        assertEquals(89631139L, deck.main().getFirst());
    }
}
