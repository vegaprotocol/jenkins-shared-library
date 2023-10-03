def call() {
    boolean researchBots = ((env.RESEARCH_BOT ?: "false") as String).toLowerCase() == "true"
    boolean liqBot = ((env.LIQBOT ?: "false") as String).toLowerCase() == "true"
    boolean traderBot = ((env.TRADERBOT ?: "false") as String).toLowerCase() == "true"

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
            GOBIN = "${env.WORKSPACE}/gobin"
        }

        stages {
            stage('Prepare') {
                steps {
                    script {
                        sh 'printenv'
                        print("params=${params.inspect()}")
                        print("Liqbot: " + liqBot)
                        print("Traderbot: " + traderBot)
                        print("Research-bot: " + researchBots)
                        vegautils.commonCleanup()
                    }
                }
            }
            stage('Checkout') {
                steps {
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
                when {
                    expression {
                        liqBot
                    }
                }
                steps {
                    lock(resource: "ethereum-minter-${env.NET_NAME}") {
                        withDevopstools(
                            command: 'topup liqbot'
                        )
                    }
                    withGoogleSA('gcp-k8s') {
                        sh "kubectl rollout restart statefulset liqbot-app -n ${env.NET_NAME}"
                    }
                }
            }
            stage('Top ups Traderbot') {
                when {
                    expression {
                        traderBot
                    }
                }

                steps {
                    script {
                        try {
                            lock(resource: "ethereum-minter-${env.NET_NAME}") {
                                withDevopstools(
                                    command: 'topup traderbot'
                                )
                            }
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

                            lock(resource: "ethereum-minter-${env.NET_NAME}") {
                                additionalTraderbotsIds.each{traderbotId ->
                                    withDevopstools(
                                        command: 'topup traderbot --traderbot-id ' + traderbotId
                                    )
                                    withGoogleSA('gcp-k8s') {
                                        sh "kubectl rollout restart statefulset traderbot${traderbotId}-app -n ${env.NET_NAME}"
                                    }
                                }
                            }
                        } catch(err) {
                            print(err)
                            currentBuild.result = 'UNSTABLE'
                        }
                    }
                }
                }

            stage('Top ups research-bots') {
                when {
                    expression {
                        researchBots
                    }
                }

                steps {
                    script {
                        String researchBotsURL = 'https://' + env.NET_NAME + '.bots.vega.rocks/traders'
                        vegautils.waitForValidHTTPCode(researchBotsURL, 20, 5)

                        try {
                            retry(3) {
                                lock(resource: "ethereum-minter-${env.NET_NAME}") {
                                    withDevopstools(
                                        command: 'topup with-transfer --network ' + env.NET_NAME + ' --traders-url ' + researchBotsURL 
                                    )
                                }
                            }
                            
                            sleep 90 // some time to make sure deposits are reflected in the network
                            withCredentials([sshUserPrivateKey(
                                credentialsId: 'ssh-vega-network',
                                keyFileVariable: 'PSSH_KEYFILE',
                                usernameVariable: 'PSSH_USER'
                            )]) {
                                sh '''
                                    ssh \
                                    -o "StrictHostKeyChecking=no" \
                                    -i "''' + PSSH_KEYFILE + '''" \
                                    ''' + PSSH_USER + '''@bots.vega.rocks \
                                    "sudo systemctl restart  bots-''' + env.NET_NAME + '''.service"'''
                            }
                            sleep 60
                            vegautils.waitForValidHTTPCode(researchBotsURL, 20, 5)
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
