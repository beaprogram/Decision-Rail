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
    void httpHandlersCannotBypassTransactionalPaymentService() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .because("financial mutations must go through the transaction boundary").check(classes);
    }
}
