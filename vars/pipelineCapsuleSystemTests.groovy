/* groovylint-disable LineLength */
String fne(String v1, String v2) {
  /* groovylint-disable-next-line UnnecessaryGetter */
  if (v1 == null || v1.isEmpty()) {
    return v2
  }

  return v1
}

void call() {
  properties([
    buildDiscarder(logRotator(daysToKeepStr: '14')),
    copyArtifactPermission('*'),
    // parameters - set in DSL: dsl/scripts/jobs.groovy
  ])

  node('system-tests-capsule') {
    println('pipelineCapsuleSystemTests params: ' + params)
    sh("""mkdir -p "\$(dirname ${pipelineDefaults.art.systemTestCapsuleJunit})" """)

    capsuleSystemTests([
      branchVega: fne(params.VEGA_BRANCH, pipelineDefaults.capsuleSystemTests.branchVega),
      branchProtos: fne(params.PROTOS_BRANCH, pipelineDefaults.capsuleSystemTests.branchProtos),
      branchSystemTests: fne(params.SYSTEM_TESTS_BRANCH, pipelineDefaults.capsuleSystemTests.branchSystemTests),
      branchVegaCapsule: fne(params.VEGACAPSULE_BRANCH, pipelineDefaults.capsuleSystemTests.branchVegaCapsule),
      branchVegatools: fne(params.VEGATOOLS_BRANCH, pipelineDefaults.capsuleSystemTests.branchVegatools),
      branchDevopsInfra: fne(params.DEVOPS_INFRA_BRANCH, pipelineDefaults.capsuleSystemTests.branchDevopsInfra),
      branchDevopsScripts: fne(params.DEVOPSSCRIPTS_BRANCH, pipelineDefaults.capsuleSystemTests.branchDevopsScripts),

      systemTestsTestFunction: fne(params.SYSTEM_TESTS_TEST_FUNCTION, pipelineDefaults.capsuleSystemTests.systemTestsTestFunction),
      systemTestsTestMark: fne(params.SYSTEM_TESTS_TEST_MARK, pipelineDefaults.capsuleSystemTests.systemTestsTestMark),
      systemTestsTestDirectory: fne(params.SYSTEM_TESTS_TEST_DIRECTORY, pipelineDefaults.capsuleSystemTests.systemTestsTestDirectory),
      capsuleConfig: fne(params.CAPSULE_CONFIG, pipelineDefaults.capsuleSystemTests.capsuleConfig),
      systemTestsDebug: params.SYSTEM_TESTS_DEBUG,
      systemTestsRunTimeout: params.TIMEOUT,
      printNetworkLogs: params.PRINT_NETWORK_LOGS,
    ])
  }
}

/**
 * Example usage
 **/
// pipelineCapsuleSystemTests()
