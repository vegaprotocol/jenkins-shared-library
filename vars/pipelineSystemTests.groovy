/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import io.vegaprotocol.DockerisedVega

void call() {
    echo "buildCauses=${currentBuild.buildCauses}"
    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
    }
    pipelineDockerisedVega.call([
        parameters: [
            string(
                name: 'SYSTEM_TESTS_TEST_DIRECTORY', defaultValue: pipelineDefaults.st.testDirectory,
                description: 'Run tests from files in this directory and all sub-directories'),
            string(
                name: 'SYSTEM_TESTS_TEST_FUNCTION', defaultValue: pipelineDefaults.st.testFunction,
                description: 'Run only a tests with a specified function name. This is actually a "pytest -k $TEST_FUNCTION_NAME" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html'),
            string(
                name: 'SYSTEM_TESTS_TEST_MARK', defaultValue: pipelineDefaults.st.testMark,
                description: 'Run only a tests with the specified mark(s). This is actually a "pytest -m $TEST_MARK" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html'),
            string(
                name: 'PROTOS_BRANCH', defaultValue: pipelineDefaults.st.protosBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/protos repository'),
            string(
                name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.st.systemTestsBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/system-tests repository'),
            booleanParam(
                name: 'SYSTEM_TESTS_DEBUG', defaultValue: pipelineDefaults.st.systemTestsDebug,
                description: 'Enable debug logs for system-tests execution'),
            text(
                name: 'DV_GENESIS_JSON', defaultValue: pipelineDefaults.st.genesisJSON,
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
                            mkdir -p "\$(dirname ${pipelineDefaults.art.systemTestsJunit})"
                            mkdir -p "${pipelineDefaults.art.systemTestsState}"
                            mkdir -p "${pipelineDefaults.art.systemTestsLogs}"
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
                "TEST_FUNCTION=${params.SYSTEM_TESTS_TEST_FUNCTION}",
                "TEST_DIRECTORY=${params.SYSTEM_TESTS_TEST_DIRECTORY}",
                "TEST_MARK=${params.SYSTEM_TESTS_TEST_MARK}",
                "SYSTEM_TESTS_PORTBASE=${dockerisedVega.portbase}",
                "SYSTEM_TESTS_DEBUG=${params.SYSTEM_TESTS_DEBUG}",
                "SYSTEM_TESTS_LNL_STATE=${env.WORKSPACE}/${pipelineDefaults.art.systemTestsState}",
                "SYSTEM_TESTS_LOG_OUTPUT=${env.WORKSPACE}/${pipelineDefaults.art.systemTestsLogs}",
                "VEGATOOLS=${dockerisedVega.vegatoolsScript}",
            ]) {
                stage('Check setup') {
                    sh 'printenv'
                    echo "vars=${vars.inspect()}"
                }
                stage('Wait for bootstrap period to finish') {
                    dockerisedVega.bootstrapWait()
                }
                stage('Run system-tests') {
                    try {
                        dir('system-tests/scripts') {
                            sh label: 'run system-tests', script: '''#!/bin/bash -e
                                make run-tests
                            '''
                        }
                    } finally {
                        String junitReportFile = 'system-tests/build/test-reports/system-test-results.xml'
                        String testLogDirectory = 'system-tests/test_logs/'
                        if (fileExists(junitReportFile)) {
                            sh label: 'copy junit report to artifact directory', script: """#!/bin/bash -e
                                cp "${junitReportFile}" "${pipelineDefaults.art.systemTestsJunit}"
                            """
                            junit checksName: 'System Tests',
                                testResults: pipelineDefaults.art.systemTestsJunit
                            archiveArtifacts artifacts: pipelineDefaults.art.systemTestsJunit,
                                allowEmptyArchive: true,
                                fingerprint: true
                        }
                        archiveArtifacts artifacts: "${pipelineDefaults.art.systemTestsState}/**/*",
                            allowEmptyArchive: true,
                            fingerprint: true
                        if (fileExists(testLogDirectory)) {
                            sh label: 'copy test logs to artifact directory', script: """#!/bin/bash -e
                                cp -r "${testLogDirectory}" "${pipelineDefaults.art.systemTestsLogs}"
                            """
                            archiveArtifacts artifacts: "${pipelineDefaults.art.systemTestsLogs}/**/*",
                                allowEmptyArchive: true,
                                fingerprint: true
                        }
                        echo "System Tests has finished with state: ${currentBuild.result}"
                    }
                }
            }
        },
        post: {
            slack.slackSendCIStatus name: 'System Tests',
                channel: '#qa-notify',
                branch: currentBuild.displayName
        }
    ])
}
