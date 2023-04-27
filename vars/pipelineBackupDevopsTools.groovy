/* groovylint-disable DuplicateStringLiteral, LineLength, NestedBlockDepth */
def call() {
    String nodeLabel = params.NODE_LABEL ?: 'system-tests-capsule'
    int pipelineTimeout = params.TIMEOUT ? params.TIMEOUT as int : 60
    String devopsToolsBranch = params.DEVOPSTOOLS_BRANCH ?: 'backup-command'
    String pipelineAction = params.ACTION ?: 'BACKUP'
    String stateRepository = 'vega-backups-state'

    String server = params.SERVER ?: 'be02.validators-testnet.vega.xyz'

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
                        if (server == '') {
                            error('The SERVER parameter MUST be specified')
                        }

                        switch(pipelineAction) {
                            case 'BACKUP':
                                currentBuild.displayName = sprintf("#%d: Backup %s", buildNumber, server)
                                break
                            case 'RESTORE':
                                currentBuild.displayName = sprintf("#%d: Restore %s", buildNumber, server)
                                break
                            case 'LIST_BACKUPS':
                                currentBuild.displayName = sprintf("#%d: List backups %s", buildNumber, server)
                                break
                            default:
                                error('Unknown operation. Expected one of [BACKUP, RESTORE, LIST_BACKUPS], got ' + pipelineAction)
                        }
                    }
                }
            }

            stage('Checkout devopstools') {
                steps {
                    script {
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        gitClone(
                            vegaUrl: 'devopstools',
                            directory:'devopstools',
                            branch: devopsToolsBranch,
                        )
                        vegautils.buildGoBinary('devopstools', './devopstools', '.')
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

            stage('Upload devopstools binary to the server') {
                steps {
                    script {
                        withCredentials([
                            sshUserPrivateKey(
                                credentialsId: 'ssh-vega-network',
                                keyFileVariable: 'PSSH_KEYFILE',
                                usernameVariable: 'PSSH_USER'
                            )
                        ]) {
                            dir('devopstools') {
                                sh '''
                                    scp \
                                    -o "StrictHostKeyChecking=no" \
                                    -i "''' + PSSH_KEYFILE + '''" \
                                    devopstools \
                                    ''' + PSSH_USER + '''@''' + server + ''':/tmp/devopstools'''
                            }
                        }
                    }
                }
            }

            stage('Upload the previous backups state') {
                    steps {
                    dir(stateRepository) {
                        script {
                            if (fileExists(server + '.json')) {
                                withCredentials([sshUserPrivateKey(
                                    credentialsId: 'ssh-vega-network',
                                    keyFileVariable: 'PSSH_KEYFILE',
                                    usernameVariable: 'PSSH_USER'
                                )]) {
                                    sh '''
                                        scp \
                                        -o "StrictHostKeyChecking=no" \
                                        -i "''' + PSSH_KEYFILE + '''" \
                                        ''' + server + '''.json \
                                        ''' + PSSH_USER + '''@''' + server + ''':/tmp/vega-backup-state-''' + buildNumber + '''.json'''

                                    sh '''
                                        ssh \
                                        -o "StrictHostKeyChecking=no" \
                                        -i "''' + PSSH_KEYFILE + '''" \
                                        ''' + PSSH_USER + '''@''' + server + ''' \
                                        "sudo chown root:root /tmp/vega-backup-state-''' + buildNumber + '''.json" '''
                                }
                            }
                        }
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
                            string(credentialsId: 'vega-backups-state-encryption-key', variable: 'ENCRYPTION_TOKEN')
                        ]) {
                            try {
                                List args = [
                                    '--passphrase', ENCRYPTION_TOKEN,
                                    '--local-state-file', '/tmp/vega-backup-state-' + buildNumber + '.json',
                                ]

                                sh '''
                                    ssh \
                                    -o "StrictHostKeyChecking=no" \
                                    -i "''' + PSSH_KEYFILE + '''" \
                                    ''' + PSSH_USER + '''@''' + server + ''' \
                                    "sudo /tmp/devopstools backup backup ''' + args.join(' ') + '''" '''
                            } catch (err) {
                                print('ERROR: ' + err)
                                currentBuild.result = 'FAILED'
                            }
                        }
                    }
                }
            }
            
            stage('Restore') {
                 when {
                    expression {
                        pipelineAction == 'RESTORE'
                    }
                }

                steps {
                    script {
                        error('Not implemented yet')
                    }
                }

            }

            stage('Save state') {
                when {
                    expression {
                        pipelineAction == 'BACKUP'
                    }
                }

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
                                    ''' + PSSH_USER + '''@''' + server + ''':/tmp/vega-backup-state-''' + buildNumber + '''.json \
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
                        sh 'devopstools/devopstools backup list-backups --local-state-file ' + stateRepository + '/' + server + '.json'
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