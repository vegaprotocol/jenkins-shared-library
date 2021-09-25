import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String systemTestsJob = 'system-tests/system-tests'
    List buildParameters = []

    // String properties
    [
        // Different repos branches
        vegaCore: 'VEGA_CORE_BRANCH',
        dataNode: 'DATA_NODE_BRANCH',
        goWallet: 'GO_WALLET_BRANCH',
        devopsInfra: 'DEVOPS_INFRA_BRANCH',
        protos: 'PROTOS_BRANCH',
        systemTests: 'SYSTEM_TESTS_BRANCH',
        // Limit which tests to run
        testDirectory: 'SYSTEM_TESTS_TEST_DIRECTORY',
        testFunction: 'SYSTEM_TESTS_TEST_FUNCTION',
        // Network config
        validators: 'DV_VALIDATOR_NODE_COUNT',
        nonValidators: 'DV_NON_VALIDATOR_NODE_COUNT',
        // Pipeline config
        agent: 'JENKINS_AGENT_LABEL',
        timeout: 'TIMEOUT',
        // Debug
        tendermintLogLevel: 'DV_TENDERMINT_LOG_LEVEL',
        vegaCoreLogLevel: 'DV_VEGA_CORE_LOG_LEVEL',
    ].each { argName, paramName ->
        if (config.get(argName) != null) {
            buildParameters << string(name: paramName, value: config.get(argName))
        }
    }
    // Other types
    config.genesis != null && buildParameters << text(name: 'DV_GENESIS_JSON', value: config.genesis)
    config.proposals != null && buildParameters << text(name: 'DV_PROPOSALS_JSON', value: config.proposals)
    config.systemTestsDebug != null && buildParameters << booleanParam(name: 'SYSTEM_TESTS_DEBUG', value: config.systemTestsDebug)
    config.vegaCoreDLV != null && buildParameters << booleanParam(name: 'DV_VEGA_CORE_DLV', value: config.vegaCoreDLV)

    echo "Starting System-Tests with parameters: ${buildParameters}"

    RunWrapper st = build(
        job: systemTestsJob,
        propagate: false,  // don't fail yet
        wait: true,
        parameters: buildParameters
    )

    echo "System-Tests execution pipeline: ${st.absoluteUrl}"

    copyArtifacts(
        projectName: systemTestsJob,
        selector: specific("${st.number}"),
        fingerprintArtifacts: true,
        optional: true,
        filter: 'output/junit-report/system-tests.xml',
        target: 'output/junit-report',
    )

    touch 'output/junit-report/system-tests.xml'

    junit checksName: 'System Tests',
          testResults: 'output/junit-report/system-tests.xml',
          allowEmptyResults: true

    archiveArtifacts artifacts: 'output/junit-report/system-tests.xml',
        allowEmptyArchive: true,
        fingerprint: true,
        onlyIfSuccessful: false

    if (st.result != 'SUCCESS') {
        error("System Tests ${st.result}")  // now fail
    }
}
