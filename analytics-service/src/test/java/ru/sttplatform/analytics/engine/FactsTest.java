package ru.sttplatform.analytics.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FactsTest {
    @Test
    void reference_is_split_into_sentences_but_not_inside_parentheses() {
        List<String> facts = Facts.split("JVM — виртуальная машина, исполняющая байткод Java. JRE — среда для запуска приложений "
                + "(JVM и библиотеки выполнения; в современных поставках JDK отдельный JRE обычно не распространяется). "
                + "JDK включает инструменты разработки, в том числе компилятор javac, и среду выполнения.");
        assertEquals(3, facts.size());
        assertTrue(facts.get(1).startsWith("JRE"), facts.get(1));
        assertTrue(facts.get(1).contains("JVM и библиотеки") || facts.get(1).endsWith("…"), "скобки не должны разрываться: " + facts.get(1));
    }

    @Test
    void facts_are_clipped_to_fit_the_models_instruction_limit() {
        for (String fact : Facts.split("Очень длинное утверждение, " + "которое продолжается и продолжается, ".repeat(10) + "и заканчивается."))
            assertTrue(fact.length() <= Facts.MAX_CHARS, fact.length() + ": " + fact);
    }

    @Test
    void short_fragments_are_glued_to_the_previous_fact() {
        List<String> facts = Facts.split("HTTP подходит для простого запроса-ответа. Да. Очередь нужна для буферизации нагрузки.");
        assertEquals(2, facts.size());
        assertTrue(facts.get(0).contains("Да"));
    }

    @Test
    void number_of_facts_is_capped() {
        String ref = String.join(" ", java.util.Collections.nCopies(12, "Это достаточно длинное отдельное утверждение номер один."));
        assertTrue(Facts.split(ref).size() <= Facts.MAX_FACTS);
    }

    @Test
    void blank_reference_gives_no_facts() {
        assertTrue(Facts.split("  ").isEmpty());
        assertTrue(Facts.split(null).isEmpty());
    }

    @Test
    void every_reference_in_the_bank_gives_two_to_six_facts() {
        for (CompiledQuestion cq : TestKb.get().questions()) {
            int n = Facts.split(cq.question().reference()).size();
            assertTrue(n >= 1 && n <= Facts.MAX_FACTS, cq.question().text() + " -> " + n);
        }
    }
}
