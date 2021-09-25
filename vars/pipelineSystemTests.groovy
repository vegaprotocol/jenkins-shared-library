/* groovylint-disable MethodSize */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

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
                name: 'PROTOS_BRANCH', defaultValue: pipelineDefaults.st.protosBranch,
                description: 'Git branch name of the vegaprotocol/protos repository'),
            string(
                name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.st.systemTestsBranch,
                description: 'Git branch name of the vegaprotocol/system-tests repository'),
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
            'st': {
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
            withEnv([
                "VALIDATOR_NODE_COUNT=${vars.params.DV_VALIDATOR_NODE_COUNT}",
                "NON_VALIDATOR_NODE_COUNT=${vars.params.DV_NON_VALIDATOR_NODE_COUNT}",
                "TEST_FUNCTION=${vars.params.SYSTEM_TESTS_TEST_FUNCTION}",
                "TEST_DIRECTORY=${vars.params.SYSTEM_TESTS_TEST_DIRECTORY}",
                "SYSTEM_TESTS_PORTBASE=${vars.portbase}",
                "SYSTEM_TESTS_DEBUG=${vars.params.SYSTEM_TESTS_DEBUG}",
            ]) {
                stage('Check setup') {
                    echo "vars=${vars.inspect()}"
                    dir('system-tests/scripts') {
                        sh label: 'check setup', script: '''#!/bin/bash -e
                            make check
                        '''
                    }
                }
                stage('Run system-tests') {
                    dir('system-tests/scripts') {
                        sh label: 'run system-tests', script: '''#!/bin/bash -e
                            make run-tests || touch ../build/test-reports/system-test-results.xml
                        '''
                    }
                }
                stage('Store junit results') {
                    sh label: 'copy system-tests junit result file to output directory', script: '''#!/bin/bash -e
                        mkdir -p output/junit-report
                        cp \
                            system-tests/build/test-reports/system-test-results.xml \
                            output/junit-report/system-tests.xml
                    '''
                    junit checksName: 'System Tests',
                          testResults: 'output/junit-report/system-tests.xml'
                    archiveArtifacts artifacts: 'output/junit-report/system-tests.xml',
                        allowEmptyArchive: true,
                        fingerprint: true
                }
            }
        },
        post: {
            slack.slackSendCIStatus name: 'System Tests',
                channel: '#qa-notify',
                branch: currentBuild.displayName
            stage('Container logs') {
                dir('system-tests/scripts') {
                    sh label: 'print logs from all the containers', script: '''#!/bin/bash -e
                        make logs
                    '''
                }
            }
        }
    ])
}
