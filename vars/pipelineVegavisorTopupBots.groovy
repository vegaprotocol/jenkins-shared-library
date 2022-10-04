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
                parallel {
                    stage('Liqbot') {
                        steps {
                            dir ('devopstools') {
                                withCredentials([
                                    usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
                                ]) {
                                    sh "go run main.go topup liqbot --network ${env.NET_NAME} --github-token ${TOKEN}"
                                }
                            }
                            withGoogleSA('gcp-k8s') {
                                    sh "kubectl rollout restart statefulset liqbot-app -n ${env.NET_NAME}"
                            }
                        }
                    }
                    stage('Traderbot') {
                        steps {
                            dir ('devopstools') {
                                withCredentials([
                                    usernamePassword(credentialsId: 'github-vega-ci-bot-artifacts', passwordVariable: 'TOKEN', usernameVariable:'USER')
                                ]) {
                                    sh "go run main.go topup traderbot --network ${env.NET_NAME} --github-token ${TOKEN}"
                                }
                            }
                            withGoogleSA('gcp-k8s') {
                                    sh "kubectl rollout restart statefulset traderbot-app -n ${env.NET_NAME}"
                            }
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
