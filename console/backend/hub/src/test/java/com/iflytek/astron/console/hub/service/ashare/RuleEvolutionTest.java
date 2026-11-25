package com.iflytek.astron.console.hub.service.ashare;

import com.iflytek.astron.console.hub.service.ashare.RuleEvolutionService.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEvolutionTest {

    @Test
    void everySeedRuleIsValidInTheLanguage() {
        // A blank starting page gives the model nothing to improve, so the seeds must really parse.
        assertFalse(RuleEvolutionService.SEEDS.isEmpty());
        RuleEvolutionService.SEEDS.forEach((expr, idea) -> {
            assertEquals(null, RuleExpression.check(expr), "seed rejected: " + expr);
            assertFalse(idea.isBlank());
        });
    }

    @Test
    void rulesAreReadOutOfAModelAnswerWithStrayText() {
        String answer = """
                好的，这是我设计的规则：
                ```json
                [{"expression":"close > ma(close,20) and volratio(5) > 1.5","idea":"放量站上月线"},
                 {"expression":"rsi(close,6) < 20","idea":"极度超卖"}]
                ```
                希望有帮助！
                """;
        List<Candidate> out = RuleEvolutionService.parseRules(answer);
        assertEquals(2, out.size());
        assertEquals("close > ma(close,20) and volratio(5) > 1.5", out.get(0).expression());
        assertEquals("放量站上月线", out.get(0).idea());
        assertEquals("极度超卖", out.get(1).idea());
    }

    @Test
    void anUnusableAnswerYieldsNothingRatherThanGarbage() {
        assertTrue(RuleEvolutionService.parseRules(null).isEmpty());
        assertTrue(RuleEvolutionService.parseRules("").isEmpty());
        assertTrue(RuleEvolutionService.parseRules("抱歉，我无法完成。").isEmpty());
        assertTrue(RuleEvolutionService.parseRules("[不是 JSON]").isEmpty());
        // Entries without an expression are skipped, the rest still come through.
        List<Candidate> partial = RuleEvolutionService.parseRules(
                "[{\"idea\":\"缺表达式\"},{\"expression\":\"close > ma(close,5)\"}]");
        assertEquals(1, partial.size());
        assertEquals("未说明", partial.get(0).idea());
    }

    @Test
    void theSameRuleWrittenDifferentlyCountsAsOne() {
        String a = "close > ma(close,20) and rsi(close,14) < 70";
        String b = "CLOSE  >  MA(close,20)   and RSI(close,14)<70";
        assertEquals(RuleEvolutionService.hash(RuleEvolutionService.normalize(a)),
                RuleEvolutionService.hash(RuleEvolutionService.normalize(b)));
        assertNotEquals(RuleEvolutionService.hash(RuleEvolutionService.normalize(a)),
                RuleEvolutionService.hash(RuleEvolutionService.normalize("close > ma(close,21)")));
    }

    @Test
    void islandsAndBudgetsAreSane() {
        // Several islands, or an early winner flattens the population and the search stops exploring.
        assertTrue(RuleEvolutionService.ISLANDS >= 2);
        assertTrue(RuleEvolutionService.SAMPLE_SIZE >= 2);
        // Each round is a model call, so the budget has to stay small.
        assertTrue(RuleEvolutionService.MAX_ROUNDS <= 5);
        assertTrue(RuleEvolutionService.RULES_PER_ROUND >= 2);
        // A rule that barely trades cannot be judged, however good its Sharpe looks.
        assertTrue(RuleEvolutionService.MIN_TRADES >= 10);
    }
}
