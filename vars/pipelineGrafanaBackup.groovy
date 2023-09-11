def call() {
    pipeline {
        agent {
            label 'tiny'
        }
        options {
            timeout(unit: 'MINUTES', time: 30)
        }
        stages {
            stage('checkout') {
                steps {
                    gitClone(
                        vegaUrl: 'grafana-backup',
                        directory: 'grafana-backup',
                        branch: 'main'
                    )
                }
            }
            stage('trigger backup') {
                environment {
                    GRAFANA_API_TOKEN = credentials('grafana-api-token')
                }
                steps {
                    dir('grafana-backup') {
                        sh '''
                            go run scripts/main.go download-config \
                                --url https://monitoring.vega.community \
                                --api-token $GRAFANA_API_TOKEN
                        '''
                    }
                }
            }
            stage('commit results') {
                steps {
                    makeCommit(
                        makeCheckout: false,
                        directory: 'grafana-backup',
                        branchName: 'backup',
                        commitMessage: '[Automated] new backup',
                        commitAction: 'git add dashboards alerts data-sources.json'
                    )
                }
            }
        }
        post {
            always {
                cleanWs()
            }
        }
    }
}