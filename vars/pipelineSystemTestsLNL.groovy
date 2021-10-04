/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import io.vegaprotocol.DockerisedVega

void call() {
    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
    }
    pipelineDockerisedVega.call([
        parameters: [
            string(
                name: 'SYSTEM_TESTS_TEST_DIRECTORY', defaultValue: pipelineDefaults.lnl.testDirectory,
                description: 'Run tests from files in this directory and all sub-directories'),
            string(
                name: 'SYSTEM_TESTS_TEST_FUNCTION_CREATE', defaultValue: pipelineDefaults.lnl.testFunctionCreate,
                description: 'Specify which tests should be run before the network restart. These should change the state of the network.'),
            string(
                name: 'SYSTEM_TESTS_TEST_FUNCTION_ASSERT', defaultValue: pipelineDefaults.lnl.testFunctionAssert,
                description: 'Specify which tests should be run after the network restart. These should validate the network state after resume from checkpoint'),
            string(
                name: 'PROTOS_BRANCH', defaultValue: pipelineDefaults.lnl.protosBranch,
                description: 'Git branch name of the vegaprotocol/protos repository'),
            string(
                name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.lnl.systemTestsBranch,
                description: 'Git branch name of the vegaprotocol/system-tests repository'),
            booleanParam(
                name: 'SYSTEM_TESTS_DEBUG', defaultValue: pipelineDefaults.lnl.systemTestsDebug,
                description: 'Enable debug logs for system-tests execution'),
            text(
                name: 'DV_GENESIS_JSON', defaultValue: pipelineDefaults.lnl.genesisJSON,
                description: 'Tendermint genesis overrides in JSON format'),
        ],
        git: [
            [name: 'system-tests', branch: 'SYSTEM_TESTS_BRANCH'],
            [name: 'protos', branch: 'PROTOS_BRANCH'],
        ],
        prepareStages: [
            'st': {
                stage('General setup') {
                    sh label: 'Create directories', script: """#!/bin/bash -e
                        mkdir -p "\$(dirname ${pipelineDefaults.art.lnl.systemTestsCreateState})"
                        mkdir -p "\$(dirname ${pipelineDefaults.art.lnl.systemTestsAssertState})"
                        mkdir -p "\$(dirname ${pipelineDefaults.art.lnl.checkpointRestore})"
                        mkdir -p "\$(dirname ${pipelineDefaults.art.lnl.checkpointEnd})"
                        mkdir -p "${pipelineDefaults.art.lnl.systemTestsState}"
                    """
                }
                stage('build system-tests docker image') {
                    dir('system-tests/scripts') {
                        sh label: 'build system-tests container', script: '''#!/bin/bash -e
                            make prepare-test-docker-image
                        '''
                    }
                }
                stage('make proto for system-tests') {
                    dir('system-tests/scripts') {
                        sh label: 'make proto', script: '''#!/bin/bash -e
                            make build-test-proto
                        '''
                    }
                }
            }
        ],
        mainStage: { Map vars ->
            DockerisedVega dockerisedVega = vars.dockerisedVega
            withEnv([
                "VALIDATOR_NODE_COUNT=${dockerisedVega.validators}",
                "NON_VALIDATOR_NODE_COUNT=${dockerisedVega.nonValidators}",
                "SYSTEM_TESTS_PORTBASE=${dockerisedVega.portbase}",
                "SYSTEM_TESTS_DEBUG=${params.SYSTEM_TESTS_DEBUG}",
                "SYSTEM_TESTS_LNL_STATE=${env.WORKSPACE}/${pipelineDefaults.art.lnl.systemTestsState}",
                "TEST_DIRECTORY=${params.SYSTEM_TESTS_TEST_DIRECTORY}",
                "TEST_FUNCTION=${params.SYSTEM_TESTS_TEST_FUNCTION_CREATE}",
            ]) {
                stage('Check setup') {
                    sh 'printenv'
                    echo "vars=${vars.inspect()}"
                    dir('system-tests/scripts') {
                        sh label: 'check setup', script: '''#!/bin/bash -e
                            make check
                        '''
                    }
                }
                stage('Run system-tests LNL before restore') {
                    dir('system-tests/scripts') {
                        sh label: 'run system-tests', script: '''#!/bin/bash -e
                            make run-tests || touch ../build/test-reports/system-test-results.xml
                        '''
                    }
                }
                stage('Store junit results') {
                    sh label: 'copy system-tests junit result file to output directory', script: """#!/bin/bash -e
                        cp \
                            system-tests/build/test-reports/system-test-results.xml \
                            "${pipelineDefaults.art.lnl.systemTestsCreateState}"
                    """
                    junit checksName: 'System Tests',
                          testResults: pipelineDefaults.art.lnl.systemTestsCreateState
                    archiveArtifacts artifacts: pipelineDefaults.art.lnl.systemTestsCreateState,
                        allowEmptyArchive: true,
                        fingerprint: true
                }
            }
        },
        afterCheckpointRestoreStage: { Map vars ->
            DockerisedVega dockerisedVega = vars.dockerisedVega
            withEnv([
                "VALIDATOR_NODE_COUNT=${dockerisedVega.validators}",
                "NON_VALIDATOR_NODE_COUNT=${dockerisedVega.nonValidators}",
                "SYSTEM_TESTS_PORTBASE=${dockerisedVega.portbase}",
                "SYSTEM_TESTS_DEBUG=${params.SYSTEM_TESTS_DEBUG}",
                "SYSTEM_TESTS_LNL_STATE=${env.WORKSPACE}/${pipelineDefaults.art.lnl.systemTestsState}",
                "TEST_DIRECTORY=${params.SYSTEM_TESTS_TEST_DIRECTORY}",
                "TEST_FUNCTION=${params.SYSTEM_TESTS_TEST_FUNCTION_ASSERT}",
            ]) {
                stage('Check setup') {
                    sh 'printenv'
                    echo "vars=${vars.inspect()}"
                    dir('system-tests/scripts') {
                        sh label: 'check setup', script: '''#!/bin/bash -e
                            make check
                        '''
                    }
                }
                stage('Run system-tests LNL after restore') {
                    dir('system-tests/scripts') {
                        sh label: 'run system-tests', script: '''#!/bin/bash -e
                            make run-tests || touch ../build/test-reports/system-test-results.xml
                        '''
                    }
                }
                stage('Store junit results') {
                    sh label: 'copy system-tests junit result file to output directory', script: """#!/bin/bash -e
                        cp \
                            system-tests/build/test-reports/system-test-results.xml \
                            "${pipelineDefaults.art.lnl.systemTestsAssertState}"
                    """
                    junit checksName: 'System Tests',
                          testResults: pipelineDefaults.art.lnl.systemTestsAssertState
                    archiveArtifacts artifacts: pipelineDefaults.art.lnl.systemTestsAssertState,
                        allowEmptyArchive: true,
                        fingerprint: true
                    archiveArtifacts artifacts: "${pipelineDefaults.art.lnl.systemTestsState}/**/*",
                        allowEmptyArchive: true,
                        fingerprint: true
                    sh label: 'list all files in SYSTEM_TESTS_LNL_STATE directory', script: """#!/bin/bash -e
                        ls -lah "${pipelineDefaults.art.lnl.systemTestsState}"
                    """
                }
            }
        },
        post: {
            slack.slackSendCIStatus name: 'LNL checkpoints: create and restore',
                channel: '#qa-notify',
                branch: currentBuild.displayName
        }
    ])
}
