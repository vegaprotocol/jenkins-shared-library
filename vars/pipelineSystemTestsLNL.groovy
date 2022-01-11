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
                description: 'Git branch, tag or hash of the vegaprotocol/protos repository'),
            string(
                name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.lnl.systemTestsBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/system-tests repository'),
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
            'st': { Map vars ->
                DockerisedVega dockerisedVega = vars.dockerisedVega
                withEnv([
                    "SYSTEM_TESTS_DOCKER_IMAGE_TAG=${dockerisedVega.prefix}",
                ]) {
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
                            withDockerRegistry(vars.dockerCredentials) {
                                sh label: 'build system-tests container', script: '''#!/bin/bash -e
                                    make prepare-test-docker-image
                                '''
                            }
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
            }
        ],
        mainStage: { Map vars ->
            DockerisedVega dockerisedVega = vars.dockerisedVega
            withEnv([
                "SYSTEM_TESTS_DOCKER_IMAGE_TAG=${dockerisedVega.prefix}",
                "DOCKERISED_VEGA_HOME=${dockerisedVega.basedir}",
                "VALIDATOR_NODE_COUNT=${dockerisedVega.validators}",
                "NON_VALIDATOR_NODE_COUNT=${dockerisedVega.nonValidators}",
                "SYSTEM_TESTS_PORTBASE=${dockerisedVega.portbase}",
                "SYSTEM_TESTS_DEBUG=${params.SYSTEM_TESTS_DEBUG}",
                "SYSTEM_TESTS_LNL_STATE=${env.WORKSPACE}/${pipelineDefaults.art.lnl.systemTestsState}",
                "TEST_DIRECTORY=${params.SYSTEM_TESTS_TEST_DIRECTORY}",
                "TEST_FUNCTION=${params.SYSTEM_TESTS_TEST_FUNCTION_CREATE}",
                "VEGATOOLS=${dockerisedVega.vegatoolsScript}",
            ]) {
                stage('Check setup') {
                    sh 'printenv'
                    echo "vars=${vars.inspect()}"
                }
                stage('Wait for bootstrap period to finish') {
                    dockerisedVega.bootstrapWait()
                }
                stage('Run system-tests LNL before restore') {
                    try {
                        dir('system-tests/scripts') {
                            sh label: 'run system-tests', script: '''#!/bin/bash -e
                                make run-tests
                            '''
                        }
                    } finally {
                        String junitReportFile = 'system-tests/build/test-reports/system-test-results.xml'
                        if (fileExists(junitReportFile)) {
                            sh label: 'copy junit report to artifact directory', script: """#!/bin/bash -e
                                cp "${junitReportFile}" "${pipelineDefaults.art.lnl.systemTestsCreateState}"
                            """
                            junit checksName: 'LNL System Tests Create',
                                testResults: pipelineDefaults.art.lnl.systemTestsCreateState
                            archiveArtifacts artifacts: pipelineDefaults.art.lnl.systemTestsCreateState,
                                allowEmptyArchive: true,
                                fingerprint: true
                        }
                        echo "LNL System Tests (before restore) has finished with state: ${currentBuild.result}"
                    }
                }
            }
        },
        afterCheckpointRestoreStage: { Map vars ->
            DockerisedVega dockerisedVega = vars.dockerisedVega
            withEnv([
                "SYSTEM_TESTS_DOCKER_IMAGE_TAG=${dockerisedVega.prefix}",
                "DOCKERISED_VEGA_HOME=${dockerisedVega.basedir}",
                "VALIDATOR_NODE_COUNT=${dockerisedVega.validators}",
                "NON_VALIDATOR_NODE_COUNT=${dockerisedVega.nonValidators}",
                "SYSTEM_TESTS_PORTBASE=${dockerisedVega.portbase}",
                "SYSTEM_TESTS_DEBUG=${params.SYSTEM_TESTS_DEBUG}",
                "SYSTEM_TESTS_LNL_STATE=${env.WORKSPACE}/${pipelineDefaults.art.lnl.systemTestsState}",
                "TEST_DIRECTORY=${params.SYSTEM_TESTS_TEST_DIRECTORY}",
                "TEST_FUNCTION=${params.SYSTEM_TESTS_TEST_FUNCTION_ASSERT}",
                "VEGATOOLS=${dockerisedVega.vegatoolsScript}",
            ]) {
                stage('Check setup') {
                    sh 'printenv'
                    echo "vars=${vars.inspect()}"
                }
                stage('Wait for bootstrap period to finish') {
                    dockerisedVega.bootstrapWait()
                }
                stage('Run system-tests LNL after restore') {
                    try {
                        dir('system-tests/scripts') {
                            sh label: 'run system-tests', script: '''#!/bin/bash -e
                                make run-tests
                            '''
                        }
                    } finally {
                        String junitReportFile = 'system-tests/build/test-reports/system-test-results.xml'
                        if (fileExists(junitReportFile)) {
                            sh label: 'copy junit report to artifact directory', script: """#!/bin/bash -e
                                cp "${junitReportFile}" "${pipelineDefaults.art.lnl.systemTestsAssertState}"
                            """
                            junit checksName: 'LNL System Tests Assert',
                                testResults: pipelineDefaults.art.lnl.systemTestsAssertState
                            archiveArtifacts artifacts: pipelineDefaults.art.lnl.systemTestsAssertState,
                                allowEmptyArchive: true,
                                fingerprint: true
                        }
                        archiveArtifacts artifacts: "${pipelineDefaults.art.lnl.systemTestsState}/**/*",
                            allowEmptyArchive: true,
                            fingerprint: true
                        sh label: 'list all files in SYSTEM_TESTS_LNL_STATE directory', script: """#!/bin/bash -e
                            ls -lah "${pipelineDefaults.art.lnl.systemTestsState}"
                            echo "Full path SYSTEM_TESTS_LNL_STATE=${SYSTEM_TESTS_LNL_STATE}"
                            ls -lah "${SYSTEM_TESTS_LNL_STATE}"
                        """
                        echo "LNL System Tests (after restore) has finished with state: ${currentBuild.result}"
                    }
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
