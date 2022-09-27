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

    pipeline {
        agent any
        options {
            skipDefaultCheckout()
            timeout(time: 40, unit: 'MINUTES')
            timestamps()
            lock(resource: env.NET_NAME)
            ansiColor('x-term')
        }
        environment {
            PATH = "${env.WORKSPACE}/bin:${env.PATH}"
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
                    stage('vega'){
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            script {
                                doGitClone('vega', params.VEGA_VERSION)
                            }
                        }
                    }
                    stage('checkpoint-store'){
                        when {
                            expression { params.USE_CHECKPOINT }
                        }
                        steps {
                            script {
                                doGitClone('checkpoint-store', params.CHECKPOINT_STORE_BRANCH)
                            }
                        }
                    }
                    // stage('devopsscripts'){
                    //     steps {
                    //         script {
                    //             doGitClone('devopsscripts', params.DEVOPSSCRIPTS_BRANCH)
                    //         }
                    //     }
                    // }
                    stage('ansible'){
                        steps {
                            script {
                                doGitClone('ansible', params.ANSIBLE_BRANCH)
                            }
                        }
                    }
                    stage('networks-internal') {
                        steps {
                            script {
                                doGitClone('networks-internal', params.NETWORKS_INTERNAL_BRANCH)
                            }
                        }
                    }
                }
            }
            stage('Prepare') {
                parallel {
                    stage('Build vaga, data-node, vegawallet and visor') {
                        when {
                            expression { params.VEGA_VERSION }
                        }
                        steps {
                            dir('vega') {
                                sh label: 'Compile', script: """#!/bin/bash -e
                                    go build -v \
                                        -o ../bin/ \
                                        ./cmd/vega \
                                        ./cmd/data-node \
                                        ./cmd/vegawallet \
                                        ./cmd/visor
                                """
                            }
                            dir('bin') {
                                sh label: 'Sanity check: vega', script: '''#!/bin/bash -e
                                    file ./vega
                                    ./vega version
                                '''
                                sh label: 'Sanity check: data-node', script: '''#!/bin/bash -e
                                    file ./data-node
                                    ./data-node version
                                '''
                                sh label: 'Sanity check: vegawallet', script: '''#!/bin/bash -e
                                    file ./vegawallet
                                    ./vegawallet version
                                '''
                                sh label: 'Sanity check: visor', script: '''#!/bin/bash -e
                                    file ./visor
                                    ./visor --help
                                '''
                            }
                        }
                    }
                    stage('Checkpoint') {
                        when {
                            expression { params.USE_CHECKPOINT }
                        }
                        stages {
                            stage('Prepare scripts') {
                                options { retry(3) }
                                steps {
                                    dir('checkpoint-store/scripts') {
                                        sh '''#!/bin/bash -e
                                            go mod download -x
                                        '''
                                    }
                                }
                            }
                            stage('download') {
                                options { retry(3) }
                                steps {
                                    dir('checkpoint-store') {
                                        withCredentials([sshCredentials]) {
                                            sh label: 'Download latest checkpoint', script: """#!/bin/bash -e
                                                go run scripts/main.go \
                                                    download-latest \
                                                    --network "${env.NET_NAME}" \
                                                    --ssh-user "\${PSSH_USER}" \
                                                    --ssh-private-keyfile "\${PSSH_KEYFILE}" \
                                                    --vega-home /home/vega/vega_home
                                            """
                                            sh "git add ${env.NET_NAME}/*"
                                        }
                                    }
                                }
                            }
                            stage('Commit changes') {
                                steps {
                                    dir('checkpoint-store') {
                                        script {
                                            def changesToCommit = sh(script:'git diff --cached', returnStdout:true).trim()
                                            if (changesToCommit == '') {
                                                print('No changes to commit')
                                            } else {
                                                sshagent(credentials: ['vega-ci-bot']) {
                                                    sh 'git config --global user.email "vega-ci-bot@vega.xyz"'
                                                    sh 'git config --global user.name "vega-ci-bot"'
                                                    sh "git commit -m 'Automated update of checkpoints'"
                                                    sh "git push origin HEAD:main"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            stage('Get latest checkpoint path') {
                                steps {
                                    dir('checkpoint-store') {
                                        script {
                                            env.LATEST_CHECKPOINT_PATH = sh(script: """#!/bin/bash -e
                                                go run scripts/main.go \
                                                    local-latest \
                                                    --network "${env.NET_NAME}"
                                            """, returnStdout:true).trim()
                                        }
                                        print("Latest checkpoint path: ${env.LATEST_CHECKPOINT_PATH}")
                                    }
                                }
                            }
                        }
                    }
                }
            }  // End: Prepare
            stage('Generate genesis') {
                stages {
                    stage('Prepare scripts') {
                        options { retry(3) }
                        steps {
                            dir('networks-internal/scripts') {
                                sh '''#!/bin/bash -e
                                    go mod download -x
                                '''
                            }
                            dir('ansible/scripts') {
                                sh '''#!/bin/bash -e
                                    go mod download -x
                                '''
                            }
                        }
                    }
                    // TODO: generate vegawallet config toml file
                    stage('Generate new genesis') {
                        environment {
                            CHECKPOINT_ARG = "${params.USE_CHECKPOINT ? '--checkpoint "' + env.LATEST_CHECKPOINT_PATH + '"' : ' '}"
                        }
                        options { retry(3) }
                        steps {
                            dir('ansible') {
                                script {
                                    env.VALIDATOR_IDS = sh(script:"""
                                        go run scripts/main.go \
                                            get-validator-ids \
                                            --network "${env.NET_NAME}"
                                    """, returnStdout:true).trim()
                                }
                            }
                            dir('networks-internal') {
                                sh label: 'Generate genesis', script: """#!/bin/bash -e
                                    go run scripts/main.go \
                                        generate-genesis \
                                        --network "${env.NET_NAME}" \
                                        --validator-ids "${env.VALIDATOR_IDS}" \
                                        ${env.CHECKPOINT_ARG}
                                """
                                sh "git add ${env.NET_NAME}/*"
                            }
                        }
                    }
                    stage('Generate vegawallet config') {
                        options { retry(3) }
                        steps {
                            dir('ansible') {
                                script {
                                    env.DATA_NODE_IDS = sh(script:"""
                                        go run scripts/main.go \
                                            get-data-node-ids \
                                            --network "${env.NET_NAME}"
                                    """, returnStdout:true).trim()
                                }
                            }
                            dir('networks-internal') {
                                sh label: 'Generate vegawallet config', script: """#!/bin/bash -e
                                    go run scripts/main.go \
                                        generate-vegawallet-config \
                                        --network "${env.NET_NAME}" \
                                        --data-node-ids "${env.DATA_NODE_IDS}"
                                """
                                sh "git add ${env.NET_NAME}/*"
                            }
                        }
                    }
                    stage('Commit changes') {
                        environment {
                            NETWORKS_INTERNAL_GENESIS_BRANCH = "${env.NETWORKS_INTERNAL_GENESIS_BRANCH ?: 'main'}"
                        }
                        steps {
                            dir('networks-internal') {
                                script {
                                    sshagent(credentials: ['vega-ci-bot']) {
                                        // NOTE: the script to generate genesis.json is run from latest version from NETWORKS_INTERNAL_BRANCH
                                        // but the result might be commited to a different branch: NETWORKS_INTERNAL_GENESIS_BRANCH
                                        sh 'git config --global user.email "vega-ci-bot@vega.xyz"'
                                        sh 'git config --global user.name "vega-ci-bot"'
                                        sh "git stash"
                                        sh "git switch ${env.NETWORKS_INTERNAL_GENESIS_BRANCH}"
                                        sh "git checkout stash -- ${env.NET_NAME}/*"
                                        sh "git commit -m 'Automated update of genesis for ${env.NET_NAME}'"
                                        sh "git push -u origin ${env.NETWORKS_INTERNAL_GENESIS_BRANCH}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage('Restart Network') {
                when {
                    expression { env.ANSIBLE_LIMIT }
                }
                environment {
                    ANSIBLE_VAULT_PASSWORD_FILE = credentials('ansible-vault-password')
                    HASHICORP_VAULT_ADDR = 'https://vault.ops.vega.xyz'
                }
                steps {
                    script {
                        if (params.VEGA_VERSION) {
                            sh label: 'copy binaries to ansible', script: """#!/bin/bash -e
                                cp ./bin/vega ./ansible/roles/barenode/files/bin/
                                cp ./bin/data-node ./ansible/roles/barenode/files/bin/
                                cp ./bin/visor ./ansible/roles/barenode/files/bin/
                            """
                        }
                    }
                    dir('ansible') {
                        withCredentials([usernamePassword(credentialsId: 'hashi-corp-vault-jenkins-approle', passwordVariable: 'HASHICORP_VAULT_SECRET_ID', usernameVariable:'HASHICORP_VAULT_ROLE_ID')]) {
                            withCredentials([sshCredentials]) {
                                // Note: environment variables PSSH_KEYFILE and PSSH_USER
                                //        are set by withCredentials wrapper
                                sh label: 'ansible playbook run', script: """#!/bin/bash -e
                                    ansible-playbook \
                                        --diff \
                                        -u "\${PSSH_USER}" \
                                        --private-key "\${PSSH_KEYFILE}" \
                                        --inventory inventories \
                                        --limit "${env.ANSIBLE_LIMIT}" \
                                        --tag "${params.ACTION}" \
                                        --extra-vars '{"release_version": "${params.RELEASE_VERSION}", "unsafe_reset_all": ${params.UNSAFE_RESET_ALL}}' \
                                        playbooks/playbook-barenode.yaml
                                """
                            }
                        }
                    }
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
