package com.decisionrail.api;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.decision.DecisionFlag;
import com.decisionrail.decision.RuleExpression;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/rules")
public class RuleController {
    private final DecisionEngine engine;
    public RuleController(DecisionEngine engine) { this.engine = engine; }

    @GetMapping("/active")
    public ActiveRulesView active() {
        var snapshot = engine.activeRuleSet();
        return new ActiveRulesView(snapshot.version(), DecisionEngine.HOME_COUNTRY, 30, 60,
                snapshot.rules().stream().map(rule -> new RuleView(rule.code(), rule.description(),
                        expression(rule.expression()), rule.scoreContribution(), rule.flag(), rule.terminal())).toList());
    }

    private static Map<String, Object> expression(RuleExpression expression) {
        // Explicit wire operators preserve distinctions between otherwise identical record shapes.
        return switch (expression) {
            case RuleExpression.All all -> Map.of("operator", "ALL", "children", all.children().stream().map(RuleController::expression).toList());
            case RuleExpression.Any any -> Map.of("operator", "ANY", "children", any.children().stream().map(RuleController::expression).toList());
            case RuleExpression.AmountAtLeast value -> Map.of("operator", "AMOUNT_AT_LEAST", "amountMinor", value.amountMinor());
            case RuleExpression.AmountLessThan value -> Map.of("operator", "AMOUNT_LESS_THAN", "amountMinor", value.amountMinor());
            case RuleExpression.CurrencyIn value -> Map.of("operator", "CURRENCY_IN", "currencies", value.currencies().stream().sorted().toList());
            case RuleExpression.CountryIn value -> Map.of("operator", "COUNTRY_IN", "countries", value.countries().stream().sorted().toList());
            case RuleExpression.CountryOutside value -> Map.of("operator", "COUNTRY_OUTSIDE", "countries", value.countries().stream().sorted().toList());
        };
    }

    public record ActiveRulesView(String version, String homeCountry, int reviewThreshold, int declineThreshold, List<RuleView> rules) {}
    public record RuleView(String code, String description, Map<String, Object> expression, int scoreContribution, DecisionFlag flag, boolean terminal) {}
}
