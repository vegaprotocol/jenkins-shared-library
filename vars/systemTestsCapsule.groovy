/* groovylint-disable 
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral, 
  FactoryMethodName, VariableTypeRequired */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
  Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
  String systemTestsCapsuleJob = '/system-tests/system-tests-capsule'

  List buildParameters = [
      string(name: 'DEVOPS_INFRA_BRANCH', value: config.devopsInfra ?: pipelineDefaults.capsuleSystemTests.branchDevopsInfra),
      string(name: 'VEGACAPSULE_BRANCH', value: config.vegacapsule ?: pipelineDefaults.capsuleSystemTests.branchVegaCapsule),
      string(name: 'VEGA_CORE_BRANCH', value: config.vegaCore ?: pipelineDefaults.capsuleSystemTests.branchVega),
      string(name: 'DATA_NODE_BRANCH', value: config.dataNode ?: pipelineDefaults.capsuleSystemTests.branchDataNode),
      string(name: 'SYSTEM_TESTS_BRANCH', value: config.systemTests ?: pipelineDefaults.capsuleSystemTests.branchSystemTests),
      string(name: 'VEGAWALLET_BRANCH', value: config.vegawallet ?: pipelineDefaults.capsuleSystemTests.branchVegawallet),
      string(name: 'PROTOS_BRANCH', value: config.protos ?: pipelineDefaults.capsuleSystemTests.branchProtos),
      string(name: 'VEGATOOLS_BRANCH', value: config.vegatools ?:  pipelineDefaults.capsuleSystemTests.branchVegatools),

      string(name: 'SYSTEM_TESTS_TEST_FUNCTION', value: config.testFunction ?: pipelineDefaults.capsuleSystemTests.systemTestsTestFunction),
      string(name: 'SYSTEM_TESTS_TEST_MARK', value: config.testMark ?: pipelineDefaults.capsuleSystemTests.systemTestsTestMark),
      string(name: 'SYSTEM_TESTS_TEST_DIRECTORY', value: config.testDirectory ?: pipelineDefaults.capsuleSystemTests.systemTestsTestDirectory),
      booleanParam(
          name: 'SYSTEM_TESTS_DEBUG', value: config.systemTestsDebug ? "${config.systemTestsDebug}".toBoolean() : pipelineDefaults.capsuleSystemTests.systemTestsDebug),
      string(name: 'TIMEOUT', value: config.timeout ? "${config.timeout}" : pipelineDefaults.capsuleSystemTests.systemTestsRunTimeout),
  ]


   RunWrapper st = build(
      job: systemTestsCapsuleJob,
      propagate: false,  // don't fail yet
      wait: true,
      parameters: buildParameters)

    try {
      echo "System-Tests with Vegacapsule execution pipeline: ${st.absoluteUrl}"

      sh label: 'remove old junit result file', script: """#!/bin/bash -e
          rm -f "${pipelineDefaults.art.systemTestCapsuleJunit} || echo 'No need to cleanup JUnit'"
      """

      copyArtifacts(
          projectName: systemTestsCapsuleJob,
          selector: specific("${stc.number}"),
          fingerprintArtifacts: true,
          filter: pipelineDefaults.art.systemTestCapsuleJunit
      )

      junit checksName: 'System Tests Capsule',
          testResults: pipelineDefaults.art.systemTestCapsuleJunit,
          skipMarkingBuildUnstable: ignoreFailure,
          skipPublishingChecks: ignoreFailure
    } catch (e) {
      echo "Ignoring error in gathering results from downstream build: ${e}"
    }

    // now fail
    if (st.result != 'SUCCESS') {
      if (ignoreFailure) {
        catchError(message: 'System Tests Failed', buildResult: null, stageResult: st.result) {
            error("Ignore failure and keep job green, but mark stage as ${st.result}")
        }
      } else {
        if (st.result == 'UNSTABLE') {
            unstable('UNSTABLE - System Tests')
        } else if (st.result == 'ABORTED') {
            currentBuild.result = 'ABORTED'
            error('ABORTED - System Tests')
        } else {
            error("${st.result} - System Tests")
        }
      }
    }

}
