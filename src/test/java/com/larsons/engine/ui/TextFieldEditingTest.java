package com.larsons.engine.ui;

import com.larsons.engine.input.InputManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Editing a text field the way a person does.
 *
 * <p>Every text box in the engine is one of these — a world's name, a friend's
 * address, a port — and until this test existed they could only <b>append and
 * backspace</b>. No caret, no {@code Delete}, no arrows, no {@code Home} or
 * {@code End}, and no clipboard, which meant correcting a typo near the start
 * of a pasted address required deleting everything after it first.
 *
 * <p><b>Events go in before {@code newFrame}, not after.</b> {@link
 * InputManager} latches what arrives and promotes the latch on the frame
 * boundary, so a harness that calls {@code newFrame} first reads every
 * keystroke one tick late — which looks exactly like the feature not working,
 * and cost an hour of chasing the wrong thing.
 */
class TextFieldEditingTest {

    private String value = "";
    private String board = "";
    private InputManager input;
    private ConfigForm form;

    @BeforeEach
    void setUp() {
        ConfigForm.setClipboard(new ConfigForm.Clipboard() {
            @Override public String read() { return board; }
            @Override public void write(String text) { board = text; }
        });
        input = new InputManager();
        form = new ConfigForm("Test");
        form.addText("Field", () -> value, v -> value = v, 40);
        for (int i = 0; i < 3; i++) tick();   // let the form settle onto the row
    }

    @AfterEach
    void tearDown() {
        ConfigForm.setClipboard(null);
    }

    private void tick() {
        input.newFrame();
        form.update(1 / 60.0, input);
    }

    private void type(String text) {
        for (char c : text.toCharArray()) {
            input.keyTyped(new KeyEvent(new JPanel(), KeyEvent.KEY_TYPED, 0, 0, 0, c));
        }
        tick();
    }

    private void press(int keyCode, int... modifiers) {
        for (int modifier : modifiers) {
            input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0,
                    modifier, (char) 0));
        }
        input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0,
                keyCode, (char) 0));
        tick();
        input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0,
                keyCode, (char) 0));
        for (int modifier : modifiers) {
            input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0,
                    modifier, (char) 0));
        }
    }

    private static final int CTRL = KeyEvent.VK_CONTROL;
    private static final int SHIFT = KeyEvent.VK_SHIFT;

    @Test
    void typingAppendsAndBackspaceRemoves() {
        type("hello");
        assertEquals("hello", value);
        press(KeyEvent.VK_BACK_SPACE);
        assertEquals("hell", value);
    }

    @Test
    void theCaretMovesAndTypingHappensWhereItIs() {
        type("13");
        press(KeyEvent.VK_LEFT);
        type("2");
        assertEquals("123", value, "typing went to the end rather than to the caret");
    }

    @Test
    void homeAndEndGoToTheEnds() {
        type("abc");
        press(KeyEvent.VK_HOME);
        type("-");
        assertEquals("-abc", value);
        press(KeyEvent.VK_END);
        type("!");
        assertEquals("-abc!", value);
    }

    @Test
    void deleteTakesTheCharacterInFrontOfTheCaret() {
        type("1.2.3.4:7799");
        press(KeyEvent.VK_HOME);
        press(KeyEvent.VK_RIGHT);
        press(KeyEvent.VK_RIGHT);
        press(KeyEvent.VK_DELETE);
        assertEquals("1..3.4:7799", value, "Delete did not remove the character ahead");
    }

    @Test
    void shiftExtendsASelectionAndTypingReplacesIt() {
        type("abcdef");
        press(KeyEvent.VK_HOME);
        press(KeyEvent.VK_RIGHT, SHIFT);
        press(KeyEvent.VK_RIGHT, SHIFT);
        press(KeyEvent.VK_RIGHT, SHIFT);
        type("X");
        assertEquals("Xdef", value, "typing over a selection did not replace it");
    }

    @Test
    void selectAllThenTypeReplacesTheWholeField() {
        type("something wrong");
        press(KeyEvent.VK_A, CTRL);
        type("right");
        assertEquals("right", value);
    }

    @Test
    void copyAndPasteGoThroughTheClipboard() {
        type("1.2.3.4:7799");
        press(KeyEvent.VK_A, CTRL);
        press(KeyEvent.VK_C, CTRL);
        assertEquals("1.2.3.4:7799", board, "copy put nothing on the clipboard");

        press(KeyEvent.VK_A, CTRL);
        type("x");
        press(KeyEvent.VK_V, CTRL);
        assertEquals("x1.2.3.4:7799", value, "paste did not insert at the caret");
    }

    @Test
    void cutTakesTheTextAndLeavesItOnTheClipboard() {
        type("cut me");
        press(KeyEvent.VK_A, CTRL);
        press(KeyEvent.VK_X, CTRL);
        assertEquals("", value, "cut left the text in the field");
        assertEquals("cut me", board, "cut put nothing on the clipboard");

        press(KeyEvent.VK_V, CTRL);
        assertEquals("cut me", value, "what was cut would not paste back");
    }

    @Test
    void controlMovesAndDeletesByWord() {
        type("one two three");
        press(KeyEvent.VK_BACK_SPACE, CTRL);
        assertEquals("one two ", value, "Ctrl+Backspace did not take the whole word");

        press(KeyEvent.VK_HOME);
        press(KeyEvent.VK_DELETE, CTRL);
        assertEquals("two ", value, "Ctrl+Delete did not take the word ahead");
    }

    /** A chord must act, and must not also spell its own letter into the field. */
    @Test
    void aControlChordDoesNotTypeItsLetter() {
        type("abc");
        input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0, CTRL, (char) 0));
        input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0,
                KeyEvent.VK_A, (char) 0));
        // AWT delivers a control character for the chord as well.
        input.keyTyped(new KeyEvent(new JPanel(), KeyEvent.KEY_TYPED, 0, 0, 0, (char) 1));
        tick();
        assertEquals("abc", value, "Ctrl+A typed something into the field");
    }

    /** The field's own length limit still holds against a long paste. */
    @Test
    void aPasteCannotOverrunTheFieldsLimit() {
        board = "0123456789".repeat(8);   // eighty characters into a field of forty
        press(KeyEvent.VK_V, CTRL);
        assertEquals(40, value.length(), "paste ignored the field's maximum length");
    }

    /** A pasted newline ends the value rather than swallowing the rest. */
    @Test
    void aPastedNewlineDoesNotDragInASecondLine() {
        board = "first line\nsecond line";
        press(KeyEvent.VK_V, CTRL);
        assertEquals("first line", value);
    }
}
