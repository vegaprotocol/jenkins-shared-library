void call() {
    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: params.TIMEOUT, unit: 'MINUTES')
            timestamps()
        }
        environment {
            CGO_ENABLED = 0
            GO111MODULE = 'on'
            DOCKER_IMAGE_NAME_LOCAL = 'vega_sim_test:latest'
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                }
            }
            stage('Clone vega-market-sim'){
                options { retry(3) }
                steps {
                    checkout(
                        [$class: 'GitSCM', branches: [[name: "${params.VEGA_MARKET_SIM_BRANCH}" ]], 
                        userRemoteConfigs: [[credentialsId: 'vega-ci-bot', url: 'git@github.com:vegaprotocol/vega-market-sim.git']]]
                    )
                }
            }
            stage('Clone vega'){
                options { retry(3) }
                steps {
                    dir('extern/vega') {
                        checkout(
                            [$class: 'GitSCM', branches: [[name: "${params.VEGA_VERSION}" ]], 
                            userRemoteConfigs: [[credentialsId: 'vega-ci-bot', url: 'git@github.com:vegaprotocol/vega.git']]]
                        )
                    }
                }
            }
            stage('Build Docker Image') {
                options { retry(3) }
                steps {
                    sh label: 'Build docker image', script: '''
                        docker build --tag="${DOCKER_IMAGE_NAME_LOCAL}" -t vegasim_test .
                    '''
                }
            }
            stage('Tests') {
                parallel {
                    stage('Integration Tests') {
                        steps {
                            sh label: 'Run Integration Tests', script: '''
                                scripts/run-docker-integration-test.sh ${BUILD_NUMBER}
                            '''
                        }
                    }
                    stage('Notebook Tests') {
                        steps {
                            sh label: 'Example Notebook Tests', script: '''
                                scripts/run-docker-example-notebook-test.sh
                            '''
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: 'test_logs/**/*.out'
                    }
                }
            }
        } // end: stages
        post {
            always {
                sendSlackMessage()
                retry(3) {
                    cleanWs()
                    sh label: 'Clean docker images', script: '''#!/bin/bash -e
                        [ -z "$(docker images -q "${DOCKER_IMAGE_NAME_LOCAL}")" ] || docker rmi "${DOCKER_IMAGE_NAME_LOCAL}"
                    '''
                }
            }
        }
    } // end: pipeline
} // end: call


void sendSlackMessage() {
    String slackChannel = '#vega-market-sim-notify'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    if (currentResult == 'SUCCESS') {
        msg = ":large_green_circle: Approbation <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: Approbation aborted <${jobURL}|${jobName}>"
        color = '#000000'
    } else {
        msg = ":red_circle: Approbation <${jobURL}|${jobName}>"
        color = 'danger'
    }

    msg += " (${duration})"

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
