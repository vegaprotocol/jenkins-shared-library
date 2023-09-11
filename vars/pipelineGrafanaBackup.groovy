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
                    dir('grafana-backup/scripts') {
                        sh '''
                            go run main.go download-config \
                                --config-dir ../ \
                                --url https://monitoring.vega.community \
                                --api-token $GRAFANA_API_TOKEN
                            rm -rf dashboards alerts data-sources.json
                            mv scripts/alerts scripts/dashboards scripts/data-sources.json ./
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