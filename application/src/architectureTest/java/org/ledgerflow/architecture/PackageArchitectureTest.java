package org.ledgerflow.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.ledgerflow", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageArchitectureTest {

  @ArchTest
  static final ArchRule featureModulesAreAcyclic =
      slices().matching("com.ledgerflow.(*)..").namingSlices("$1").should().beFreeOfCycles();

  @ArchTest
  static final ArchRule repositoryWideTechnicalLayersAreForbidden =
      noClasses()
          .should()
          .resideInAnyPackage(
              "com.ledgerflow.controller..",
              "com.ledgerflow.entity..",
              "com.ledgerflow.model..",
              "com.ledgerflow.repository..",
              "com.ledgerflow.service..");
}
