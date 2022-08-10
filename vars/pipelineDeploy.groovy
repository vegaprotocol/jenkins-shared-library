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
    def networksNameMap = [
        devnet: 'devnet1',
        testnet: 'fairground',
        stagnet: 'stagnet1',
        stagnet2: 'stagnet2',
    ]

    def doGitClone = { repo, branch ->
        dir(repo) {
            retry(3) {
                // returns object:
                // [GIT_BRANCH:origin/master,
                // GIT_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41,
                // GIT_PREVIOUS_SUCCESSFUL_COMMIT:5897d0e927e920fc217f967e91ea086f8cf2bb41, 
                // GIT_URL:git@github.com:vegaprotocol/devops-infra.git]
                return checkout([
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

    def waitForURL = { address ->
        timeout(3) {
            waitUntil {
                script {
                    def r = sh returnStatus: true, script: 'curl -X GET ' + address
                    return r == 0
                }
            }
        }
    }

    def netSsh = { command ->
        return 'ssh -t -i $PSSH_KEYFILE $PSSH_USER@n04.$NET_NAME.vega.xyz "sudo ' + command + '"'
    }

    def doesDockerImageExist = { imageName ->
        timeout(time: 30, unit: 'SECONDS') { {
            waitUntil {
                script {
                    def r = sh returnStatus: true, script: 'docker manifest inspect ' + imageName
                    return r == 0
                }
            }
        }
    }

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 40, unit: 'MINUTES')
            timestamps()
            disableConcurrentBuilds()
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
            DOCKER_IMAGE_TAG = params.VEGA_VERSION.replaceAll('/', '-')
            DOCKER_IMAGE_TAG_HASH = ''
        }
        stages {
            stage('CI Config') {
                steps {
                    sh "printenv"
                    echo "params=${params.inspect()}"
                    script {
                        if (!params.VEGA_VERSION && params.RESTART == 'YES_FROM_CHECKPOINT') {
                            error("When restarting from check point you need to provide VEGA_VERSION otherwise it will not work")
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
                                params.VEGA_VERSION && params.BUILD_VEGA_CORE
                            }
                        }
                        steps {
                            script {
                                def repoVars = doGitClone('vega', params.VEGA_VERSION)
                                env.DOCKER_IMAGE_TAG_HASH = repoVars.GIT_COMMIT?.substring(0, 8)
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
                    stage('checkpoint store') {
                        when {
                            expression {
                                env.NET_NAME == 'testnet'
                            }
                        }
                        steps {
                            script {
                                doGitClone('checkpoint-store', 'main')
                            }
                        }
                    }
                    stage('vegatools') {
                        when {
                            expression {
                                env.NET_NAME == 'testnet'
                            }
                        }
                        steps {
                            script {
                                doGitClone('vegatools', 'develop')
                            }
                            dir('vegatools') {
                                sh "go install ./..."
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
                                params.VEGA_VERSION && params.BUILD_VEGA_CORE
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
                                params.VEGA_VERSION && !params.BUILD_VEGA_CORE
                            }
                        }
                        environment {
                            GITHUB_CREDS = "github-vega-ci-bot-artifacts"
                        }
                        steps {
                            withGHCLI('credentialsId': env.GITHUB_CREDS) {
                                sh "gh release --repo vegaprotocol/vega download ${params.VEGA_VERSION} --pattern '*linux*'"
                            }
                            sh "mkdir -p bin"
                            sh "mv vega-linux-amd64 bin/vega"
                            sh "chmod +x bin/vega"
                        }
                    }
                    stage('Build data-node docker image') {
                        when {
                            expression {
                                env.DOCKER_IMAGE_TAG_HASH && !doesDockerImageExist("ghcr.io/vegaprotocol/vega/data-node:${env.DOCKER_IMAGE_TAG_HASH}")
                            }
                        }
                        environment {
                            DATANODE_DOCKER_IMAGE_BRANCH = "ghcr.io/vegaprotocol/vega/data-node:${env.DOCKER_IMAGE_TAG}"
                            DATANODE_DOCKER_IMAGE_HASH = "ghcr.io/vegaprotocol/vega/data-node:${env.DOCKER_IMAGE_TAG_HASH}"
                        }
                        stages {
                            stage('Build') {
                                steps {
                                    dir('vega') {
                                        sh label: 'Build docker image', script: """#!/bin/bash -e
                                            docker build \
                                                -f docker/data-node.dockerfile \
                                                -t ${DATANODE_DOCKER_IMAGE_BRANCH} \
                                                -t ${DATANODE_DOCKER_IMAGE_HASH} \
                                                .
                                        """
                                    }
                                    sh label: 'Sanity check', script: """#!/bin/bash -e
                                        docker run --rm -it \
                                            ${DATANODE_DOCKER_IMAGE_BRANCH} \
                                            version
                                    """
                                }
                            }
                            stage('Publish') {
                                steps {
                                    withDockerRegistry(dockerCredentials) {
                                        sh label: 'Publish docker image - branch tag', script: """#!/bin/bash -e
                                            docker push ${DATANODE_DOCKER_IMAGE_BRANCH}
                                        """
                                        sh label: 'Publish docker image - hash tag', script: """#!/bin/bash -e
                                            docker push ${DATANODE_DOCKER_IMAGE_HASH}
                                        """
                                    }
                                }
                            }
                        }
                    }
                    stage('Build vegawallet docker image') {
                        when {
                            expression {
                                env.DOCKER_IMAGE_TAG_HASH && !doesDockerImageExist("ghcr.io/vegaprotocol/vega/vegawallet:${env.DOCKER_IMAGE_TAG_HASH}")
                            }
                        }
                        environment {
                            VEGAWALLET_DOCKER_IMAGE_BRANCH = "ghcr.io/vegaprotocol/vega/vegawallet:${env.DOCKER_IMAGE_TAG}"
                            VEGAWALLET_DOCKER_IMAGE_HASH = "ghcr.io/vegaprotocol/vega/vegawallet:${env.DOCKER_IMAGE_TAG_HASH}"
                        }
                        stages {
                            stage('Build') {
                                steps {
                                    dir('vega') {
                                        sh label: 'Build docker image', script: """#!/bin/bash -e
                                            docker build \
                                                -f docker/vegawallet.dockerfile \
                                                -t ${VEGAWALLET_DOCKER_IMAGE_BRANCH} \
                                                -t ${VEGAWALLET_DOCKER_IMAGE_HASH} \
                                                .
                                        """
                                    }
                                    sh label: 'Sanity check', script: """#!/bin/bash -e
                                        docker run --rm -it \
                                            ${VEGAWALLET_DOCKER_IMAGE_BRANCH} \
                                            version
                                    """
                                }
                            }
                            stage('Publish') {
                                steps {
                                    withDockerRegistry(dockerCredentials) {
                                        sh label: 'Publish docker image - branch tag', script: """#!/bin/bash -e
                                            docker push ${VEGAWALLET_DOCKER_IMAGE_BRANCH}
                                        """
                                        sh label: 'Publish docker image - hash tag', script: """#!/bin/bash -e
                                            docker push ${VEGAWALLET_DOCKER_IMAGE_HASH}
                                        """
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage('Stop network') {
                when {
                    expression {
                        params.RESTART == 'YES_FROM_CHECKPOINT' || params.RESTART == 'YES'
                    }
                }
                steps {
                    script {
                        veganet('stopbots stop')
                    }
                }
            }
            stage('Backup') {
                when {
                    expression {
                        env.NET_NAME == 'testnet'
                    }
                }
                steps {
                    script {
                        veganet('chainstorecopy vegalogcopy')
                    }
                }
            }
            stage('Store checkpoint') {
                when {
                    expression {
                        env.NET_NAME == 'testnet'
                    }
                }
                steps {
                    withCredentials([sshCredentials]) {
                        script {
                            newestFile = sh (
                                script: netSsh('ls -t /home/vega/.local/state/vega/node/checkpoints/ | head -n 1'),
                                returnStdout: true,
                            ).trim()
                            version = sh (
                                script: netSsh('/home/vega/current/vega version'),
                                returnStdout: true,
                            ).trim().split(" ")[2]
                            user = sh (
                                script: 'whoami',
                                returnStdout: true,
                            ).trim()
                            sh script: netSsh("cp /home/vega/.local/state/vega/node/checkpoints/${newestFile} /tmp/${newestFile}")
                            sh script: netSsh("chown ${user}:${user} /tmp/${newestFile}")
                            sh "scp -i ${env.PSSH_KEYFILE} ${env.PSSH_USER}@n04.${env.NET_NAME}.vega.xyz:/tmp/${newestFile} ."
                            sh "mkdir -p checkpoint-store/Fairground/${version}"
                            sh "mv ${newestFile} checkpoint-store/Fairground/${version}/"
                            dir('checkpoint-store'){
                                sh "vegatools checkpoint --file 'Fairground/${version}/${newestFile}' --out 'Fairground/${version}/${newestFile}.json'"
                                sshagent(credentials: ['vega-ci-bot']) {
                                    sh 'git config --global user.email "vega-ci-bot@vega.xyz"'
                                    sh 'git config --global user.name "vega-ci-bot"'
                                    sh "git add Fairground/${version}"
                                    sh "git commit -m 'Automated update of checkpoint from ${env.BUILD_URL}'"
                                    sh "git push origin HEAD:main"
                                }
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
                        params.VEGA_VERSION
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
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                }
                steps {
                    dir('ansible') {
                        withCredentials([sshCredentials]) {
                            // Note: environment variables PSSH_KEYFILE and PSSH_USER
                            //        are set by withCredentials wrapper
                            script {
                                ['tendermint', 'vegaserver'].each { playbook ->
                                    sh label: 'ansible deploy run', script: """#!/bin/bash -e
                                        ansible-playbook \
                                            --diff \
                                            -u "\${PSSH_USER}" \
                                            --private-key "\${PSSH_KEYFILE}" \
                                            --inventory inventories \
                                            --limit "${env.NET_NAME}" \
                                            --tags vega-network-config \
                                            playbooks/playbook-${playbook}.yaml
                                    """
                                }
                            }
                        }
                    }
                }
            }
            stage('Load checkpoint') {
                when {
                    expression {
                        params.RESTART == 'YES_FROM_CHECKPOINT'
                    }
                    expression {
                        params.VEGA_VERSION
                    }
                }
                steps {
                    dir('devopsscripts') {
                        withCredentials([sshCredentials]) {

                            sh """
                                go mod vendor
                                go run main.go old-network remote load-latest-checkpoint \
                                    --vega-binary "${env.WORKSPACE}/bin/vega" \
                                    --network '""" + networksNameMap[env.NET_NAME] +"""' \
                                    --ssh-private-key "${env.PSSH_KEYFILE}"  \
                                    --ssh-user "${env.PSSH_USER}" \
                                    --no-secrets
                            """
                        }
                    }
                }
            }
            stage('Reset chain state') {
                when {
                    expression {
                        params.RESTART == 'YES'
                    }
                }
                steps {
                    script {
                        veganet('nukedata vegareinit')
                    }
                }
            }
            stage('Start') {
                when {
                    expression {
                        params.RESTART == 'YES' || params.RESTART == 'YES_FROM_CHECKPOINT'
                    }
                }
                environment {
                    DATANODE_TAG = env.DOCKER_IMAGE_TAG_HASH ?: env.DOCKER_IMAGE_TAG
                }
                steps {
                    script {
                        veganet('start_datanode start')
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
                        def dnsAlias = env.DNS_ALIAS ?: env.NET_NAME
                        waitForURL('https://wallet.' + dnsAlias + '.vega.xyz/api/v1/status')
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
            stage('Deploy wallet') {
                when {
                    expression {
                        env.DEPLOY_WALLET && params.VEGA_VERSION
                    }
                }
                environment {
                    VEGAWALLET_VERSION = env.DOCKER_IMAGE_TAG_HASH ?: env.DOCKER_IMAGE_TAG
                }
                steps {
                    makeCommit(
                        directory: 'k8s',
                        url: 'git@github.com:vegaprotocol/k8s.git',
                        branchName: "${env.NET_NAME}-wallet-update",
                        commitMessage: '[Automated] wallet version update',
                        commitAction: "echo ${env.VEGAWALLET_VERSION} > charts/apps/vegawallet/${env.NET_NAME}/VERSION"
                    )
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
                cleanWs()
                script {
                    slack.slackSendDeployStatus (
                        network: "${env.NET_NAME}",
                        version: params.VEGA_VERSION,
                        restart: params.RESTART != 'NO',
                    )
                }
            }
        }
    }
}
