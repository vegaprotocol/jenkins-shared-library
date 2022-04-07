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
    parameters([
      string(name: 'DEVOPS_INFRA_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchDevopsInfra,
          description: 'Git branch, tag or hash of the vegaprotocol/devops-infra repository'),
      string(name: 'VEGACAPSULE_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchVegaCapsule,
          description: 'Git branch, tag or hash of the vegaprotocol/vegacapsule repository'),
      string(name: 'VEGA_CORE_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchVega,
          description: 'Git branch, tag or hash of the vegaprotocol/vega repository'),
      string(name: 'DATA_NODE_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchDataNode,
          description: 'Git branch, tag or hash of the vegaprotocol/data-node repository'),
      string(name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchSystemTests,
          description: 'Git branch, tag or hash of the vegaprotocol/system-tests repository'),
      string(name: 'VEGAWALLET_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchVegawallet,
          description: 'Git branch, tag or hash of the vegaprotocol/vegawallet repository'),
      string(name: 'PROTOS_BRANCH', defaultValue: pipelineDefaults.capsuleSystemTests.branchProtos,
          description: 'Git branch, tag or hash of the vegaprotocol/protos repository'),
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
    capsuleSystemTests([
      branchDevopsInfra: fne(params.DEVOPS_INFRA_BRANCH, pipelineDefaults.capsuleSystemTests.branchDevopsInfra),
      branchVegaCapsule: fne(params.VEGACAPSULE_BRANCH, pipelineDefaults.capsuleSystemTests.branchVegaCapsule),
      branchVega: fne(params.VEGA_CORE_BRANCH, pipelineDefaults.capsuleSystemTests.branchVega),
      branchDataNode: fne(params.DATA_NODE_BRANCH, pipelineDefaults.capsuleSystemTests.branchDataNode),
      branchSystemTests: fne(params.SYSTEM_TESTS_BRANCH, pipelineDefaults.capsuleSystemTests.branchSystemTests),
      branchVegawallet: fne(params.VEGAWALLET_BRANCH, pipelineDefaults.capsuleSystemTests.branchVegawallet),
      branchProtos: fne(params.PROTOS_BRANCH, pipelineDefaults.capsuleSystemTests.branchProtos),
      branchVegatools: fne(params.VEGATOOLS_BRANCH, pipelineDefaults.capsuleSystemTests.branchVegatools),

      systemTestsTestFunction: fne(params.SYSTEM_TESTS_TEST_FUNCTION, pipelineDefaults.capsuleSystemTests.systemTestsTestFunction),
      systemTestsTestMark: fne(params.SYSTEM_TESTS_TEST_MARK, pipelineDefaults.capsuleSystemTests.systemTestsTestMark),
      systemTestsTestDirectory: fne(params.SYSTEM_TESTS_TEST_DIRECTORY, pipelineDefaults.capsuleSystemTests.systemTestsTestDirectory),
      systemTestsDebug: params.SYSTEM_TESTS_DEBUG,
      systemTestsRunTimeout: params.TIMEOUT,
      printNetworkLogs: pipelineDefaults.capsuleSystemTests.printNetworkLogsInJenkinsPipeline.toBoolean(),
    ])
  }
}

/**
 * Example usage
 **/
// pipelineCapsuleSystemTests()
