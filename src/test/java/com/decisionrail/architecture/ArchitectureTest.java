package com.decisionrail.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.decisionrail");

    @Test
    void decisionDomainIsIndependentOfTransportAndPersistence() {
        noClasses().that().resideInAPackage("..decision..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "..payments..", "..api..", "com.fasterxml.jackson..")
                .because("rule evaluation must be reusable in replay without a database or web server").check(classes);
    }

    @Test
    void shadowEvaluationCannotReachAnyFinancialMutation() {
        // The isolation guarantee should not depend on nobody adding an import later. Shadow code
        // has no access to the payment service, the payment store, or the outbox, so it cannot
        // reserve funds, capture, void, write a ledger entry, change a stored decision, or emit a
        // financial event even by mistake.
        noClasses().that().resideInAPackage("..shadow..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.decisionrail.payments.PaymentService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.decisionrail.payments.PaymentStore")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.decisionrail.events.OutboxStore")
                .because("shadow evaluation must not be able to move money or emit financial events").check(classes);
    }

    @Test
    void replayCannotReachAnyFinancialMutation() {
        noClasses().that().resideInAPackage("..replay..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.decisionrail.payments.PaymentService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.decisionrail.payments.PaymentStore")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.decisionrail.events.OutboxStore")
                .because("replaying history must not alter the payments it replays").check(classes);
    }

    @Test
    void httpHandlersCannotBypassTransactionalPaymentService() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .because("financial mutations must go through the transaction boundary").check(classes);
    }
}
