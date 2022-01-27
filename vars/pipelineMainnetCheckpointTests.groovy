/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable UnnecessaryGetter */
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
                name: 'NAME', defaultValue: '',
                description: 'Network name - used only to display on Jenkins'),
            string(
                name: 'TIMEOUT', defaultValue: '200',
                description: 'Timeout after which the network will be stopped. Default 200min'),
            string(
                name: 'JENKINS_AGENT_LABEL', defaultValue: 'private-network',
                description: 'Specify Jenkins machine on which to run this pipeline'),
            string(
                name: 'SYSTEM_TESTS_TEST_FUNCTION', defaultValue: 'test_checkpoint_loaded',
                description: 'Run only a tests with a specified function name. This is actually a "pytest -k $TEST_FUNCTION_NAME" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html'),
            string(
                name: 'SYSTEM_TESTS_TEST_DIRECTORY', defaultValue: pipelineDefaults.lnl.testDirectory,
                description: 'Run tests from files in this directory and all sub-directories'),
            string(
                name: 'SYSTEM_TESTS_TEST_MARK', defaultValue: '',
                description: 'Run tests only with the following pytest marks'),
            string(
                name: 'SYSTEM_TESTS_TEST_MARK', defaultValue: '',
                description: 'Run tests only with the following pytest marks'),
            booleanParam(
                name: 'DV_MAINNET', defaultValue: true,
                description: 'Run network as Mainnet. LEAVE THIS SET TO TRUE, or else undefined behaviour.'),
            text(
                name: 'DV_GENESIS_JSON', defaultValue: pipelineDefaults.st.mainnetGenesis,
                description: '''Tendermint genesis overrides in JSON format, or path to a file.
            '''),
            string(
                name: 'PROTOS_BRANCH', defaultValue: pipelineDefaults.st.protosBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/protos repository'),
            string(
                name: 'SYSTEM_TESTS_BRANCH', defaultValue: pipelineDefaults.st.systemTestsBranch,
                description: 'Git branch, tag or hash of the vegaprotocol/system-tests repository'),
            booleanParam(
                name: 'SYSTEM_TESTS_DEBUG', defaultValue: pipelineDefaults.st.systemTestsDebug,
                description: 'Enable debug logs for system-tests execution'),
        ],
        git: [
            [name: 'system-tests', branch: 'SYSTEM_TESTS_BRANCH'],
            [name: 'protos', branch: 'PROTOS_BRANCH'],
        ],
        prepareStages: [
            'net': { Map vars ->
                stage('Set name') {
                    String whoStarted = currentBuild.getBuildCauses()[0].shortDescription - 'Started by user '
                    String networkName = params.NAME ?: whoStarted
                    currentBuild.displayName = "${networkName} #${currentBuild.id}"
                }
            },
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
            String ip = vars.jenkinsAgentPublicIP
            Map<String,String> usefulLinks = dockerisedVega.getUsefulLinks(ip)
            Map<String,Map<String,String>> endpointInfo = dockerisedVega.getEndpointInformation(ip)
            List parameters = []

            usefulLinks.eachWithIndex { linkName, linkURL, index ->
                if (index == 0) {
                    parameters << booleanParam(description: 'Useful links', name: "[${linkName}]: ${linkURL}")
                } else {
                    parameters << booleanParam(name: "[${linkName}]: ${linkURL}")
                }
            }

            endpointInfo.each { machine, endpoints ->
                endpoints.eachWithIndex { endpointType, endpoint, index ->
                    if (index == 0) {
                        parameters << booleanParam(description: machine, name: "[${endpointType}]: ${endpoint}")
                    } else {
                        parameters << booleanParam(name: "[${endpointType}]: ${endpoint}")
                    }
                }
            }

            withEnv([
                "SYSTEM_TESTS_DOCKER_IMAGE_TAG=${dockerisedVega.prefix}",
                "DOCKERISED_VEGA_HOME=${dockerisedVega.homedir}",
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
                stage('Start tests and wait for checkpoint') {
                    parallel ([
                        'Wait for checkpoint and take new checkpoint file' : {
                            dockerisedVega.waitForNextCheckpoint()
                            dockerisedVega.saveLatestCheckpointToFile("system-tests/LNL/after_checkpoint_load.json")
                        },
                        'Run system-tests' : {
                            sh label: 'Copy checkpoint file to test dir for comparison', script: """#!/bin/bash -e
                                        cp "${pipelineDefaults.art.resumeCheckpoint}" system-tests/LNL
                                    """
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
                    ])
                }
            }

        }
    ])
}
