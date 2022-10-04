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
                    gitClone(
                        vegaUrl: 'devopstools',
                        directory:'devopstools',
                        branch: params.DEVOPSTOOLS_BRANCH,
                    )
                    dir ('devopstools') {
                        sh 'go mod download'
                    }
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
                            dir ('devopstools') {
                                // TODO - restart bots?
                                sh "go run main.go topup liqbot --network fairground --github-token token"
                            }
                        }
                    }
                    stage('Traderbot') {
                        steps {
                            dir ('devopstools') {
                                // TODO - restart bots?
                                sh "go run main.go topup traderbot --network fairground --github-token token"
                            }
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
