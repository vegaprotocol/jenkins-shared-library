/* groovylint-disable LineLength */
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
    ])
  ])

  node('system-tests-capsule') {
      capsuleSystemTests([
        branchDevopsInfra: params.DEVOPS_INFRA_BRANCH,
        branchVegaCapsule: params.VEGACAPSULE_BRANCH,
        branchVega: params.VEGA_CORE_BRANCH,
        branchDataNode: params.DATA_NODE_BRANCH,
        branchSystemTests: params.SYSTEM_TESTS_BRANCH,
        branchVegawallet: params.VEGAWALLET_BRANCH,
        branchProtos: params.PROTOS_BRANCH,
        branchVegatools: params.VEGATOOLS_BRANCH,

        systemTestsTestFunction: params.SYSTEM_TESTS_TEST_FUNCTION,
        systemTestsTestMark: params.SYSTEM_TESTS_TEST_MARK,
        systemTestsTestDirectory: params.SYSTEM_TESTS_TEST_DIRECTORY,
        systemTestsDebug: params.SYSTEM_TESTS_DEBUG,
        systemTestsRunTimeout: params.TIMEOUT,

        preapareSteps: {
            // Move it to AMI, will be removed soon
            sh 'sudo apt-get install -y daemonize'
        }
      ])
  }
}

/**
 * Example usage
 **/
// pipelineCapsuleSystemTests()
