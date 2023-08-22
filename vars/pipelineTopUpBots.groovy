def call() {
    pipeline {
        agent {
            label 's-4vcpu-8gb'
        }
        options {
            timeout(time: 15, unit: 'MINUTES')
            timestamps()
            ansiColor('xterm')
        }
        environment {
            CREDENTIALS_ID = 'ssh-vega-network'
            GOBIN = "${env.WORKSPACE}/gobin"
        }
        stages {
            stage('Prepare') {
                steps {
                    script {
                        vegautils.commonCleanup()
                    }
                }
            }
            stage('Checkout') {
                steps {
                    sh 'printenv'
                    echo "params=${params.inspect()}"
                    gitClone(
                        vegaUrl: 'devops-infra',
                        directory:'devops-infra',
                        branch: params.DEVOPS_INFRA_BRANCH,
                    )
                }
            }
            stage('Network status') {
                when {
                    expression { env.CHECK_NETWORK_STATUS == 'true' }
                }
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
                script {
                    slack.slackSendCIStatus channel: '#env-deploy', name: env.JOB_NAME, branch: 'Top-Up'
                }
                cleanWs()
            }
        }
    }
}
