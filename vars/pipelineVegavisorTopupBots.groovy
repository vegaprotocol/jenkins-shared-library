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
                    withDevopstools(
                        command: 'topup traderbot'
                    )
                    withGoogleSA('gcp-k8s') {
                        sh "kubectl rollout restart statefulset traderbot-app -n ${env.NET_NAME}"
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
