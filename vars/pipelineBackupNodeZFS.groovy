/* groovylint-disable DuplicateStringLiteral, NestedBlockDepth */
def call() {
    String nodeLabel = params.NODE_LABEL ?: 'tiny'
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
            GOBIN = "${env.WORKSPACE}/gobin"
        }
        stages {
            stage('Prepare') {
                steps {
                    script {
                        vegautils.commonCleanup()
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
                                    -o "UserKnownHostsFile=/dev/null" \
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

            stage('Delete old local zfs snapshots') {
                steps {
                    sh label: 'delete old local zfs snapshots', script: """#!/bin/bash -e
                        sudo zfs list -t snapshot -o name -S creation -H | tail -n +61 | xargs -n 1 --no-run-if-empty sudo zfs destroy
                    """
                    // command explanation:
                    // "-t snapshot" - print snapshots only
                    // "-o name" - print only names
                    // "-H" - do not print headers row
                    // "-S creation" - order by creation time, newest first
                    // "tail -n +61" - skip first 60 rows (there are 5 rows for each snapshots, we create 4 snapshots a day, every 7th snapshot is a full one, so we need to keep at least: 5 * 7 = 35)
                }
            }
        }
        post {
            failure {
                script {
                    slack.sendSlackMessage channel: '#ops-alerts', name: 'Backup on ' + server + ' failed'
                }
            }
            success {
                script {
                    slack.sendSlackMessage channel: '#ops-alerts', name: 'Backup on ' + server + ' finished successfully'
                }
            }
            always {
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