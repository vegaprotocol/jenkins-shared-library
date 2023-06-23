/* groovylint-disable
  BuilderMethodWithSideEffects, CompileStatic, DuplicateStringLiteral,
  FactoryMethodName, VariableTypeRequired */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
  Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
  String systemTestsCapsuleJob = '/common/system-tests'

  List buildParameters = [
      string(name: 'TIMEOUT', value: config.timeout ? "${config.timeout}" : pipelineDefaults.capsuleSystemTests.systemTestsRunTimeout),
      string(name: 'ORIGIN_REPO', value: config.originRepo ?: 'vegaprotocol/vega'),
      string(name: 'VEGA_BRANCH', value: config.vegaVersion ?: pipelineDefaults.capsuleSystemTests.branchVega),
      string(name: 'SYSTEM_TESTS_BRANCH', value: config.systemTests ?: pipelineDefaults.capsuleSystemTests.branchSystemTests),
      string(name: 'VEGACAPSULE_BRANCH', value: config.vegacapsule ?: pipelineDefaults.capsuleSystemTests.branchVegaCapsule),
      string(name: 'VEGATOOLS_BRANCH', value: config.vegatools ?:  pipelineDefaults.capsuleSystemTests.branchVegatools),
      string(name: 'DEVOPS_INFRA_BRANCH', value: config.devopsInfra ?: pipelineDefaults.capsuleSystemTests.branchDevopsInfra),
      string(name: 'DEVOPSSCRIPTS_BRANCH', value: config.devopsScripts ?: pipelineDefaults.capsuleSystemTests.branchDevopsScripts),
      string(name: 'JENKINS_SHARED_LIB_BRANCH', value: config.jenkinsSharedLib ?: pipelineDefaults.capsuleSystemTests.jenkinsSharedLib),
      string(name: 'NODE_LABEL', value: config.nodeLabel ?: pipelineDefaults.capsuleSystemTests.nodeLabel),

      // string(name: 'SYSTEM_TESTS_TEST_FUNCTION', value: config.testFunction ?: pipelineDefaults.capsuleSystemTests.systemTestsTestFunction),
      // string(name: 'SYSTEM_TESTS_TEST_MARK', value: config.testMark ?: pipelineDefaults.capsuleSystemTests.systemTestsTestMark),
      // string(name: 'SYSTEM_TESTS_TEST_DIRECTORY', value: config.testDirectory ?: pipelineDefaults.capsuleSystemTests.systemTestsTestDirectory),
      // string(name: 'CAPSULE_CONFIG', value: config.capsuleConfig ?: pipelineDefaults.capsuleSystemTests.capsuleConfig),
      booleanParam(
          name: 'SYSTEM_TESTS_DEBUG', value: config.systemTestsDebug ? "${config.systemTestsDebug}".toBoolean() : pipelineDefaults.capsuleSystemTests.systemTestsDebug),
      booleanParam(name: 'PRINT_NETWORK_LOGS', value: config.printNetworkLogs ?: pipelineDefaults.capsuleSystemTests.printNetworkLogsInJenkinsPipeline),
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
          selector: specific("${st.number}"),
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
