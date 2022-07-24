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
    parameters([
      string(name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchDevopsInfra,
          description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
      string(name: 'VEGACAPSULE_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchVegaCapsule,
          description: 'Git branch, tag or hash of the vegaprotocol/vegacapsule repository'),
      string(name: 'VEGA_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchVega,
          description: 'Git branch, tag or hash of the vegaprotocol/vega repository'),
      string(name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchSystemTests,
          description: 'Git branch, tag or hash of the vegaprotocol/system-tests repository'),
      string(name: 'VEGATOOLS_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchVegatools,
          description: 'Git branch, tag or hash of the vegaprotocol/vegatools repository'),

      string(name: 'SYSTEM_TESTS_TEST_FUNCTION', defaultValue: pipelineDefaults.capsuleSystemTests.systemTestsTestFunction,
          description: '''Run only a tests with a specified function name. This is actually a "pytest -k
          $SYSTEM_TESTS_TEST_FUNCTION_NAME" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html'''),
      string(name: 'SYSTEM_TESTS_TEST_MARK', defaultValue: pipelineDefaults.capsuleSystemTests.systemTestsTestMark,
          description: '''Run only a tests with the specified mark(s). This is actually a "pytest -m $SYSTEM_TESTS_TEST_MARK"
          command-line argument, see more: https://docs.pytest.org/en/stable/usage.html'''),
      string(name: 'SYSTEM_TESTS_TEST_DIRECTORY', defaultValue: pipelineDefaults.capsuleSystemTests.systemTestsTestDirectory,
          description: 'Run tests from files in this directory and all sub-directories'),
      string(name: 'CAPSULE_CONFIG', defaultValue: pipelineDefaults.capsuleSystemTests.capsuleConfig,
          description: 'Run tests using the given vegacapsule config file'),
      booleanParam(
          name: 'SYSTEM_TESTS_DEBUG', defaultValue: pipelineDefaults.capsuleSystemTests.systemTestsDebug,
          description: 'Enable debug logs for system-tests execution'),
      string(name: 'TIMEOUT', defaultValue: pipelineDefaults.capsuleSystemTests.systemTestsRunTimeout,
          description: 'Timeout for system test run'),
      booleanParam(name: 'PRINT_NETWORK_LOGS', defaultValue: pipelineDefaults.capsuleSystemTests.printNetworkLogsInJenkinsPipeline,
          description: 'By default logs are only archived as as Jenkins Pipeline artifact. If this is checked, the logs will be printed in jenkins as well'),
    ])
  ])

  node('system-tests-capsule') {
    println('pipelineCapsuleSystemTests params: ' + params)
    sh("""mkdir -p "\$(dirname ${pipelineDefaults.art.systemTestCapsuleJunit})" """)

    capsuleSystemTests([
      branchDevopsInfra: fne(params.DEVOPS_INFRA_BRANCH, pipelineDefaults.capsuleSystemTests.branchDevopsInfra),
      branchVegaCapsule: fne(params.VEGACAPSULE_BRANCH, pipelineDefaults.capsuleSystemTests.branchVegaCapsule),
      branchVega: fne(params.VEGA_BRANCH, pipelineDefaults.capsuleSystemTests.branchVega),
      branchSystemTests: fne(params.SYSTEM_TESTS_BRANCH, pipelineDefaults.capsuleSystemTests.branchSystemTests),
      branchVegatools: fne(params.VEGATOOLS_BRANCH, pipelineDefaults.capsuleSystemTests.branchVegatools),
      capsuleConfig: fne(params.CAPSULE_CONFIG, pipelineDefaults.capsuleSystemTests.capsuleConfig),
      systemTestsTestFunction: fne(params.SYSTEM_TESTS_TEST_FUNCTION, pipelineDefaults.capsuleSystemTests.systemTestsTestFunction),
      systemTestsTestMark: fne(params.SYSTEM_TESTS_TEST_MARK, pipelineDefaults.capsuleSystemTests.systemTestsTestMark),
      systemTestsTestDirectory: fne(params.SYSTEM_TESTS_TEST_DIRECTORY, pipelineDefaults.capsuleSystemTests.systemTestsTestDirectory),
      systemTestsDebug: params.SYSTEM_TESTS_DEBUG,
      systemTestsRunTimeout: params.TIMEOUT,
      printNetworkLogs: params.SYSTEM_TESTS_TEST_DIRECTORY,
    ])
  }
}

/**
 * Example usage
 **/
// pipelineCapsuleSystemTests()
