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
                    stage('vega core'){
                        when {
                            params.VEGA_CORE_VERSION && params.BUILD_VEGA_CORE
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
                                String hash = sh(
                                    script: 'git rev-parse HEAD|cut -b1-8',
                                    returnStdout: true,
                                ).trim()
                                String ldflags = "-X main.CLIVersion=dev-${hash} -X main.CLIVersionHash=${hash}"
                                sh label: 'Compile vega core', script: """
                                    go build -v -o ./cmd/vega/vega-linux-amd64 -ldflags "${ldflags}" ./cmd/vega
                                """
                                sh label: 'Sanity check', script: '''
                                    file ./cmd/vega/vega-linux-amd64
                                    ./cmd/vega/vega-linux-amd64 version
                                '''
                            }
                        }
                    }
                    stage('Get vega core binary'){
                        when {
                            expression {
                                params.VEGA_CORE_VERSION && !params.BUILD_VEGA_CORE
                            }
                        }
                        environment {
                            TAG = params.VEGA_CORE_VERSION
                        }
                        steps {
                            script {
                                veganet('getvega')
                            }
                        }
                    }
                    stage('veganet docker pull') {
                        steps {
                            script {
                                veganet('pull')
                            }
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
                    VEGA_CORE_BINARY = "${env.WORKSPACE}/vega/cmd/vega/vega-linux-amd64"
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
            stage('Restart Network') {
                when {
                    expression {
                        params.RESTART != 'NO'
                    }
                }
                environment {
                    RESTORE_FROM_CHECKPOINT = "${params.RESTART == 'YES_FROM_CHECKPOINT' ? 'yes' : 'no'}"
                }
                steps {
                    script {
                        veganet('bounce')
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
                steps {
                    script {
                        veganet('bounce_bots')
                    }
                }
            }
        }
        post {
            always {
                slack.slackSendDeployStatus network: "${env.NET_NAME}",
                    version: params.VEGA_CORE_VERSION,
                    restart: params.RESTART != 'NO',
                cleanWs()
            }
        }
    }
}
