void call() {
    // NOTE: environment variables PSSH_USER and PSSH_KEYFILE are used by veganet.sh script
    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )
    Map dockerCredentials = [
        credentialsId: 'github-vega-ci-bot-artifacts',
        url: 'https://ghcr.io'
    ]
    def githubAPICredentials = usernamePassword(
        credentialsId: 'github-vega-ci-bot-artifacts',
        passwordVariable: 'GITHUB_API_TOKEN',
        usernameVariable: 'GITHUB_API_USER'
    )
    def doGitClone = { repo, branch ->
        dir(repo) {
            retry(3) {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: branch]],
                    userRemoteConfigs: [[
                        url: "git@github.com:vegaprotocol/${repo}.git",
                        credentialsId: 'vega-ci-bot'
                    ]]])
            }
        }
    }
    def veganet = { command ->
        dir('devops-infra') {
            withCredentials([githubAPICredentials]) {
                withDockerRegistry(dockerCredentials) {
                    withCredentials([sshCredentials]) {
                        withGoogleSA('gcp-k8s') {
                            sh script:  "./veganet.sh ${env.NET_NAME} ${command}"
                        }
                    }
                }
            }
        }
    }

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 20, unit: 'MINUTES')
            timestamps()
            disableConcurrentBuilds()
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        if (!params.VEGA_CORE_VERSION && params.RESTART == 'YES_FROM_CHECKPOINT') {
                            error("When restarting from check point you need to provide VEGA_CORE_VERSION otherwise it will not work")
                        }
                    }
                }
            }
            stage('Checkout') {
                parallel {
                    stage('devops-infra'){
                        steps {
                            script {
                                doGitClone('devops-infra', params.DEVOPS_INFRA_BRANCH)
                            }
                        }
                    }
                    stage('devopsscripts'){
                        when {
                            expression {
                                params.RESTART == 'YES_FROM_CHECKPOINT'
                            }
                        }
                        steps {
                            script {
                                doGitClone('devopsscripts', params.DEVOPSSCRIPTS_BRANCH)
                            }
                        }
                    }
                    stage('vega core'){
                        when {
                            expression {
                                params.VEGA_CORE_VERSION && params.BUILD_VEGA_CORE
                            }
                        }
                        steps {
                            script {
                                doGitClone('vega', params.VEGA_CORE_VERSION)
                            }
                        }
                    }
                    stage('ansible'){
                        steps {
                            script {
                                doGitClone('ansible', params.ANSIBLE_BRANCH)
                            }
                        }
                    }
                }
            }
            stage('Prepare'){
                parallel {
                    stage('Build Vega Core binary') {
                        when {
                            expression {
                                params.VEGA_CORE_VERSION && params.BUILD_VEGA_CORE
                            }
                        }
                        steps {
                            dir('vega') {
                                sh label: 'Compile vega core', script: """
                                    go build -v -o ./cmd/vega/vega-linux-amd64 ./cmd/vega
                                """
                                sh label: 'Sanity check', script: '''
                                    file ./cmd/vega/vega-linux-amd64
                                    ./cmd/vega/vega-linux-amd64 version
                                '''
                            }
                            sh "mkdir -p bin"
                            sh "mv vega/cmd/vega/vega-linux-amd64 bin/vega"
                            sh "chmod +x bin/vega"
                        }
                    }
                    stage('veganet docker pull') {
                        steps {
                            script {
                                veganet('pull')
                            }
                        }
                    }
                    stage('Download Vega Core binary') {
                        when {
                            expression {
                                params.VEGA_CORE_VERSION && !params.BUILD_VEGA_CORE
                            }
                        }
                        environment {
                            GITHUB_CREDS = "github-vega-ci-bot-artifacts"
                        }
                        steps {
                            withGHCLI('credentialsId': env.GITHUB_CREDS) {
                                sh "gh release --repo vegaprotocol/vega download ${params.VEGA_CORE_VERSION} --pattern '*linux*'"
                            }
                            sh "mkdir -p bin"
                            sh "mv vega-linux-amd64 bin/vega"
                            sh "chmod +x bin/vega"
                            sh "vega version"
                        }
                    }
                }
            }
            stage('Status') {
                steps {
                    script {
                        veganet('status')
                    }
                }
            }
            stage('Deploy Vega Core binary') {
                when {
                    expression {
                        params.VEGA_CORE_VERSION
                    }
                }
                environment {
                    VEGA_CORE_BINARY = "${env.WORKSPACE}/bin/vega"
                }
                steps {
                    script {
                        veganet('pushvega')
                    }
                }
            }
            stage('Deploy Vega Network Config') {
                when {
                    expression {
                        params.DEPLOY_CONFIG
                    }
                }
                steps {
                    dir('ansible') {
                        withCredentials([sshCredentials]) {
                            // Note: environment variables PSSH_KEYFILE and PSSH_USER
                            //        are set by withCredentials wrapper
                            sh label: 'ansible deploy run', script: """#!/bin/bash -e
                                ansible-playbook \
                                    --diff \
                                    -u "\${PSSH_USER}" \
                                    --private-key "\${PSSH_KEYFILE}" \
                                    --inventory inventories \
                                    --limit ${env.NET_NAME} \
                                    --tags vega-network-config \
                                    site.yaml
                            """
                        }
                    }
                }
            }
            stage('Restart Network - without checkpoint') {
                when {
                    expression {
                        params.RESTART == 'YES'
                    }
                }
                steps {
                    script {
                        veganet('bounce')
                    }
                }
            }
            stage('Restart Network - with checkpoint') {
                when {
                    expression {
                        params.RESTART == 'YES_FROM_CHECKPOINT'
                    }
                    expression {
                        params.VEGA_CORE_VERSION
                    }
                }
                steps {
                    dir('devopsscripts') {
                        withCredentials([sshCredentials]) {
                            sh """
                                go mod vendor
                                go run main.go old-network remote load-latest-checkpoint \
                                    --vega-binary "${env.WORKSPACE}/bin/vega" \
                                    --network ${env.NET_NAME} \
                                    --ssh-private-key ${env.PSSH_KEYFILE}  \
                                    --ssh-user ${env.PSSH_USER} \
                                    --no-secrets
                            """
                        }
                    }
                }
            }
            stage('Create Markets') {
                when {
                    expression {
                        params.CREATE_MARKETS
                    }
                }
                steps {
                    script {
                        veganet('create_markets')
                    }
                }
            }
            stage('Create Incentive Markets') {
                when {
                    expression {
                        params.CREATE_INCENTIVE_MARKETS
                    }
                }
                steps {
                    script {
                        veganet('incentive_create_markets')
                    }
                }
            }
            stage('Bounce Bots') {
                when {
                    expression {
                        params.BOUNCE_BOTS
                    }
                }
                environment {
                    REMOVE_BOT_WALLETS = "${params.REMOVE_WALLETS ? 'yes' : ''}"
                }
                steps {
                    script {
                        veganet('bounce_bots')
                    }
                }
            }
        }
        post {
            always {
                script {
                    slack.slackSendDeployStatus network: "${env.NET_NAME}",
                        version: params.VEGA_CORE_VERSION,
                        restart: params.RESTART != 'NO',
                }
                cleanWs()
            }
        }
    }
}
