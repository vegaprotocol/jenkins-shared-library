/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable MethodSize */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call(Map config = [:]) {
    String systemTestsLNLJob = 'system-tests/LNL-create-restore'
    Boolean ignoreFailure = config.ignoreFailure ? "${config.ignoreFailure}".toBoolean() : false
    List buildParameters = [
            // Different repos branches
            string(name: 'VEGA_CORE_BRANCH', value: config.vegaCore ?: pipelineDefaults.lnl.vegaCoreBranch),
            string(name: 'DATA_NODE_BRANCH', value: config.dataNode ?: pipelineDefaults.lnl.dataNodeBranch),
            string(name: 'VEGAWALLET_BRANCH', value: config.vegawallet ?: pipelineDefaults.lnl.vegaWalletBranch),
            string(name: 'ETHEREUM_EVENT_FORWARDER_BRANCH',
                   value: config.ethereumEventForwarder ?: pipelineDefaults.lnl.ethereumEventForwarderBranch),
            string(name: 'VEGATOOLS_BRANCH', value: config.vegatools ?: pipelineDefaults.lnl.vegatoolsBranch),
            string(name: 'DEVOPS_INFRA_BRANCH', value: config.devopsInfra ?: pipelineDefaults.lnl.devopsInfraBranch),
            string(name: 'PROTOS_BRANCH', value: config.protos ?: pipelineDefaults.lnl.protosBranch),
            string(name: 'SYSTEM_TESTS_BRANCH', value: config.systemTests ?: pipelineDefaults.lnl.systemTestsBranch),

            // Limit which tests to run
            string(name: 'SYSTEM_TESTS_TEST_DIRECTORY',
                   value: config.testDirectory ?: pipelineDefaults.lnl.testDirectory),
            string(name: 'SYSTEM_TESTS_TEST_FUNCTION_CREATE',
                    value: config.testFunctionCreate ?: pipelineDefaults.lnl.testFunctionCreate),
            string(name: 'SYSTEM_TESTS_TEST_FUNCTION_ASSERT',
                    value: config.testFunctionAssert ?: pipelineDefaults.lnl.testFunctionAssert),

            // Network config
            string(name: 'DV_VALIDATOR_NODE_COUNT',
                   value: config.validators ? "${config.validators}" : pipelineDefaults.lnl.validatorNodeCount),
            string(name: 'DV_NON_VALIDATOR_NODE_COUNT',
                   value: config.nonValidators ? "${config.nonValidators}" : pipelineDefaults.lnl.nonValidatorNodeCount),
            text(name: 'DV_GENESIS_JSON', value: config.genesis ?: pipelineDefaults.lnl.genesisJSON),
            text(name: 'DV_PROPOSALS_JSON', value: config.proposals ?: pipelineDefaults.lnl.proposalsJSON),

            // Pipeline config
            string(name: 'JENKINS_AGENT_LABEL', value: config.agent ?: pipelineDefaults.lnl.agent),
            string(name: 'TIMEOUT', value: config.timeout ? "${config.timeout}" : pipelineDefaults.lnl.timeout),

            // Debug
            booleanParam(
                name: 'SYSTEM_TESTS_DEBUG',
                value: config.systemTestsDebug ? "${config.systemTestsDebug}".toBoolean() : pipelineDefaults.lnl.systemTestsDebug
            ),
            string(name: 'DV_TENDERMINT_LOG_LEVEL',
                   value: config.tendermintLogLevel ?: pipelineDefaults.lnl.tendermintLogLevel),
            string(name: 'DV_VEGA_CORE_LOG_LEVEL',
                   value: config.vegaCoreLogLevel ?: pipelineDefaults.lnl.vegaCoreLogLevel),
            booleanParam(
                name: 'DV_VEGA_CORE_DLV',
                value: config.vegaCoreDLV ? "${config.vegaCoreDLV}".toBoolean() : pipelineDefaults.lnl.vegaCoreDLV
            ),
        ]

    echo "Starting System-Tests with parameters: ${buildParameters}"

    RunWrapper st = build(
        job: systemTestsLNLJob,
        propagate: false,  // don't fail yet
        wait: true,
        parameters: buildParameters
    )

    try {
        echo "LNL System-Tests execution pipeline: ${st.absoluteUrl}"

        sh label: 'remove old junit result file', script: """#!/bin/bash -e
            rm -f "${pipelineDefaults.art.lnl.systemTestsCreateState}"
            rm -f "${pipelineDefaults.art.lnl.systemTestsAssertState}"
        """

        copyArtifacts(
            projectName: systemTestsLNLJob,
            selector: specific("${st.number}"),
            fingerprintArtifacts: true,
            filter: pipelineDefaults.art.lnl.systemTestsCreateState
        )

        junit checksName: 'LNL System Tests Create',
              testResults: pipelineDefaults.art.lnl.systemTestsCreateState,
              skipMarkingBuildUnstable: ignoreFailure,
              skipPublishingChecks: ignoreFailure

        copyArtifacts(
            projectName: systemTestsLNLJob,
            selector: specific("${st.number}"),
            fingerprintArtifacts: true,
            filter: pipelineDefaults.art.lnl.systemTestsAssertState
        )

        junit checksName: 'LNL System Tests Assert',
              testResults: pipelineDefaults.art.lnl.systemTestsAssertState,
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
            catchError(message: 'LNL System Tests Failed', buildResult: null, stageResult: st.result) {
                error("Ignore failure and keep job green, but mark stage as ${st.result}")
            }
        } else {
            if (st.result == 'UNSTABLE') {
                unstable('UNSTABLE - LNL System Tests')
            } else if (st.result == 'ABORTED') {
                currentBuild.result = 'ABORTED'
                error('ABORTED - LNL System Tests')
            } else {
                error("${st.result} - LNL System Tests")
            }
        }
    }
}
