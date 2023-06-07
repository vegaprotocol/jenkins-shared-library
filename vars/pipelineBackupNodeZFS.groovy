/* groovylint-disable DuplicateStringLiteral, NestedBlockDepth */
def call() {
    String nodeLabel = params.NODE_LABEL ?: 's-2vcpu-4gb'
    int pipelineTimeout = params.TIMEOUT ? params.TIMEOUT as int : 120
    String pipelineAction = params.ACTION ?: 'BACKUP'
    String stateRepository = 'vega-backups-state'

    String server = params.SERVER ?: ''

    int buildNumber = currentBuild.number

    pipeline {
        agent {
            label nodeLabel
        }
        options {
            timeout(time: pipelineTimeout, unit: 'MINUTES')
            timestamps()
            ansiColor('xterm')
        }
        environment {
            CREDENTIALS_ID = 'ssh-vega-network'
            GOOS = 'linux'
            GOARCH = 'amd64'
            CGO_ENABLED = '0'
        }
        stages {
            stage('Prepare') {
                steps {
                    script {
                        if (server == "") {
                            error("Backup server cannot be empty")
                        }

                        switch(pipelineAction) {
                            case 'BACKUP':
                                currentBuild.displayName = sprintf("#%d: Backup %s", buildNumber, server)
                                break
                            case 'RESTORE':
                                currentBuild.displayName = sprintf("#%d: Restore %s", buildNumber, server)
                                break
                        }
                    }
                }
            }

            stage('Checkout backup state') {
                steps {
                    script {
                        gitClone(
                            vegaUrl: stateRepository,
                            directory: stateRepository,
                            branch: 'main'
                        )
                    }
                }
            }

            stage('Backup') {
                when {
                    expression {
                        pipelineAction == 'BACKUP'
                    }
                }
                steps {
                    script {
                        withCredentials([
                            sshUserPrivateKey(
                                credentialsId: 'ssh-vega-network',
                                keyFileVariable: 'PSSH_KEYFILE',
                                usernameVariable: 'PSSH_USER'
                            ),
                            usernamePassword(
                                credentialsId: 'digitalocean-s3-credentials',
                                passwordVariable: 'AWS_SECRET_ACCESS_KEY',
                                usernameVariable: 'AWS_ACCESS_KEY_ID'
                            ),
                        ]) {
                            sh '''
                                ssh \
                                -o "StrictHostKeyChecking=no" \
                                -i "''' + PSSH_KEYFILE + '''" \
                                ''' + PSSH_USER + '''@''' + server + ''' \
                                "AWS_ACCESS_KEY_ID='''+AWS_ACCESS_KEY_ID+''' AWS_SECRET_ACCESS_KEY='''+AWS_SECRET_ACCESS_KEY+''' sudo -E devopstools backup create --config-path /etc/backup-config.toml"'''
                        }
                    }
                }
            }

            stage('Save state') {
                steps {
                    script {
                        withCredentials([sshUserPrivateKey(
                            credentialsId: 'ssh-vega-network',
                            keyFileVariable: 'PSSH_KEYFILE',
                            usernameVariable: 'PSSH_USER'
                        )]) {
                            dir (stateRepository) {
                                sh '''
                                    scp \
                                    -o "StrictHostKeyChecking=no" \
                                    -i "''' + PSSH_KEYFILE + '''" \
                                    ''' + PSSH_USER + '''@''' + server + ''':/etc/backup-state.json \
                                    ''' + server + '''.json'''
                            }
                        }

                        makeCommit(
                            makeCheckout: false,
                            directory: stateRepository,
                            branchName: 'state-update',
                            commitMessage: '[Automated] new backup state for ' + server,
                            commitAction: 'git add ' + server + '.json'
                        )
                    }
                }
            }

            stage('Print backup state') {
                steps {
                    script {
                        sh 'cat ' + stateRepository + '/' + server + '.json'
                    }
                }
            }
        }
        post {
            always {
                // script {
                //     slack.slackSendCIStatus channel: '#tbd...', name: env.JOB_NAME, branch: 'Top-Up'
                // }
                cleanWs()
            }
        }
    }
}
// library (
//                 identifier: "vega-shared-library@main",
//                 changelog: false,
//             )
// call()