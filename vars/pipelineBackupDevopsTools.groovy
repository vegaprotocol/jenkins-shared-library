/* groovylint-disable DuplicateStringLiteral, NestedBlockDepth */
def call() {
    String nodeLabel = params.NODE_LABEL ?: 'general'
    int pipelineTimeout = params.TIMEOUT ? params.TIMEOUT as int : 60
    String devopsToolsBranch = params.DEVOPSTOOLS_BRANCH ?: 'backup-command'
    String pipelineAction = params.ACTION ?: 'BACKUP'
    String stateRepository = 'vega-backups-state'

    String server = params.SERVER ?: 'be02.validators-testnet.vega.xyz'

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
            stage('Checkout devopstools') {
                steps {
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

            stage('Checkout backup state') {
                gitClone(
                    vegaUrl: stateRepository,
                    directory: stateRepository,
                    branch: 'main'
                )
            }

            stage('Upload devopstools binary to the server') {
                script {
                    withCredentials([sshUserPrivateKey(
                        credentialsId: 'ssh-vega-network',
                        keyFileVariable: 'PSSH_KEYFILE',
                        usernameVariable: 'PSSH_USER'
                    )]) {
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

            stage('Upload the previous backups state') {
                dir(stateRepository) {
                    script {
                        if (!fileExists(server + '.json')) {
                            withCredentials([sshUserPrivateKey(
                                credentialsId: 'ssh-vega-network',
                                keyFileVariable: 'PSSH_KEYFILE',
                                usernameVariable: 'PSSH_USER'
                            )]) {
                                dir('devopstools') {
                                    sh '''
                                        scp \
                                        -o "StrictHostKeyChecking=no" \
                                        -i "''' + PSSH_KEYFILE + '''" \
                                        ''' + server + '''.json \
                                        ''' + PSSH_USER + '''@''' + server + ''':/tmp/vega-backup-state.json'''
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
                        withCredentials([sshUserPrivateKey(
                                credentialsId: 'ssh-vega-network',
                                keyFileVariable: 'PSSH_KEYFILE',
                                usernameVariable: 'PSSH_USER'
                            )]) {
                                dir('devopstools') {
                                    sh '''
                                        ssh \
                                        -o "StrictHostKeyChecking=no" \
                                        -i "''' + PSSH_KEYFILE + '''" \
                                        ''' + PSSH_USER + '''@''' + server + ''':/tmp/vega-backup-state.json''' \
                                        'sudo /tmp/devopstools backup list-backups'
                                }
                            }

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
