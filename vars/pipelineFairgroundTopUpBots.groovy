/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

def call() {
    pipeline {
        agent any
        options {
            timeout(time: 15, unit: 'MINUTES')
            timestamps()
            ansiColor('x-term')
        }
        environment {
            CREDENTIALS_ID = 'ssh-vega-network'
        }
        stages {
            stage('Checkout') {
                steps {
                    sh 'printenv'
                    echo "params=${params.inspect()}"
                    dir('devops-infra') {
                        gitClone('devops-infra', params.DEVOPS_INFRA_BRANCH)
                    }
                }
            }
            stage('Fairground: status') {
                steps {
                    veganetSh(
                        credentialsId: env.CREDENTIALS_ID,
                        network: env.NETWORK,
                        command: 'status',
                    )
                }
            }
            stage('Top ups') {
                failFast false
                environment {
                    REMOVE_BOT_WALLETS = "${params.REMOVE_BOT_WALLETS ? "true" : ""}"
                }
                parallel {
                    stage('Liqbot') {
                        steps {
                            veganetSh(
                                credentialsId: env.CREDENTIALS_ID,
                                network: env.NETWORK,
                                command: 'bounce_liqbots',
                            )
                        }
                    }
                    stage('Traderbot') {
                        steps {
                            veganetSh(
                                credentialsId: env.CREDENTIALS_ID,
                                network: env.NETWORK,
                                command: 'bounce_traderbots',
                            )
                        }
                    }
                }
            }
        }
        post {
            always {
                slack.slackSendCIStatus channel: '#env-deploy', name: 'Fairground Top-Up Bots', branch: 'Top-Up'
                cleanWs()
            }
        }
    }
}
