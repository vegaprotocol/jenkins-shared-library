def call() {
    pipeline {
        agent {
            label params.NODE_LABEL
        }
        options {
            timeout(time: params.TIMEOUT, unit: 'MINUTES')
            timestamps()
            ansiColor('xterm')
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
            stage('Top ups Liqbot') {
                steps {
                    withDevopstools(
                        command: 'topup liqbot'
                    )
                    withGoogleSA('gcp-k8s') {
                        sh "kubectl rollout restart statefulset liqbot-app -n ${env.NET_NAME}"
                    }
                }
            }
            stage('Top ups Traderbot') {
                steps {
                    script {
                        try {
                            withDevopstools(
                                command: 'topup traderbot'
                            )
                            withGoogleSA('gcp-k8s') {
                                sh "kubectl rollout restart statefulset traderbot-app -n ${env.NET_NAME}"
                            }
                        } catch(err) {
                            print(err)
                            currentBuild.result = 'UNSTABLE'
                        }

                        try {
                            List additionalTraderbotsIds = []
                            if (params.ADDITIONAL_TRADER_BOTS_IDS) {
                                additionalTraderbotsIds = params.ADDITIONAL_TRADER_BOTS_IDS.split(',')
                            }

                            additionalTraderbotsIds.each{traderbotId ->
                                withDevopstools(
                                    command: 'topup traderbot --traderbot-id ' + traderbotId
                                )
                                withGoogleSA('gcp-k8s') {
                                    sh "kubectl rollout restart statefulset traderbot${traderbotId}-app -n ${env.NET_NAME}"
                                }
                            }
                        } catch(err) {
                            print(err)
                            currentBuild.result = 'UNSTABLE'
                        }
                    }
                }
            }
        }
        post {
            always {
                // script {
                //     slack.slackSendCIStatus channel: '#env-deploy', name: env.JOB_NAME, branch: 'Top-Up'
                // }
                cleanWs()
            }
        }
    }
}
