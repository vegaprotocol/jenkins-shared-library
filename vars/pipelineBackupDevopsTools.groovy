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
                                devopstools ''' + server + ''':/tmp/devopstools'''
                        }
                    }
                }
            }

            stage('Upload the previous backups state') {
                dir(stateRepository) {
                    script {
                        if (!fileExists(server + '.json'))
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
