/* groovylint-disable DuplicateNumberLiteral, DuplicateStringLiteral, NestedBlockDepth */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call() {
    int parallelWorkers = params.PARALLEL_WORKERS as int ?: 1
    String testFunction = params.TEST_FUNCTION ?: ''
    String logLevel = params.LOG_LEVEL ?: 'INFO'
    String jenkinsAgentIP
    String monitoringDashboardURL
    pipeline {
        agent {
            label params.NODE_LABEL
        }
        options {
            skipDefaultCheckout()
            timeout(time: params.TIMEOUT, unit: 'MINUTES')
            timestamps()
            ansiColor('xterm')
        }
        environment {
            CGO_ENABLED = 0
            GO111MODULE = 'on'
            DOCKER_IMAGE_NAME_LOCAL = 'vega_sim_test:latest'
            PARALLEL_WORKERS = "${parallelWorkers}"
            TEST_FUNCTION = "${testFunction}"
            LOG_LEVEL = "${logLevel}"
            GOBIN = "${env.WORKSPACE}/gobin"
            PATH = "${env.GOBIN}:${env.PATH}"
        }

        stages {
            stage('CI Config') {
                steps {
                    script {
                        // init global variables
                        monitoringDashboardURL = jenkinsutils.getMonitoringDashboardURL()
                        jenkinsAgentIP = agent.getPublicIP()
                        echo "Jenkins Agent IP: ${jenkinsAgentIP}"
                        echo "Monitoring Dahsboard: ${monitoringDashboardURL}"
                        // set job Title and Description
                        String prefixDescription = jenkinsutils.getNicePrefixForJobDescription()
                        currentBuild.displayName = "#${currentBuild.id} ${prefixDescription} [${env.NODE_NAME.take(12)}]"
                        currentBuild.description = "Monitoring: ${monitoringDashboardURL}, Jenkins Agent IP: ${jenkinsAgentIP} [${env.NODE_NAME}]"
                        // Cleanup
                        vegautils.commonCleanup()
                        // Setup grafana-agent
                        grafanaAgent.configure("market-sim", [:])
                        grafanaAgent.restart()
                    }
                    sh 'printenv'
                    echo "params=${params.inspect()}"
                }
            }

            stage('INFO') {
                steps {
                    // Print Info only, do not execute anythig
                    echo "Jenkins Agent IP: ${jenkinsAgentIP}"
                    echo "Jenkins Agent name: ${env.NODE_NAME}"
                    echo "Monitoring Dahsboard: ${monitoringDashboardURL}"
                }
            }

            stage('Clone system-tests-nbc'){
                options { retry(3) }
                steps {
                    checkout(
                        [$class: 'GitSCM', branches: [[name: "${params.SYSTEM_TESTS_NBC_BRANCH}" ]],
                        userRemoteConfigs: [
                            [
                                credentialsId: 'vega-ci-bot',
                                url: 'git@github.com:vegaprotocol/system-tests-nbc.git'
                            ]
                        ]]
                    )
                }
            }
            stage('Clone vega'){
                options { retry(3) }
                steps {
                    dir('extern/vega') {
                        checkout(
                            [$class: 'GitSCM', branches: [[name: "${params.VEGA_VERSION}" ]],
                            userRemoteConfigs: [
                                [
                                    credentialsId: 'vega-ci-bot',
                                    url: "git@github.com:${params.ORIGIN_REPO}.git"
                                ]
                            ]]
                        )
                    }
                }
            }
            stage('Clone vegacapsule'){
                options { retry(3) }
                steps {
                    dir('extern/vegacapsule') {
                        checkout(
                            [$class: 'GitSCM', branches: [[name: "${params.VEGACAPSULE_VERSION}" ]],
                            userRemoteConfigs: [
                                [
                                    credentialsId: 'vega-ci-bot',
                                    url: 'git@github.com:vegaprotocol/vegacapsule.git'
                                ]
                            ]]
                        )
                    }
                }
            }
            stage('Poetry install deps') {
                options { retry(3) }
                steps {
                    sh label: 'poetry install', script: '''
                        poetry install
                    '''
                }
            }
            stage('Build Binaries') {
                options { retry(3) }
                steps {
                    sh label: 'Build binaries', script: '''
                        make build_deps
                    '''
                    sh label: 'echo stuff', script: '''
                        ls -lah ./vega_sim/bin
                    '''
                }
            }
            stage('Build Protos') {
                options { retry(3) }
                steps {
                    dir('extern/vega') {
                        sh 'printenv'
                        sh './script/gettools.sh'
                    }
                    sh label: 'build proto', script: '''
                        make build_proto
                    '''
                    sh label: 'list built proto files', script: '''
                        ls -lah ./vega_sim/proto
                    '''
                }
            }
            stage('Tests') {
                parallel {
                    stage('Spam Tests') {
                        steps {
                            /* groovylint-disable-next-line GStringExpressionWithinString */
                            sh label: 'Run Spam Tests', script: '''
                                poetry run scripts/run-spam-test.sh ${BUILD_NUMBER}
                            '''
                        }
                        post {
                            always {
                                junit checksName: 'Spam Tests results',
                                    testResults: 'test_logs/*-spam/spam-test-results.xml'
                            }
                        }
                    }
                    // stage('Integration Tests') {
                    //     when {
                    //         expression {
                    //             params.RUN_LEARNING == false
                    //         }
                    //     }
                    //     steps {
                    //         /* groovylint-disable-next-line GStringExpressionWithinString */
                    //         sh label: 'Run Integration Tests', script: '''
                    //             poetry run scripts/run-integration-test.sh ${BUILD_NUMBER}
                    //         '''
                    //     }
                    //     post {
                    //         always {
                    //             junit checksName: 'Integration Tests results',
                    //                 testResults: 'test_logs/*-integration/integration-test-results.xml'
                    //         }
                    //     }
                    // }
                    // stage('Notebook Tests') {
                    //     when {
                    //         expression {
                    //             params.RUN_EXTRA_TESTS
                    //         }
                    //     }
                    //     steps {
                    //         sh label: 'Example Notebook Tests', script: '''
                    //             scripts/run-docker-example-notebook-test.sh
                    //         '''
                    //     }
                    //     post {
                    //         always {
                    //             junit checksName: 'Notebook Tests results',
                    //                 testResults: 'test_logs/*-notebook/notebook-test-results.xml'
                    //         }
                    //     }
                    // }
                    // stage('RL Tests') {
                    //     when {
                    //         expression {
                    //             params.RUN_LEARNING == true
                    //         }
                    //     }
                    //     steps {
                    //         sh label: 'Reinforcement Learning Test', script: '''
                    //             poetry run scripts/run-learning-test.sh ${NUM_RL_ITERATIONS}
                    //         '''
                    //     }
                    // }
                    // stage('Fuzz Tests') {
                    //     when {
                    //         expression {
                    //             params.RUN_LEARNING == true
                    //         }
                    //     }
                    //     steps {
                    //         /* groovylint-disable-next-line GStringExpressionWithinString */
                    //         sh label: 'Fuzz Test', script: '''
                    //             poetry run scripts/run-fuzz-test.sh --steps ${NUM_FUZZ_STEPS} --core-metrics-port 2102 --data-node-metrics-port 2123
                    //         '''
                    //     }
                    //     post {
                    //         success {
                    //             archiveArtifacts artifacts: 'fuzz_plots/*.jpg, fuzz_plots/*.html, fuzz_plots/*.csv'
                    //         }
                    //     }
                    // }
                    // stage('Generate Plots') {
                    //     when {
                    //         expression {
                    //             params.RUN_LEARNING == false
                    //         }
                    //     }
                    //     steps {
                    //         sh label: 'Market Behaviour Plots', script: '''
                    //             poetry run scripts/run-plot-gen.sh
                    //         '''
                    //     }
                    //     post {
                    //         success {
                    //             archiveArtifacts artifacts: 'run.jpg'
                    //         }
                    //     }
                    // }
                }
                // TODO: Print logs files from the /test-logs/*.test.log in case of failure
                //       This is required because by default logs are not printed when the
                //       pytests are running in parallel(ref: pytest -n, pytest-xdist)
            }
        } // end: stages
        post {
            always {
                catchError {
                    script {
                        grafanaAgent.stop()
                        grafanaAgent.cleanup()
                    }
                }
                catchError {
                    // Jenkins does not allow to archive artifacts outside of the workspace
                    script {
                        sh 'mkdir -p ./network_home'
                        sh 'cp -r /tmp/vega-sim* ./network_home/'
                    }
                    archiveArtifacts(artifacts: [
                        'network_home/**/*.out',
                        'network_home/**/*.err',
                        'network_home/**/**/replay',
                    ].join(','), allowEmptyArchive: true)
                    script {
                        sh 'sudo rm -rf /tmp/vega-sim*'
                    }
                }
                catchError {
                    archiveArtifacts(artifacts: 'test_logs/**/*.test.log', allowEmptyArchive: true)
                }

                sendSlackMessage()
                retry(3) {
                    cleanWs()
                }
            }
        }
    } // end: pipeline
} // end: call

void sendSlackMessage() {
    String slackChannel = '#system-tests-nbc-notify'
    // if (params.BRANCH_RUN == true) {
    //     slackChannel = '#vega-market-sim-branch-notify'
    // }
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.id

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    String msgTitle = 'System Tests NBC'
    if (params.RUN_LEARNING == true) {
        msgTitle = 'System Tests NBC - Nightly Long Tests'
    }

    if (currentResult == 'SUCCESS') {
        msg = ":large_green_circle: ${msgTitle} <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: ${msgTitle} aborted <${jobURL}|${jobName}>"
        color = '#000000'
    } else {
        msg = ":red_circle: ${msgTitle} <${jobURL}|${jobName}>"
        color = 'danger'
    }

    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        msg += " for <${upBuild.absoluteUrl}|${upBuild.fullProjectName} #${upBuild.id}>"
    }

    msg += " (${duration})"

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
