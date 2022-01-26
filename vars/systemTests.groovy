import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String systemTestsJob = 'system-tests/system-tests'
    Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
    List buildParameters = [
            // Different repos branches
            string(name: 'VEGA_CORE_BRANCH', value: config.vegaCore ?: pipelineDefaults.st.vegaCoreBranch),
            string(name: 'DATA_NODE_BRANCH', value: config.dataNode ?: pipelineDefaults.st.dataNodeBranch),
            string(name: 'VEGAWALLET_BRANCH', value: config.vegawallet ?: pipelineDefaults.st.vegaWalletBranch),
            string(name: 'VEGATOOLS_BRANCH', value: config.vegatools ?: pipelineDefaults.st.vegatoolsBranch),
            string(name: 'DEVOPS_INFRA_BRANCH', value: config.devopsInfra ?: pipelineDefaults.st.devopsInfraBranch),
            string(name: 'PROTOS_BRANCH', value: config.protos ?: pipelineDefaults.st.protosBranch),
            string(name: 'SYSTEM_TESTS_BRANCH', value: config.systemTests ?: pipelineDefaults.st.systemTestsBranch),

            // Limit which tests to run
            string(name: 'SYSTEM_TESTS_TEST_DIRECTORY',
                   value: config.testDirectory ?: pipelineDefaults.st.testDirectory),
            string(name: 'SYSTEM_TESTS_TEST_FUNCTION', value: config.testFunction ?: pipelineDefaults.st.testFunction),
            string(name: 'SYSTEM_TESTS_TEST_MARK', value: config.testMark ?: pipelineDefaults.st.testMark),

            // Network config
            string(name: 'DV_VALIDATOR_NODE_COUNT',
                   value: config.validators ? "${config.validators}" : pipelineDefaults.st.validatorNodeCount),
            string(name: 'DV_NON_VALIDATOR_NODE_COUNT',
                   value: config.nonValidators ? "${config.nonValidators}" : pipelineDefaults.st.nonValidatorNodeCount),
            text(name: 'DV_GENESIS_JSON', value: config.genesis ?: pipelineDefaults.st.genesisJSON),

            // Pipeline config
            string(name: 'JENKINS_AGENT_LABEL', value: config.agent ?: pipelineDefaults.st.agent),
            string(name: 'TIMEOUT', value: config.timeout ? "${config.timeout}" : pipelineDefaults.st.timeout),

            // Debug
            booleanParam(
                name: 'SYSTEM_TESTS_DEBUG',
                value: config.systemTestsDebug ? "${config.systemTestsDebug}".toBoolean() : pipelineDefaults.st.systemTestsDebug
            ),
            string(name: 'DV_TENDERMINT_LOG_LEVEL',
                   value: config.tendermintLogLevel ?: pipelineDefaults.st.tendermintLogLevel),
            string(name: 'DV_VEGA_CORE_LOG_LEVEL',
                   value: config.vegaCoreLogLevel ?: pipelineDefaults.st.vegaCoreLogLevel),
            booleanParam(
                name: 'DV_VEGA_CORE_DLV',
                value: config.vegaCoreDLV ? "${config.vegaCoreDLV}".toBoolean() : pipelineDefaults.st.vegaCoreDLV
            ),
        ]

    echo "Starting System-Tests with parameters: ${buildParameters}"

    RunWrapper st = build(
        job: systemTestsJob,
        propagate: false,  // don't fail yet
        wait: true,
        parameters: buildParameters
    )

    try {
        echo "System-Tests execution pipeline: ${st.absoluteUrl}"

        sh label: 'remove old junit result file', script: """#!/bin/bash -e
            rm -f "${pipelineDefaults.art.systemTestsJunit}"
        """

        copyArtifacts(
            projectName: systemTestsJob,
            selector: specific("${st.number}"),
            fingerprintArtifacts: true,
            filter: pipelineDefaults.art.systemTestsJunit
        )

        junit checksName: 'System Tests',
              testResults: pipelineDefaults.art.systemTestsJunit,
              skipMarkingBuildUnstable: ignoreFailure,
              skipPublishingChecks: ignoreFailure

    } catch (e) {
        echo "Ignoring error in gathering results from downstream build: ${e}"
    }

    // now fail
    if (st.result != 'SUCCESS') {
        if (ignoreFailure) {
            // workaround to:
            // - change status of current stage to not successful
            // - don't change build status, keep it as it was outside of this stage
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
