/* groovylint-disable DuplicateNumberLiteral, DuplicateStringLiteral, NestedBlockDepth */
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call() {
    if (currentBuild.upstreamBuilds) {
        RunWrapper upBuild = currentBuild.upstreamBuilds[0]
        currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
    }
    int parallelWorkers = params.PARALLEL_WORKERS as int ?: 5
    String testFunction = params.TEST_FUNCTION ?: ''

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
        }
        stages {
            stage('CI Config') {
                steps {
                    sh 'printenv'
                    echo "params=${params.inspect()}"
                }
            }
            stage('Clone vega-market-sim'){
                options { retry(3) }
                steps {
                    checkout(
                        [$class: 'GitSCM', branches: [[name: "${params.VEGA_MARKET_SIM_BRANCH}" ]],
                        userRemoteConfigs: [
                            [
                                credentialsId: 'vega-ci-bot', 
                                url: 'git@github.com:vegaprotocol/vega-market-sim.git'
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
            stage('Build Binaries') {
                options { retry(3) }
                when {
                    expression {
                        params.RUN_LEARNING == false
                    }
                }
                steps {
                    sh label: 'Build binaries', script: '''
                        sudo apt-get update \
                            && sudo apt-get install -y nvidia-cuda-toolkit nvidia-cuda-toolkit-gcc

                        make build_deps \
                            && poetry install -E learning \
                            && poetry run python -m pip install "transformers[torch]" 
                    '''
                }
            }
            stage('Build Learning Image') {
                options { retry(3) }
                when {
                    expression {
                        params.RUN_LEARNING == true
                    }
                }
                steps {
                    sh label: 'Build docker image', script: '''
                        scripts/build-docker-learning.sh
                    '''
                }
            }
            stage('Tests') {
                parallel {
                    stage('Integration Tests') {
                        when {
                            expression {
                                params.RUN_LEARNING == false
                            }
                        }
                        steps {
                            /* groovylint-disable-next-line GStringExpressionWithinString */
                            sh label: 'Run Integration Tests', script: '''
                                poetry run scripts/run-integration-test.sh ${BUILD_NUMBER}
                            '''
                        }
                        post {
                            always {
                                junit checksName: 'Integration Tests results',
                                    testResults: 'test_logs/*-integration/integration-test-results.xml'
                            }
                        }
                    }
                    stage('Notebook Tests') {
                        when {
                            expression {
                                params.RUN_EXTRA_TESTS
                            }
                        }
                        steps {
                            sh label: 'Example Notebook Tests', script: '''
                                scripts/run-docker-example-notebook-test.sh
                            '''
                        }
                        post {
                            always {
                                junit checksName: 'Notebook Tests results',
                                    testResults: 'test_logs/*-notebook/notebook-test-results.xml'
                            }
                        }
                    }
                    stage('RL Tests') {
                        when {
                            expression {
                                params.RUN_LEARNING == true
                            }
                        }
                        steps {
                            sh label: 'Reinforcement Learning Test', script: '''
                                scripts/run-docker-learning.sh ${NUM_RL_ITERATIONS}
                            '''
                        }
                    }
                    stage('Fuzz Tests') {
                        when {
                            expression {
                                params.RUN_LEARNING == true
                            }
                        }
                        steps {
                            /* groovylint-disable-next-line GStringExpressionWithinString */
                            sh label: 'Fuzz Test', script: '''
                                poetry run scripts/run-fuzz-test.sh ${NUM_FUZZ_STEPS}
                            '''
                        }
                        post {
                            success {
                                archiveArtifacts artifacts: '*.jpg, *.html, *.csv'
                            }
                        }
                    }
                    stage('Generate Plots') {
                        when {
                            expression {
                                params.RUN_LEARNING == false
                            }
                        }
                        steps {
                            sh label: 'Market Behaviour Plots', script: '''
                                poetry run scripts/run-plot-gen.sh
                            '''
                        }
                        post {
                            success {
                                archiveArtifacts artifacts: 'run.jpg'
                            }
                        }
                    }
                }
            }
        } // end: stages
        post {
            always {
                archiveArtifacts artifacts: [
                    '/tmp/vega-sim-*/**/*.out',
                    '/tmp/vega-sim-*/**/*.err',
                    '/tmp/vega-sim-*/**/replay',
                ].join(',')
                archiveArtifacts artifacts: 'test_logs/**/*.test.log', allowEmptyArchive: true

                sendSlackMessage()
                retry(3) {
                    cleanWs()
                }
            }
        }
    } // end: pipeline
} // end: call

void sendSlackMessage() {
    String slackChannel = '#vega-market-sim-notify'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.id

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    String msgTitle = 'Vega Market Sim'
    if (params.RUN_LEARNING == true) {
        msgTitle = 'Vega Market Sim - Nightly Long Tests'
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
